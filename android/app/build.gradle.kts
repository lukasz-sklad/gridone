plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "one.lmk.gridcraft"
    compileSdk = 36

    defaultConfig {
        applicationId = "one.lmk.gridcraft"
        minSdk = 26
        targetSdk = 36
        versionCode = providers.gradleProperty("versionCode").orNull?.toIntOrNull()
            ?: System.getenv("ANDROID_VERSION_CODE")?.toIntOrNull()
            ?: 1901
        versionName = providers.gradleProperty("versionName").orNull
            ?: System.getenv("ANDROID_VERSION_NAME")
            ?: "0.19.1"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        val abiFiltersProp = providers.gradleProperty("abiFilters").orNull
        val abis = abiFiltersProp
            ?.split(",")
            ?.map { it.trim() }
            ?.filter { it.isNotEmpty() }
            ?: listOf("arm64-v8a", "x86_64")
        ndk {
            abiFilters += abis
        }
    }

    flavorDimensions += "distribution"
    productFlavors {
        create("foss") {
            dimension = "distribution"
            applicationId = "one.lmk.gridcraft"
            isDefault = true
        }
        create("play") {
            dimension = "distribution"
            applicationId = "one.lmk.gridone"
        }
    }

    val releaseKeystorePath = System.getenv("ANDROID_KEYSTORE_PATH")
        ?: providers.gradleProperty("androidKeystorePath").orNull
    val releaseKeystorePassword = System.getenv("ANDROID_KEYSTORE_PASSWORD")
        ?: providers.gradleProperty("androidKeystorePassword").orNull
    val releaseKeyAlias = System.getenv("ANDROID_KEY_ALIAS")
        ?: providers.gradleProperty("androidKeyAlias").orNull
    val releaseKeyPassword = System.getenv("ANDROID_KEY_PASSWORD")
        ?: providers.gradleProperty("androidKeyPassword").orNull
    val hasReleaseSigning = !releaseKeystorePath.isNullOrBlank() &&
        !releaseKeystorePassword.isNullOrBlank() &&
        !releaseKeyAlias.isNullOrBlank() &&
        !releaseKeyPassword.isNullOrBlank() &&
        file(releaseKeystorePath).isFile

    signingConfigs {
        if (hasReleaseSigning) {
            create("release") {
                storeFile = file(releaseKeystorePath)
                storePassword = releaseKeystorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
            }
        }
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
            packaging {
                jniLibs {
                    keepDebugSymbols += "**/*.so"
                }
            }
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            if (hasReleaseSigning) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    packaging {
        jniLibs {
            useLegacyPackaging = false
        }
    }

    testOptions {
        unitTests {
            isReturnDefaultValues = true
        }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.games.activity)

    "playImplementation"(libs.androidx.mediarouter)
    "playImplementation"(libs.play.services.cast.framework)

    testImplementation(libs.junit)
}
