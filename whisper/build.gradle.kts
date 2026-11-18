// The native speech engine (whisper.cpp, a git submodule pinned in gradle/whisper.pin), its JNI bridge
// and a small Kotlin API. The module depends on nothing of the project: neither :core nor :app.
plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "app.meetingrecorder.whisper"
    compileSdk = libs.versions.compile.sdk.get().toInt()
    ndkVersion = "29.0.14206865"

    defaultConfig {
        minSdk = libs.versions.min.sdk.get().toInt()
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // 32-bit ARM is not supported; x86_64 exists for the emulator (functional tests only).
        ndk {
            abiFilters += listOf("arm64-v8a", "x86_64")
        }

        externalNativeBuild {
            cmake {
                // The native code is always optimized, also for the debug app variant (research R-2).
                arguments += listOf("-DCMAKE_BUILD_TYPE=Release", "-DANDROID_STL=c++_static")
            }
        }
    }

    testOptions {
        // The engine loads its CPU backend libraries by scanning the native library directory, so the test package
        // must extract them like the app does (`useLegacyPackaging` in :app).
        packaging {
            jniLibs {
                useLegacyPackaging = true
            }
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.31.6"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    lint {
        lintConfig = file("lint.xml")
        warningsAsErrors = true
        abortOnError = true
    }
}

dependencies {
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.ext.junit)
}
