plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "fr.tvluncher.launcher"
    compileSdk = 35

    defaultConfig {
        applicationId = "fr.tvluncher.launcher"
        // Android 8.0. The Free Player TV ships Android TV 9 (Pop) / 11 (Ultra).
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.0.0"
        resourceConfigurations += setOf("fr", "en")
    }

    signingConfigs {
        // Sideloaded builds are signed with a stable local key when one is provided,
        // so upgrades install over each other instead of failing with a signature clash.
        create("sideload") {
            val store = (project.findProperty("SIDELOAD_STORE_FILE") as String?)
                ?: System.getenv("SIDELOAD_STORE_FILE")
            if (store != null) {
                storeFile = file(store)
                storePassword = (project.findProperty("SIDELOAD_STORE_PASSWORD") as String?)
                    ?: System.getenv("SIDELOAD_STORE_PASSWORD")
                keyAlias = (project.findProperty("SIDELOAD_KEY_ALIAS") as String?)
                    ?: System.getenv("SIDELOAD_KEY_ALIAS")
                keyPassword = (project.findProperty("SIDELOAD_KEY_PASSWORD") as String?)
                    ?: System.getenv("SIDELOAD_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = if (signingConfigs.getByName("sideload").storeFile != null) {
                signingConfigs.getByName("sideload")
            } else {
                signingConfigs.getByName("debug")
            }
        }
        debug {
            applicationIdSuffix = ".debug"
            isMinifyEnabled = false
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
        viewBinding = true
        buildConfig = true
    }

    packaging {
        resources.excludes += setOf(
            "DebugProbesKt.bin",
            "kotlin/**",
            "META-INF/*.version",
            "META-INF/**.kotlin_module",
        )
    }

    sourceSets["main"].java.srcDirs("src/main/kotlin")

    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.recyclerview)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.kotlinx.coroutines.android)
}
