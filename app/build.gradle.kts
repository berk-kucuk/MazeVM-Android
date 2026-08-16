import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

/**
 * Release signing credentials.
 *
 * Read from keystore.properties, which is deliberately not in version control, or from
 * the matching environment variables so a CI run can supply them without a file. When
 * neither is present the release build simply comes out unsigned instead of failing,
 * which keeps `assembleRelease` usable for checking that R8 has not broken anything.
 */
val keystoreProperties = Properties().apply {
    val file = rootProject.file("keystore.properties")
    if (file.exists()) file.inputStream().use { load(it) }
}

fun signingValue(key: String, environmentKey: String): String? =
    keystoreProperties.getProperty(key) ?: System.getenv(environmentKey)

val releaseStoreFile = signingValue("storeFile", "MAZEVM_STORE_FILE")
val releaseStorePassword = signingValue("storePassword", "MAZEVM_STORE_PASSWORD")
val releaseKeyAlias = signingValue("keyAlias", "MAZEVM_KEY_ALIAS")
val releaseKeyPassword = signingValue("keyPassword", "MAZEVM_KEY_PASSWORD")

val hasReleaseSigning = listOf(
    releaseStoreFile,
    releaseStorePassword,
    releaseKeyAlias,
    releaseKeyPassword,
).all { !it.isNullOrBlank() } && rootProject.file(releaseStoreFile!!).exists()

android {
    namespace = "com.mazevm.android"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.mazevm.android"
        // QEMU's bionic build needs APIs that only settled in Android 9, and the
        // sigaltstack coroutine backend it falls back to is unreliable below it.
        minSdk = 28
        targetSdk = 35
        versionCode = 9
        versionName = "1.5.0"

        // Only English and Turkish are shipped. No other locale is packaged.
        resourceConfigurations += setOf("en", "tr")

        ndk {
            // QEMU is only built for 64-bit ARM devices.
            abiFilters += "arm64-v8a"
        }
    }

    signingConfigs {
        if (hasReleaseSigning) {
            create("release") {
                storeFile = rootProject.file(releaseStoreFile!!)
                storePassword = releaseStorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
                // Both signature schemes: v2 is what modern Android verifies, v1 is
                // what some sideload installers and file managers still check.
                enableV1Signing = true
                enableV2Signing = true
                enableV3Signing = true
            }
        }
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
            applicationIdSuffix = ".debug"
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = if (hasReleaseSigning) signingConfigs.getByName("release") else null
            // A build people are asked to trust should say where it came from.
            ndk { debugSymbolLevel = "SYMBOL_TABLE" }
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        jniLibs {
            // QEMU binaries must live on disk as real files so they can be exec()'d.
            // Legacy packaging forces the installer to extract them into nativeLibraryDir.
            useLegacyPackaging = true
        }
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }

    testOptions {
        unitTests {
            // android.util.Log throws in local unit tests unless stubs return defaults.
            // Without this, exercising any code path that logs fails the test for a
            // reason that has nothing to do with what is being tested.
            isReturnDefaultValues = true
        }
    }

    androidResources {
        // QEMU firmware blobs under assets/qemu-data must not be compressed, they are
        // extracted verbatim at runtime.
        noCompress += listOf("rom", "bin", "fd", "img", "dtb")
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.service)
    implementation(libs.androidx.navigation.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    debugImplementation(libs.androidx.compose.ui.tooling)

    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)

    implementation(libs.tukaani.xz)
    implementation(libs.commons.compress)

    testImplementation(libs.junit)
}
