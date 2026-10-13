import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

// The Play *upload* key. Play App Signing re-signs every release with the app
// signing key Google holds; that Play-signed APK is the one the ATAK plugin
// embeds. keystore.properties is gitignored -- see docs/play-release.md.
val uploadKeyProps = Properties().apply {
    val f = rootProject.file("keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

// versionCode is derived from versionName so that every release is an update
// an MDM (and Play) will accept: MAJOR*1_000_000 + MINOR*1_000 + PATCH.
val beaconVersion = "1.3.4"
val beaconVersionCode = beaconVersion.split(".").map { it.toInt() }
    .let { (maj, min, pat) -> maj * 1_000_000 + min * 1_000 + pat }

android {
    namespace = "com.taksolutions.takbeacon"
    compileSdk = 36

    defaultConfig {
        // Permanent once uploaded to Play. Confirm before the first upload.
        applicationId = "com.taksolutions.takbeacon"
        minSdk = 26
        targetSdk = 36
        versionCode = beaconVersionCode
        versionName = beaconVersion
    }

    signingConfigs {
        if (uploadKeyProps.isNotEmpty()) {
            create("upload") {
                storeFile = rootProject.file(uploadKeyProps.getProperty("storeFile"))
                storePassword = uploadKeyProps.getProperty("storePassword")
                keyAlias = uploadKeyProps.getProperty("keyAlias")
                keyPassword = uploadKeyProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfigs.findByName("upload")?.let { signingConfig = it }
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
    kotlin {
        compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) }
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    packaging {
        resources.excludes += setOf("META-INF/versions/9/OSGI-INF/MANIFEST.MF", "META-INF/DEPENDENCIES")
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.service)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons)
    implementation(libs.androidx.datastore)
    implementation(libs.androidx.media)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.camera.camera2)
    implementation(libs.camera.lifecycle)
    implementation(libs.camera.view)
    implementation(libs.zxing.core)
    implementation(libs.bouncycastle.pkix)
    implementation(libs.osmdroid)
    debugImplementation(libs.compose.ui.tooling)
    testImplementation(libs.junit)
    testImplementation(libs.orgjson)
}
