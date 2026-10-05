plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("com.chaquo.python")
}

// Update source, taken from gradle.properties so no secret lives in the source
// tree. `tgws.updateRepo` is "owner/repo"; `tgws.updateToken` is only needed for
// a private repository. Anything compiled in can be extracted from the APK, so
// such a token should be read-only and scoped to that repository alone.
val updateRepo = (project.findProperty("tgws.updateRepo") as String?).orEmpty()
val updateToken = (project.findProperty("tgws.updateToken") as String?).orEmpty()

android {
    // Fork identity: our own namespace and application id. The proxy core is
    // still upstream's code, credited in the app and in LICENSE.
    namespace = "com.nixikon.tgwsproxy"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.nixikon.tgwsproxy"
        minSdk = 24
        targetSdk = 36
        versionCode = 15
        versionName = "1.11.0-a9"

        buildConfigField("String", "UPDATE_REPO", "\"$updateRepo\"")
        buildConfigField("String", "UPDATE_TOKEN", "\"$updateToken\"")

        // Only these two ABIs ship with the Chaquopy runtime in this offline
        // dependency cache (arm64-v8a for devices, x86_64 for emulators).
        ndk {
            abiFilters += listOf("arm64-v8a", "x86_64")
        }
    }

    buildFeatures {
        buildConfig = true
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
        }
        release {
            isMinifyEnabled = false
            isShrinkResources = false
            // Signed with the debug key so the produced release APK is
            // directly installable (sideload) without extra keystore setup.
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

chaquopy {
    defaultConfig {
        version = "3.13"
        // Source compilation to .pyc is disabled on purpose: it needs a matching
        // Python on the build machine, and Chaquopy then compiles the sources on
        // the device instead. It only costs a little first-run time and keeps
        // real .py files (and therefore readable tracebacks) in the APK.
        pyc {
            src = false
        }
        // Upstream 1.11.0 added an HTTP/2 media path built on httpx + hyper-h2.
        // Both are pure Python, so Chaquopy installs them fine — but they are the
        // port's only non-stdlib dependency, and they make the first build need
        // network access (the pip repository).
        pip {
            install("httpx[http2]==0.28.1")
        }
    }
    // `src/main/python` is already the default source directory; no extra
    // srcDir is declared so the same path cannot be added twice.
}

dependencies {
    implementation("androidx.core:core-ktx:1.17.0")
    implementation("androidx.appcompat:appcompat:1.7.1")
    implementation("com.google.android.material:material:1.13.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.0")
    implementation("androidx.activity:activity-ktx:1.8.1")
}
