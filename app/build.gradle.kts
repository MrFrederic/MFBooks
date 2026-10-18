plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
}

android {
    namespace = "app.mfbooks"
    compileSdk { version = release(37) { minorApiLevel = 2 } }
    defaultConfig {
        applicationId = "app.mfbooks"
        minSdk = 29
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
    }
    // Release signing from env (CI secrets); unsigned release build when absent.
    val ks = System.getenv("SIGNING_KEYSTORE")
    if (ks != null) signingConfigs.create("release") {
        storeFile = file(ks)
        storePassword = System.getenv("SIGNING_PASSWORD")
        keyAlias = System.getenv("SIGNING_ALIAS") ?: "mfbooks"
        keyPassword = System.getenv("SIGNING_PASSWORD")
    }
    buildTypes { release { signingConfig = signingConfigs.findByName("release") } }
    buildFeatures { compose = true }
}

ksp { arg("room.schemaLocation", "$projectDir/schemas") }

dependencies {
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.compose.material3.navsuite)
    implementation(libs.compose.icons.extended)
    implementation(libs.activity.compose)
    implementation(libs.lifecycle.runtime.compose)
    implementation(libs.lifecycle.viewmodel.compose)
    implementation(libs.navigation.compose)
    implementation(libs.media3.exoplayer)
    implementation(libs.media3.session)
    implementation(libs.media3.inspector)
    implementation(libs.media3.cast)
    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)
    implementation(libs.datastore.preferences)
    implementation(libs.work.runtime)
    implementation(libs.coil.compose)
    implementation(libs.material.kolor)
    implementation(libs.car.app)
    implementation(libs.juniversalchardet)

    testImplementation(libs.junit)
    testImplementation(libs.json) // android's org.json is a stub on the JVM
}
