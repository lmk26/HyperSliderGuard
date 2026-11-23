import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.util.Properties

plugins {
    id("com.android.application")
}

val localProps = Properties().apply {
    val propsFile = rootProject.file("local.properties")
    if (propsFile.exists()) {
        propsFile.inputStream().use { load(it) }
    }
}

android {
    namespace = "io.github.lmk26.hypersliderguard"
    compileSdk = 37

    defaultConfig {
        applicationId = "io.github.lmk26.hypersliderguard"
        minSdk = 31
        targetSdk = 37
        versionCode = 100
        versionName = "1.0.0"
    }

    val storeFileName = localProps.getProperty("signing.storeFile")
    val storePass = localProps.getProperty("signing.storePassword")
    val aliasName = localProps.getProperty("signing.keyAlias")
    val keyPass = localProps.getProperty("signing.keyPassword")
    val keystoreFile = if (!storeFileName.isNullOrBlank()) file(storeFileName) else null

    val hasSigningConfig = keystoreFile != null && keystoreFile.exists() &&
        !storePass.isNullOrBlank() && !aliasName.isNullOrBlank() && !keyPass.isNullOrBlank()

    if (hasSigningConfig) {
        signingConfigs {
            create("appSign") {
                storeFile = keystoreFile
                storePassword = storePass
                keyAlias = aliasName
                keyPassword = keyPass
            }
        }
    }

    buildTypes {
        getByName("debug") {
            if (hasSigningConfig) {
                signingConfig = signingConfigs.getByName("appSign")
            }
        }
        getByName("release") {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            if (hasSigningConfig) {
                signingConfig = signingConfigs.getByName("appSign")
            }
        }
    }

    buildFeatures {
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    compileOnly("io.github.libxposed:api:102.0.0")
    implementation("io.github.libxposed:service:102.0.0")
    compileOnly("de.robv.android.xposed:api:82")
}