plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val keystorePath: String? = System.getenv("ANDROID_KEYSTORE_PATH")
val keystorePassword: String? = System.getenv("ANDROID_KEYSTORE_PASSWORD")
val signingKeyAlias: String? = System.getenv("ANDROID_KEY_ALIAS")
val signingKeyPassword: String? = System.getenv("ANDROID_KEY_PASSWORD")

android {
    namespace = "ir.mamoot.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "ir.mamoot.app"
        minSdk = 26
        targetSdk = 35
        versionCode = 5
        versionName = "0.3.1"

        // Supabase publishable key: public by design (all access is enforced by RLS).
        buildConfigField("String", "SUPABASE_URL", "\"https://eslatwgowmovfkkddcnp.supabase.co\"")
        buildConfigField("String", "SUPABASE_ANON_KEY", "\"sb_publishable_7BdtP9a7dgRcpD6jM8uHQQ_Tu_5zYsw\"")
    }

    signingConfigs {
        if (keystorePath != null && keystorePassword != null && signingKeyAlias != null && signingKeyPassword != null) {
            create("releaseEnv") {
                storeFile = file(keystorePath)
                storePassword = keystorePassword
                keyAlias = signingKeyAlias
                keyPassword = signingKeyPassword
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            if (keystorePath != null) {
                signingConfig = signingConfigs.getByName("releaseEnv")
            }
        }
        debug {
            isMinifyEnabled = false
        }
    }

    buildFeatures {
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    packaging {
        resources.excludes += setOf("META-INF/AL2.0", "META-INF/LGPL2.1")
    }
}

dependencies {
    // Intentionally zero third-party runtime dependencies:
    // tiny APK, no Google Play Services (important for Iran), small attack surface.
    testImplementation("junit:junit:4.13.2")
}
