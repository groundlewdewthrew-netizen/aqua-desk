import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Signing: put these in local.properties (never commit it):
//   storeFile=C:/path/to/signing.keystore
//   storePassword=...   keyAlias=...   keyPassword=...
val props = Properties().apply {
    val f = rootProject.file("local.properties"); if (f.exists()) f.inputStream().use { load(it) }
}

android {
    namespace = "app.netlify.create_app_4a7921.twa"
    compileSdk = 34
    defaultConfig {
        // Same id as the existing Play listing, so this ships as an update.
        applicationId = "app.netlify.create_app_4a7921.twa"
        minSdk = 21
        targetSdk = 34
        versionCode = 2          // must be higher than the version already on Play
        versionName = "2.0.0"
    }
    signingConfigs {
        create("release") {
            if (props.getProperty("storeFile") != null) {
                storeFile = file(props.getProperty("storeFile"))
                storePassword = props.getProperty("storePassword")
                keyAlias = props.getProperty("keyAlias")
                keyPassword = props.getProperty("keyPassword")
            }
        }
    }
    buildTypes {
        release {
            isMinifyEnabled = false
            if (props.getProperty("storeFile") != null) signingConfig = signingConfigs.getByName("release")
        }
    }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
}
