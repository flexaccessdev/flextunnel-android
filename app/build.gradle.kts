import java.io.FileOutputStream
import java.net.URI
import java.security.MessageDigest
import java.util.zip.ZipInputStream

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

// ---------------------------------------------------------------------------
// libflextunnel.so delivery.
//
// Default: download the pinned flextunnel release zip (libflextunnel-android.zip, the
// jniLibs/<abi>/libflextunnel.so tree built by ../flextunnel/build-android.sh) by
// URL + sha256 into build/, mirroring how flextunnel-ios pins its xcframework.
// Local FFI dev: FLEXTUNNEL_LOCAL_JNILIBS=1 (exactly) points the jniLibs source set
// at ../flextunnel/dist/android/jniLibs instead. Any other value selects the release.
val coreLocalJniLibs = System.getenv("FLEXTUNNEL_LOCAL_JNILIBS") == "1"
val coreReleaseTag = providers.gradleProperty("flextunnel.releaseTag").get()
val coreReleaseSha256 = providers.gradleProperty("flextunnel.releaseSha256").getOrElse("")
val coreReleaseUrl =
    "https://github.com/flexaccessdev/flextunnel/releases/download/$coreReleaseTag/libflextunnel-android.zip"
val coreLocalDir = rootProject.file("../flextunnel/dist/android/jniLibs")
val coreFetchedDir = layout.buildDirectory.dir("flextunnel-jnilibs")
val coreJniLibsDir: File =
    if (coreLocalJniLibs) coreLocalDir else coreFetchedDir.get().asFile.resolve("jniLibs")

val fetchFlextunnelJniLibs by tasks.registering {
    description = "Downloads the pinned libflextunnel-android.zip release and verifies its sha256."
    val url = coreReleaseUrl
    val sha256 = coreReleaseSha256
    val outDir = coreFetchedDir
    val skip = coreLocalJniLibs
    inputs.property("url", url)
    inputs.property("sha256", sha256)
    outputs.dir(outDir)
    onlyIf { !skip }
    doLast {
        require(sha256.matches(Regex("[0-9a-f]{64}"))) {
            "flextunnel.releaseSha256 is not set in gradle.properties: run " +
                "scripts/bump-jnilibs.sh <tag> to pin a published flextunnel release, " +
                "or build ../flextunnel with ./build-android.sh and set FLEXTUNNEL_LOCAL_JNILIBS=1."
        }
        // No shortcut on a stamp: Gradle reruns this only when the inputs or the
        // outputs changed, and a changed output (a deleted .so) must be restored.
        val dir = outDir.get().asFile
        val stamp = dir.resolve("sha256.txt")
        dir.deleteRecursively()
        dir.mkdirs()
        logger.lifecycle("Downloading $url")
        val bytes = URI(url).toURL().openConnection().run {
            connectTimeout = 30_000
            readTimeout = 120_000
            getInputStream().use { it.readBytes() }
        }
        val actual = MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it) }
        require(actual == sha256) {
            "sha256 mismatch for $url: expected $sha256, got $actual"
        }
        ZipInputStream(bytes.inputStream()).use { zip ->
            generateSequence { zip.nextEntry }.forEach { entry ->
                val target = dir.resolve(entry.name).canonicalFile
                require(target.path.startsWith(dir.canonicalPath)) { "zip entry escapes dir: ${entry.name}" }
                if (entry.isDirectory) {
                    target.mkdirs()
                } else {
                    target.parentFile.mkdirs()
                    FileOutputStream(target).use { zip.copyTo(it) }
                }
            }
        }
        require(dir.resolve("jniLibs").isDirectory) { "zip did not contain a jniLibs/ tree" }
        stamp.writeText(sha256)
    }
}

android {
    namespace = "dev.flexaccess.flextunnel"
    compileSdk = 37

    defaultConfig {
        // The JNI symbols in libflextunnel.so (../flextunnel's
        // crates/flextunnel-ffi/src/android.rs) are bound to the class
        // dev.flexaccess.flextunnel.FlextunnelNative; the applicationId can
        // change, the package of that class cannot.
        applicationId = "dev.flexaccess.flextunnel"
        minSdk = 34
        targetSdk = 37
        // The app's own version (gradle.properties), independent of the core pin.
        versionCode = providers.gradleProperty("flextunnel.versionCode").get().toInt()
        versionName = providers.gradleProperty("flextunnel.versionName").get()
        // The pinned libflextunnel release, shown next to the app version in the UI.
        // A local FFI build (FLEXTUNNEL_LOCAL_JNILIBS) still reports the pinned
        // number: the local .so carries none.
        buildConfigField("String", "FLEXTUNNEL_CORE_VERSION", "\"${coreReleaseTag.removePrefix("v")}\"")

        // 64-bit only (Google Play's 64-bit requirement; 32-bit devices are
        // not supported): arm64-v8a for phones plus x86_64 for VMs/emulators.
        // Only these ABIs' libflextunnel.so from the core zip are packaged, so the
        // APK refuses to install anywhere else.
        ndk { abiFilters.addAll(listOf("arm64-v8a", "x86_64")) }

        // The end-to-end test (src/androidTest) against a real server; see
        // docs/e2e.md for the arguments it takes.
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    // Release signing comes from the environment (scripts/build-release-apk.sh
    // sets it up): FLEXTUNNEL_KEYSTORE (path), FLEXTUNNEL_KEYSTORE_PASSWORD, optional
    // FLEXTUNNEL_KEY_ALIAS (default "flextunnel") and FLEXTUNNEL_KEY_PASSWORD (defaults to the
    // keystore password). Without FLEXTUNNEL_KEYSTORE, assembleRelease produces an
    // unsigned APK (app-release-unsigned.apk) that no device will install.
    val releaseKeystore = System.getenv("FLEXTUNNEL_KEYSTORE")?.takeIf { it.isNotBlank() }
    if (releaseKeystore != null) {
        signingConfigs.create("release") {
            storeFile = file(releaseKeystore)
            storePassword = System.getenv("FLEXTUNNEL_KEYSTORE_PASSWORD")?.takeIf { it.isNotEmpty() }
                ?: error("FLEXTUNNEL_KEYSTORE is set but FLEXTUNNEL_KEYSTORE_PASSWORD is not")
            keyAlias = System.getenv("FLEXTUNNEL_KEY_ALIAS")?.takeIf { it.isNotBlank() } ?: "flextunnel"
            keyPassword = System.getenv("FLEXTUNNEL_KEY_PASSWORD")?.takeIf { it.isNotEmpty() } ?: storePassword
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.findByName("release")
        }
    }

    sourceSets["main"].jniLibs.srcDir(coreJniLibsDir)

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        // The .so files are built against the NDK with 16 KiB page alignment;
        // keep them uncompressed and aligned as the system expects.
        jniLibs.useLegacyPackaging = false
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

tasks.named("preBuild") {
    dependsOn(fetchFlextunnelJniLibs)
}

tasks.withType<Test>().configureEach {
    testLogging {
        events("passed", "failed", "skipped")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}

dependencies {
    implementation(project(":tunnelcore"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.process)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.kotlinx.coroutines.android)
    debugImplementation(libs.androidx.compose.ui.tooling)

    testImplementation(libs.junit)
    testImplementation(libs.org.json)

    androidTestImplementation(libs.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.ext.junit)
}
