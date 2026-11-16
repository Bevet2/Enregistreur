import java.io.File

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.devtools.ksp")
}

// Version fournie par la CI (numéro de build GitHub Actions), sinon 1.
val ciVersionCode = (System.getenv("VERSION_CODE") ?: "1").toInt()
val ciVersionName = System.getenv("VERSION_NAME") ?: "1.0.$ciVersionCode"

// Version de sherpa-onnx (AAR téléchargé par scripts/download_models.sh)
val sherpaOnnxVersion = "1.13.8"

// Signature : keystore fourni par les secrets GitHub, sinon repli sur la clé de debug.
val releaseKeystore = System.getenv("KEYSTORE_FILE")?.let { File(it) }?.takeIf { it.isFile }

// Test de bout en bout sur émulateur x86_64 (CI) : ./gradlew -Pe2e ...
val e2e = project.hasProperty("e2e")

android {
    namespace = "fr.reunion.enregistreur"
    compileSdk = 36

    defaultConfig {
        applicationId = "fr.reunion.enregistreur"
        minSdk = 29
        targetSdk = 36
        versionCode = ciVersionCode
        versionName = ciVersionName
        ndk { abiFilters += if (e2e) listOf("arm64-v8a", "x86_64") else listOf("arm64-v8a") }
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    // En test e2e, on teste l'APK release (minifié par R8), comme sur le téléphone.
    if (e2e) testBuildType = "release"

    signingConfigs {
        if (releaseKeystore != null) {
            create("release") {
                storeFile = releaseKeystore
                storePassword = System.getenv("KEYSTORE_PASSWORD")
                keyAlias = System.getenv("KEY_ALIAS")
                keyPassword = System.getenv("KEY_PASSWORD") ?: System.getenv("KEYSTORE_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = if (releaseKeystore != null) {
                signingConfigs.getByName("release")
            } else {
                logger.warn("⚠️  Aucun keystore de release : APK signé avec la clé de DEBUG.")
                signingConfigs.getByName("debug")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    // Les modèles ONNX sont stockés non compressés : chargement direct (mmap) depuis l'APK.
    androidResources { noCompress += listOf("onnx", "txt") }

    packaging {
        resources.excludes += listOf("/META-INF/{AL2.0,LGPL2.1}", "/META-INF/*.kotlin_module")
    }

    testOptions { unitTests.isReturnDefaultValues = true }
    lint { abortOnError = false; checkReleaseBuilds = false }
}

ksp { arg("room.schemaLocation", "$projectDir/schemas") }

dependencies {
    implementation(files("libs/sherpa-onnx-$sherpaOnnxVersion.aar"))

    implementation("androidx.core:core-ktx:1.16.0")
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-service:2.8.7")
    implementation("androidx.navigation:navigation-compose:2.8.9")

    implementation(platform("androidx.compose:compose-bom:2025.05.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.ui:ui-tooling-preview")
    debugImplementation("androidx.compose.ui:ui-tooling")

    implementation("androidx.room:room-runtime:2.7.1")
    implementation("androidx.room:room-ktx:2.7.1")
    ksp("androidx.room:room-compiler:2.7.1")

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")

    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
}
