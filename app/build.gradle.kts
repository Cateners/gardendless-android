import java.io.FileInputStream
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

val keystoreProperties = Properties()
val keystorePropertiesFile = rootProject.file("keystore.properties")
if (keystorePropertiesFile.exists()) {
    keystoreProperties.load(FileInputStream(keystorePropertiesFile))
}

val versionCodeOverride = providers.gradleProperty("versionCodeOverride").orNull?.let { value ->
    val parsedValue = value.toIntOrNull()
        ?: error("versionCodeOverride must be a positive integer, but was: $value")
    require(parsedValue > 0) { "versionCodeOverride must be positive, but was: $value" }
    parsedValue
}
val versionNameOverride = providers.gradleProperty("versionNameOverride").orNull?.also { value ->
    require(value.isNotBlank()) { "versionNameOverride must not be blank" }
}

android {
    namespace = "com.fct.gardendless"
    compileSdk {
        version = release(36)
    }

    defaultConfig {
        applicationId = "com.fct.gardendless"
        minSdk = 27
        targetSdk = 36
        versionCode = versionCodeOverride ?: 8
        versionName = versionNameOverride ?: "0.10.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        create("release") {
            keyAlias = keystoreProperties["keyAlias"] as String?
            keyPassword = keystoreProperties["keyPassword"] as String?
            storeFile = keystoreProperties["storeFile"]?.let { file(it) }
            storePassword = keystoreProperties["storePassword"] as String?
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = signingConfigs.getByName("release")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    kotlinOptions {
        jvmTarget = "11"
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.webkit)
    implementation(libs.material)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
}
