@file:Suppress("UnstableApiUsage")

import com.android.build.gradle.tasks.PackageAndroidArtifact
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.util.Properties
import java.util.zip.ZipInputStream
import java.io.File
import java.io.FileInputStream

plugins {
    alias(libs.plugins.agp.app)
    alias(libs.plugins.kotlin)
    alias(libs.plugins.kotlin.compose.compiler)
    alias(libs.plugins.ksp)
    alias(libs.plugins.lsplugin.apksign)
    alias(libs.plugins.lsplugin.resopt)
    alias(libs.plugins.lsplugin.cmaker)
    id("kotlin-parcelize")
}

val androidCompileSdkVersion: Int by rootProject.extra
val androidCompileNdkVersion: String by rootProject.extra
val androidBuildToolsVersion: String by rootProject.extra
val androidMinSdkVersion: Int by rootProject.extra
val androidTargetSdkVersion: Int by rootProject.extra
val androidSourceCompatibility: JavaVersion by rootProject.extra
val androidTargetCompatibility: JavaVersion by rootProject.extra
val managerVersionCode: Int by rootProject.extra
val managerVersionName: String by rootProject.extra
val branchName: String by rootProject.extra
val kernelPatchVersion: String by rootProject.extra
val kernelPatchBranch: String by rootProject.extra
val kernelPatchRepo: String by rootProject.extra

// Load keystore properties
val keystoreProperties = Properties()
val keystorePropertiesFile = rootProject.file("keystore.properties")
if (keystorePropertiesFile.exists()) {
    keystoreProperties.load(FileInputStream(keystorePropertiesFile))
}

// Load local properties
val localProperties = Properties()
val localPropertiesFile = rootProject.file("local.properties")
if (localPropertiesFile.exists()) {
    localProperties.load(FileInputStream(localPropertiesFile))
}

apksign {
    storeFileProperty = "KEYSTORE_FILE"
    storePasswordProperty = "KEYSTORE_PASSWORD"
    keyAliasProperty = "KEY_ALIAS"
    keyPasswordProperty = "KEY_PASSWORD"
}

val ccache = System.getenv("PATH")?.split(File.pathSeparator)
    ?.map { File(it, "ccache") }?.firstOrNull { it.exists() }?.absolutePath

val baseFlags = listOf(
    "-Wall", "-Qunused-arguments", "-fno-rtti", "-fvisibility=hidden",
    "-fvisibility-inlines-hidden", "-fno-exceptions", "-fno-stack-protector",
    "-fomit-frame-pointer", "-Wno-builtin-macro-redefined", "-Wno-unused-value",
    "-D__FILE__=__FILE_NAME__",
    "-DANDROID_SUPPORT_FLEXIBLE_PAGE_SIZES=ON", "-Wno-unused", "-Wno-unused-parameter",
    "-Wno-unused-command-line-argument", "-Wno-incompatible-function-pointer-types",
    "-U_FORTIFY_SOURCE", "-D_FORTIFY_SOURCE=0"
)

val baseArgs = mutableListOf(
    "-DANDROID_STL=none", "-DANDROID_SUPPORT_FLEXIBLE_PAGE_SIZES=ON",
    "-DCMAKE_CXX_STANDARD=23", "-DCMAKE_C_STANDARD=23",
    "-DCMAKE_INTERPROCEDURAL_OPTIMIZATION=ON", "-DCMAKE_VISIBILITY_INLINES_HIDDEN=ON",
    "-DCMAKE_CXX_VISIBILITY_PRESET=hidden", "-DCMAKE_C_VISIBILITY_PRESET=hidden"
).apply { if (ccache != null) add("-DANDROID_CCACHE=$ccache") }

android {
    namespace = "me.bmax.apatch"
    signingConfigs {
        create("release") {
            storeFile = file(keystoreProperties.getProperty("KEYSTORE_FILE") ?: "debug.keystore")
            storePassword = keystoreProperties.getProperty("KEYSTORE_PASSWORD") ?: "android"
            keyAlias = keystoreProperties.getProperty("KEY_ALIAS") ?: "androiddebugkey"
            keyPassword = keystoreProperties.getProperty("KEY_PASSWORD") ?: "android"
            enableV1Signing = true
            enableV2Signing = true
            enableV3Signing = true
            enableV4Signing = true
        }
    }

    buildTypes {
        debug {
            isDebuggable = true
            isMinifyEnabled = false
            isShrinkResources = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            externalNativeBuild {
                cmake {
                    arguments += listOf("-DCMAKE_CXX_FLAGS_DEBUG=-Og", "-DCMAKE_C_FLAGS_DEBUG=-Og")
                }
            }
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            isDebuggable = false
            multiDexEnabled = true
            vcsInfo.include = false
            if (keystorePropertiesFile.exists()) {
                signingConfig = signingConfigs.getByName("release")
            }
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            externalNativeBuild {
                cmake {
                    val relFlags = listOf(
                        "-flto", "-ffunction-sections", "-fdata-sections", "-Wl,--gc-sections",
                        "-fno-unwind-tables", "-fno-asynchronous-unwind-tables", "-Wl,--exclude-libs,ALL",
                        "-Ofast", "-fmerge-all-constants", "-flto=full", "-ffat-lto-objects",
                        "-fno-semantic-interposition", "-fno-threadsafe-statics"
                    )
                    cppFlags += relFlags
                    cFlags += relFlags
                    arguments += listOf("-DCMAKE_BUILD_TYPE=Release", "-DCMAKE_CXX_FLAGS_RELEASE=-O3 -DNDEBUG", "-DCMAKE_C_FLAGS_RELEASE=-O3 -DNDEBUG")
                }
            }
        }
    }

    dependenciesInfo.includeInApk = false

    buildFeatures {
        aidl = true
        buildConfig = true
        compose = true
        prefab = true
    }

    defaultConfig {
        applicationId = "me.yuki.folk"
        minSdk = androidMinSdkVersion
        targetSdk = androidTargetSdkVersion
        versionCode = managerVersionCode
        versionName = managerVersionName
        buildConfigField("String", "buildKPV", "\"$kernelPatchVersion\"")
        buildConfigField("boolean", "DEBUG_FAKE_ROOT", localProperties.getProperty("debug.fake_root", "false"))

        base.archivesName = "FolkPatch_${managerVersionCode}_${managerVersionName}_on_${branchName}"

        ndk.abiFilters.addAll(arrayOf("arm64-v8a"))
        externalNativeBuild {
            cmake {
                cppFlags += baseFlags + "-std=c++2b"
                cFlags += baseFlags + "-std=c2x"
                arguments += baseArgs
                
                // Pass Token and Signature Hash to CMake
                val authProps = Properties()
                val authFile = rootProject.file("auth.properties")
                if (authFile.exists()) {
                    authProps.load(FileInputStream(authFile))
                }
                val token = authProps.getProperty("api.token", "")
                val signatureHash = authProps.getProperty("app.signature.hash", "")

                // Pass to C++ compiler directly via flags
                // Only add flags if values are non-empty to avoid compiler errors
                if (token.isNotEmpty()) {
                    cppFlags += "-DAPI_TOKEN=\"$token\""
                }
                if (signatureHash.isNotEmpty()) {
                    cppFlags += "-DAPP_SIGNATURE_HASH=\"$signatureHash\""
                }
                cppFlags += "-DAPP_PACKAGE_NAME=\"$applicationId\""
                
                abiFilters("arm64-v8a")
            }
        }
        
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
        resources {
            excludes += "**"
            merges += "META-INF/com/google/android/**"
        }
    }

    externalNativeBuild {
        cmake {
            version = "3.28.0+"
            path("src/main/cpp/CMakeLists.txt")
        }
    }

    androidResources {
        generateLocaleConfig = true
    }

    compileSdk = androidCompileSdkVersion
    ndkVersion = androidCompileNdkVersion
    buildToolsVersion = androidBuildToolsVersion

    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }

    android.sourceSets.named("main") {
        kotlin.directories += "build/generated/ksp/$name/kotlin"
        jniLibs.directories += "libs"
    }
}

// https://stackoverflow.com/a/77745844
tasks.withType<PackageAndroidArtifact> {
    doFirst { appMetadata.asFile.orNull?.writeText("") }
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

kotlin {
    jvmToolchain(21)
    compilerOptions {
        jvmTarget = JvmTarget.JVM_21
    }
}

// ── Release download helpers ──

fun registerReleaseDownloadTask(
    taskName: String, srcUrl: String, destPath: String, project: Project, version: String? = null
) {
    project.tasks.register(taskName) {
        val destFile = File(destPath)
        val versionFile = File("$destPath.version")

        doLast {
            var forceDownload = false
            if (version != null) {
                if (!versionFile.exists() || versionFile.readText().trim() != version) {
                    forceDownload = true
                }
            }

            if (!destFile.exists() || forceDownload || isFileUpdated(srcUrl, destFile)) {
                println(" - Downloading $srcUrl to ${destFile.absolutePath}")
                downloadFile(srcUrl, destFile)
                if (version != null) {
                    versionFile.writeText(version)
                }
                println(" - Download completed.")
            } else {
                println(" - File is up-to-date, skipping download.")
            }
        }
    }
}

fun isFileUpdated(url: String, localFile: File): Boolean {
    val connection = URI.create(url).toURL().openConnection()
    val remoteLastModified = connection.getHeaderFieldDate("Last-Modified", 0L)
    return remoteLastModified > localFile.lastModified()
}

fun downloadFile(url: String, destFile: File) {
    URI.create(url).toURL().openStream().use { input ->
        destFile.outputStream().use { output ->
            input.copyTo(output)
        }
    }
}

// ── GitHub Actions artifact download helpers ──

fun getGitHubToken(): String {
    val envToken = System.getenv("KP_GH_TOKEN") ?: System.getenv("GITHUB_TOKEN")
    if (!envToken.isNullOrBlank()) return envToken
    if (project.hasProperty("kpGhToken")) {
        val localPropsToken = project.property("kpGhToken") as? String
        if (!localPropsToken.isNullOrBlank()) return localPropsToken
    }
    throw GradleException(
        "GitHub token required for artifact download. " +
        "Set KP_GH_TOKEN or GITHUB_TOKEN env var, or kpGhToken in local.properties."
    )
}

fun httpGet(url: String, token: String): String {
    val conn = URL(url).openConnection() as HttpURLConnection
    conn.requestMethod = "GET"
    conn.setRequestProperty("Authorization", "Bearer $token")
    conn.setRequestProperty("Accept", "application/vnd.github.v3+json")
    conn.connectTimeout = 15000
    conn.readTimeout = 15000
    return conn.inputStream.reader().readText()
}

fun extractJsonValue(json: String, key: String, afterPos: Int = 0): String? {
    val searchKey = "\"$key\":"
    val start = json.indexOf(searchKey, afterPos)
    if (start < 0) return null
    val valueStart = start + searchKey.length
    var end = valueStart
    while (end < json.length && json[end] != ',' && json[end] != '}' && json[end] != ']') end++
    return json.substring(valueStart, end).trim()
}

fun findArtifactId(json: String, artifactName: String): String? {
    val artifactsKey = "\"artifacts\":"
    val artsStart = json.indexOf(artifactsKey)
    if (artsStart < 0) return null
    var pos = artsStart
    while (true) {
        val nameKey = "\"name\":\"$artifactName\""
        val namePos = json.indexOf(nameKey, pos)
        if (namePos < 0) return null
        val idKey = "\"id\":"
        val idPos = json.lastIndexOf(idKey, namePos)
        if (idPos < artsStart) { pos = namePos + 1; continue }
        val idEnd = json.indexOf(",", idPos + idKey.length)
        if (idEnd < 0) return null
        return json.substring(idPos + idKey.length, idEnd).trim()
    }
}

fun downloadArtifactZip(artifactId: String, token: String, destZip: File) {
    val url = "https://api.github.com/repos/$kernelPatchRepo/actions/artifacts/$artifactId/zip"
    println(" - Downloading artifact $artifactId from $kernelPatchRepo (next branch) ...")
    val conn = URL(url).openConnection() as HttpURLConnection
    conn.requestMethod = "GET"
    conn.setRequestProperty("Authorization", "Bearer $token")
    conn.setRequestProperty("Accept", "application/vnd.github.v3+json")
    conn.instanceFollowRedirects = true
    destZip.outputStream().use { out -> conn.inputStream.use { it.copyTo(out) } }
}

fun extractFromZip(zipFile: File, entryName: String, destFile: File) {
    ZipInputStream(zipFile.inputStream()).use { zip ->
        var entry = zip.nextEntry
        while (entry != null) {
            if (entry.name.removePrefix("./") == entryName || entry.name == entryName) {
                destFile.outputStream().use { out -> zip.copyTo(out) }
                println(" - Extracted ${entry.name} -> ${destFile.absolutePath}")
                return
            }
            entry = zip.nextEntry
        }
    }
    throw GradleException("Entry '$entryName' not found in artifact zip: $zipFile")
}

fun registerArtifactDownloadTask(
    taskName: String, artifactName: String, extractPath: String,
    destPath: String, project: Project
) {
    project.tasks.register(taskName) {
        val destFile = File(destPath)
        val versionFile = File("$destPath.version")
        val tempZip = File("${destFile.parentFile?.absolutePath ?: "."}/.${taskName}_tmp.zip")

        doLast {
            val token = getGitHubToken()

            // Find latest successful run on next branch
            val runUrl = "https://api.github.com/repos/$kernelPatchRepo/actions/runs" +
                "?branch=$kernelPatchBranch&status=success&event=push&per_page=1"
            val runJson = httpGet(runUrl, token)
            val runId = extractJsonValue(runJson, "id")
                ?: throw GradleException("No successful run found on branch '$kernelPatchBranch'")

            // Get artifact ID
            val artUrl = "https://api.github.com/repos/$kernelPatchRepo/actions/runs/$runId/artifacts"
            val artJson = httpGet(artUrl, token)
            val artifactId = findArtifactId(artJson, artifactName)
                ?: throw GradleException("Artifact '$artifactName' not found in run $runId")

            // Check version
            val versionTag = "$runId-$artifactId"
            if (versionFile.exists() && versionFile.readText().trim() == versionTag && destFile.exists()) {
                println(" - $taskName: up-to-date (run $runId).")
                return@doLast
            }

            // Download and extract
            downloadArtifactZip(artifactId, token, tempZip)
            extractFromZip(tempZip, extractPath, destFile)
            tempZip.delete()

            versionFile.writeText(versionTag)
            println(" - $taskName completed (run $runId).")
        }

        doLast {
            tempZip.delete()
        }
    }
}

// ── Download tasks ──

registerArtifactDownloadTask(
    taskName = "downloadKpimg",
    artifactName = "kpimg",
    extractPath = "kpimg-android",
    destPath = "${project.projectDir}/src/main/assets/kpimg",
    project = project
)

registerArtifactDownloadTask(
    taskName = "downloadKptools",
    artifactName = "kptools-android",
    extractPath = "kptools-android",
    destPath = "${project.projectDir}/libs/arm64-v8a/libkptools.so",
    project = project
)

// Compat kp version less than 0.10.7
registerReleaseDownloadTask(
    taskName = "downloadCompatKpatch",
    srcUrl = "https://github.com/bmax121/KernelPatch/releases/download/0.10.7/kpatch-android",
    destPath = "${project.projectDir}/libs/arm64-v8a/libkpatch.so",
    project = project,
    version = "0.10.7"
)

tasks.register<Copy>("mergeScripts") {
    into("${project.projectDir}/src/main/resources/META-INF/com/google/android")
    from(rootProject.file("${project.rootDir}/scripts/update_binary.sh")) {
        rename { "update-binary" }
    }
    from(rootProject.file("${project.rootDir}/scripts/update_script.sh")) {
        rename { "updater-script" }
    }
}

// Build fpd (FolkPatch service binary) for arm64
tasks.register<Exec>("buildFpd") {
    executable("cargo")
    args("ndk", "-t", "arm64-v8a", "build", "--release")
    workingDir("${project.rootDir}/fpd")
    doFirst {
        println("Building fpd for arm64...")
    }
    doLast {
        val fpdBinary = file("${project.rootDir}/fpd/target/aarch64-linux-android/release/fpd")
        val serviceDir = file("src/main/assets/Service")
        serviceDir.mkdirs()
        fpdBinary.copyTo(file("${serviceDir}/fpd"), overwrite = true)
        println("fpd binary built and copied to Service/fpd")
    }
}

tasks.getByName("preBuild").dependsOn(
    "downloadKpimg",
    "downloadKptools",
    "downloadCompatKpatch",
    "mergeScripts",
    "buildFpd",
)

// https://github.com/bbqsrc/cargo-ndk
// cargo ndk -t arm64-v8a build --release
tasks.register<Exec>("cargoBuild") {
    executable("cargo")
    args("ndk", "-t", "arm64-v8a", "build", "--release")
    workingDir("${project.rootDir}/apd")
    environment("APATCH_VERSION_CODE", "${managerVersionCode}")
    environment("APATCH_VERSION_NAME", "${managerVersionCode}-Matsuzaka-yuki")
}

tasks.register<Copy>("buildApd") {
    dependsOn("cargoBuild")
    from("${project.rootDir}/apd/target/aarch64-linux-android/release/apd")
    into("${project.projectDir}/libs/arm64-v8a")
    rename("apd", "libapd.so")
}

tasks.configureEach {
    if (name == "mergeDebugJniLibFolders" || name == "mergeReleaseJniLibFolders") {
        dependsOn("buildApd")
    }
}

tasks.register<Exec>("cargoClean") {
    executable("cargo")
    args("clean")
    workingDir("${project.rootDir}/apd")
}

tasks.register<Delete>("apdClean") {
    dependsOn("cargoClean")
    delete(file("${project.projectDir}/libs/arm64-v8a/libapd.so"))
}

tasks.clean {
    dependsOn("apdClean")
}

ksp {
    arg("compose-destinations.defaultTransitions", "none")
}

dependencies {
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.core.splashscreen)
    implementation(libs.androidx.webkit)
    implementation(libs.androidx.biometric)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.compose.material)
    implementation(libs.androidx.compose.material3)

    implementation("androidx.compose.material3:material3-android:1.5.0-alpha17")
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.runtime.livedata)

    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)

    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation("androidx.lifecycle:lifecycle-process:2.8.7")

    implementation(libs.compose.destinations.core)
    ksp(libs.compose.destinations.ksp)

    implementation(libs.com.github.topjohnwu.libsu.core)
    implementation(libs.com.github.topjohnwu.libsu.service)
    implementation(libs.com.github.topjohnwu.libsu.nio)
    implementation(libs.com.github.topjohnwu.libsu.io)

    implementation(libs.dev.rikka.rikkax.parcelablelist)

    implementation(libs.io.coil.kt.coil.compose)
    implementation(libs.io.coil.kt.coil.gif)

    implementation(libs.kotlinx.coroutines.core)

    implementation(libs.me.zhanghai.android.appiconloader.coil)

    implementation(libs.sheet.compose.dialogs.core)
    implementation(libs.sheet.compose.dialogs.list)
    implementation(libs.sheet.compose.dialogs.input)

    implementation(libs.markdown)

    implementation(libs.ini4j)

    implementation(libs.google.code.gson)

    implementation(libs.liquid)

    compileOnly(libs.cxx)
}
