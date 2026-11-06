import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "com.xiziphus.monctl"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.xiziphus.monctl"
        minSdk = 26
        targetSdk = 35
        // Minutes since the epoch: every build outranks the last, which the
        // in-app updater needs (Android won't install an equal versionCode).
        val stamp = (System.currentTimeMillis() / 60_000).toInt()
        versionCode = stamp
        versionName = "0.2.$stamp"
    }

    // The release key lives outside the repo (~/.config/monctl/release.*).
    // Without it the release build is left unsigned rather than falling back
    // to the debug key, so a lost key can't ship an APK nobody can update.
    val relProps = Properties().apply {
        val f = file(System.getProperty("user.home") + "/.config/monctl/release.properties")
        if (f.exists()) f.inputStream().use { load(it) }
    }
    signingConfigs {
        if (relProps.getProperty("storeFile") != null) {
            create("release") {
                storeFile = file(relProps.getProperty("storeFile"))
                storePassword = relProps.getProperty("storePassword")
                keyAlias = relProps.getProperty("keyAlias")
                keyPassword = relProps.getProperty("keyPassword")
            }
        }
    }
    buildTypes {
        release {
            isMinifyEnabled = false
            isDebuggable = false
            signingConfigs.findByName("release")?.let { signingConfig = it }
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
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui.tooling.preview)
    debugImplementation(libs.androidx.compose.ui.tooling)
    implementation(libs.androidx.glance.appwidget)
    implementation(libs.androidx.glance.material3)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.okhttp)
    implementation(libs.kotlinx.serialization.json)
}
