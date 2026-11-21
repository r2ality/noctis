plugins {
    id("com.android.application")
}

android {
    namespace = "dev.noctis.browser"
    compileSdk = 36
    buildToolsVersion = "36.0.0"

    defaultConfig {
        applicationId = "dev.noctis.browser"
        minSdk = 26
        targetSdk = 36
        // Keep applicationId stable and increment versionCode for in-place updates.
        versionCode = 5
        versionName = "0.2.3"

        vectorDrawables.useSupportLibrary = false
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }


    packaging {
        resources {
            excludes += setOf("META-INF/LICENSE*", "META-INF/NOTICE*")
        }
    }
}
