import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// Pure Kotlin shared by the app and the server: parsers, metadata merge, sync types.
plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

java { sourceCompatibility = JavaVersion.VERSION_11; targetCompatibility = JavaVersion.VERSION_11 }
kotlin { compilerOptions { jvmTarget = JvmTarget.JVM_11 } }

dependencies {
    api(libs.serialization.json)
    implementation(libs.juniversalchardet)
    compileOnly(libs.json) // Android ships org.json; the server adds the jar
    testImplementation(libs.junit)
    testImplementation(libs.json)
}
