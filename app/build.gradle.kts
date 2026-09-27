import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

// This build does not use the Android Gradle Plugin (Google's Maven repository is not
// reachable from the environment this project was created in). Kotlin is compiled against
// android.jar with the regular Kotlin/JVM plugin, and the APK is assembled by calling the
// SDK tools directly: aapt2 (resources + manifest), D8 (dex), zipalign and apksigner.
// Run tools/setup-toolchain.sh once, then `gradle :app:assembleApk`.

plugins {
    kotlin("jvm") version "2.4.20"
}

val applicationId = "com.tryagain2019.androidplot"
val appVersionCode = 7
val appVersionName = "1.5.1"
val minSdk = 26
val targetSdk = 34

val toolsCache: File = rootProject.file("tools/cache")
val sdkDir: File? = (System.getenv("ANDROID_HOME") ?: System.getenv("ANDROID_SDK_ROOT"))?.let(::File)

fun optionalProperty(name: String): String? = findProperty(name) as String?

val androidJar: File = optionalProperty("androidJar")?.let(::File)
    ?: sdkDir?.resolve("platforms/android-$targetSdk/android.jar")?.takeIf { it.isFile }
    ?: toolsCache.resolve("android-$targetSdk.jar")

val d8Jar: File = optionalProperty("d8Jar")?.let(::File)
    ?: toolsCache.listFiles { f -> f.name.startsWith("r8-") && f.name.endsWith(".jar") }?.maxByOrNull { it.name }
    ?: toolsCache.resolve("r8.jar")

/** Finds an SDK build tool in the newest installed build-tools directory, falling back to PATH. */
fun buildTool(name: String): String {
    optionalProperty("${name}Path")?.let { return it }
    val fromSdk = sdkDir?.resolve("build-tools")?.listFiles()
        ?.sortedByDescending { it.name }
        ?.map { it.resolve(name) }
        ?.firstOrNull { it.canExecute() }
    return fromSdk?.absolutePath ?: name
}

dependencies {
    compileOnly(files(androidJar))
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    testImplementation(kotlin("test-junit"))
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20260814")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.11.0")
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

// App code is compiled against android.jar only (like the Android plugin's bootclasspath), so
// JDK-only APIs cannot sneak in. Tests keep the JDK because they run on the host JVM.
tasks.named<KotlinCompile>("compileKotlin") {
    compilerOptions.noJdk.set(true)
}

tasks.test {
    useJUnit()
    testLogging {
        events("failed")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}

val apkWork = layout.buildDirectory.dir("apk")
val manifestFile = file("src/main/AndroidManifest.xml")
val resDir = file("src/main/res")
val assetsDir = file("src/main/assets")

val compileResources by tasks.registering(Exec::class) {
    val out = apkWork.map { it.file("res-compiled.zip") }
    inputs.dir(resDir)
    outputs.file(out)
    doFirst { out.get().asFile.parentFile.mkdirs() }
    commandLine(buildTool("aapt2"), "compile", "--dir", resDir.absolutePath, "-o", out.get().asFile.absolutePath)
}

val linkResources by tasks.registering(Exec::class) {
    dependsOn(compileResources)
    val compiled = apkWork.map { it.file("res-compiled.zip") }
    val out = apkWork.map { it.file("resources.apk") }
    inputs.file(manifestFile)
    inputs.dir(assetsDir)
    inputs.file(compiled)
    inputs.file(androidJar)
    outputs.file(out)
    commandLine(
        buildTool("aapt2"), "link",
        "-o", out.get().asFile.absolutePath,
        "-I", androidJar.absolutePath,
        "--manifest", manifestFile.absolutePath,
        "-A", assetsDir.absolutePath,
        "--min-sdk-version", minSdk.toString(),
        "--target-sdk-version", targetSdk.toString(),
        "--version-code", appVersionCode.toString(),
        "--version-name", appVersionName,
        "--no-compress-regex", "\\.(js|html|css)$",
        compiled.get().asFile.absolutePath,
    )
}

val dex by tasks.registering(JavaExec::class) {
    val appJar = tasks.jar.flatMap { it.archiveFile }
    val runtimeJars = configurations.runtimeClasspath.get()
    val out = apkWork.map { it.dir("dex") }
    inputs.file(appJar)
    inputs.files(runtimeJars)
    inputs.file(d8Jar)
    outputs.dir(out)
    classpath = files(d8Jar)
    mainClass.set("com.android.tools.r8.D8")
    maxHeapSize = "2g"
    doFirst {
        val dir = out.get().asFile
        dir.deleteRecursively()
        dir.mkdirs()
        args(
            listOf("--release", "--min-api", minSdk.toString(), "--lib", androidJar.absolutePath, "--output", dir.absolutePath) +
                listOf(appJar.get().asFile.absolutePath) + runtimeJars.map { it.absolutePath },
        )
    }
}

/** Java resources from dependency jars that must ship in the APK (ServiceLoader files, OkHttp's public suffix list...). */
fun isPackagedJavaResource(name: String): Boolean {
    if (name.endsWith("/") || name.endsWith(".class")) return false
    val upper = name.uppercase()
    if (upper == "META-INF/MANIFEST.MF" || upper.startsWith("META-INF/VERSIONS/")) return false
    if (upper.startsWith("META-INF/") && (upper.endsWith(".SF") || upper.endsWith(".RSA") || upper.endsWith(".DSA") || upper.endsWith(".EC"))) return false
    if (upper.startsWith("META-INF/PROGUARD/") || upper.startsWith("META-INF/COM.ANDROID.TOOLS/")) return false
    if (name.endsWith(".kotlin_module") || name.endsWith("module-info.class")) return false
    if (name == "DebugProbesKt.bin") return false // coroutines debug agent, unused on Android
    return true
}

val packageApk by tasks.registering {
    dependsOn(linkResources, dex)
    val resApk = apkWork.map { it.file("resources.apk") }
    val dexDir = apkWork.map { it.dir("dex") }
    val runtimeJars = configurations.runtimeClasspath.get()
    val out = apkWork.map { it.file("app-unaligned.apk") }
    inputs.file(resApk)
    inputs.dir(dexDir)
    inputs.files(runtimeJars)
    outputs.file(out)
    doLast {
        val seen = HashSet<String>()
        ZipOutputStream(out.get().asFile.outputStream().buffered()).use { zip ->
            // Keep aapt2's entries byte-for-byte, including which ones are stored uncompressed
            // (resources.arsc must stay uncompressed for targetSdk >= 30).
            ZipFile(resApk.get().asFile).use { res ->
                for (entry in res.entries()) {
                    val copy = ZipEntry(entry.name)
                    copy.method = entry.method
                    if (entry.method == ZipEntry.STORED) {
                        copy.size = entry.size
                        copy.compressedSize = entry.size
                        copy.crc = entry.crc
                    }
                    zip.putNextEntry(copy)
                    res.getInputStream(entry).use { it.copyTo(zip) }
                    zip.closeEntry()
                    seen += entry.name
                }
            }
            dexDir.get().asFile.listFiles { f -> f.name.endsWith(".dex") }!!.sortedBy { it.name }.forEach { dexFile ->
                zip.putNextEntry(ZipEntry(dexFile.name))
                dexFile.inputStream().use { it.copyTo(zip) }
                zip.closeEntry()
                seen += dexFile.name
            }
            for (jar in runtimeJars) {
                ZipFile(jar).use { zf ->
                    for (entry in zf.entries()) {
                        if (!isPackagedJavaResource(entry.name) || !seen.add(entry.name)) continue
                        zip.putNextEntry(ZipEntry(entry.name))
                        zf.getInputStream(entry).use { it.copyTo(zip) }
                        zip.closeEntry()
                    }
                }
            }
        }
    }
}

val alignApk by tasks.registering(Exec::class) {
    dependsOn(packageApk)
    val input = apkWork.map { it.file("app-unaligned.apk") }
    val out = apkWork.map { it.file("app-aligned.apk") }
    inputs.file(input)
    outputs.file(out)
    commandLine(buildTool("zipalign"), "-f", "-p", "4", input.get().asFile.absolutePath, out.get().asFile.absolutePath)
}

// The signing key is not part of the repository. A build creates one on first use; keep the file
// to sign later builds as updates of an installed APK (a different key means uninstall first).
val keystoreFile = optionalProperty("keystoreFile")?.let(::File) ?: rootProject.file("keystore/android-plot.jks")
val keystorePassword = optionalProperty("keystorePassword") ?: "android-plot"
val keyAlias = optionalProperty("keyAlias") ?: "android-plot"

val generateKeystore by tasks.registering(Exec::class) {
    onlyIf { !keystoreFile.exists() }
    outputs.file(keystoreFile)
    doFirst { keystoreFile.parentFile.mkdirs() }
    commandLine(
        File(System.getProperty("java.home"), "bin/keytool").absolutePath, "-genkeypair",
        "-keystore", keystoreFile.absolutePath, "-storetype", "PKCS12",
        "-alias", keyAlias, "-keyalg", "RSA", "-keysize", "2048", "-validity", "36500",
        "-storepass", keystorePassword, "-keypass", keystorePassword,
        "-dname", "CN=android-plot",
    )
}

val signApk by tasks.registering(Exec::class) {
    dependsOn(alignApk, generateKeystore)
    val input = apkWork.map { it.file("app-aligned.apk") }
    val out = layout.buildDirectory.file("outputs/android-plot-$appVersionName.apk")
    inputs.file(input)
    inputs.file(keystoreFile)
    outputs.file(out)
    doFirst { out.get().asFile.parentFile.mkdirs() }
    commandLine(
        buildTool("apksigner"), "sign",
        "--ks", keystoreFile.absolutePath,
        "--ks-pass", "pass:$keystorePassword",
        "--ks-key-alias", keyAlias,
        "--key-pass", "pass:$keystorePassword",
        "--min-sdk-version", minSdk.toString(),
        "--out", out.get().asFile.absolutePath,
        input.get().asFile.absolutePath,
    )
}

tasks.register<Copy>("assembleApk") {
    group = "build"
    description = "Builds the signed APK and copies it to dist/."
    dependsOn(signApk)
    from(layout.buildDirectory.file("outputs/android-plot-$appVersionName.apk"))
    into(rootProject.file("dist"))
    rename { "android-plot.apk" }
}
