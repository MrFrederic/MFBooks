plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    application
}

version = "0.1.0"
kotlin { jvmToolchain(21) }
application { mainClass = "app.mfbooks.server.MainKt" }

dependencies {
    implementation(project(":shared"))
    implementation(libs.ktor.server.core)
    implementation(libs.ktor.server.cio)
    implementation(libs.ktor.server.content.negotiation)
    implementation(libs.ktor.serialization.json)
    implementation(libs.ktor.server.websockets)
    implementation(libs.ktor.server.partial.content)
    implementation(libs.ktor.server.auto.head.response)
    implementation(libs.ktor.server.status.pages)
    implementation(libs.ktor.server.forwarded.header)
    implementation(libs.sqlite.jdbc)
    implementation(libs.bouncycastle)
    implementation(libs.logback)
    implementation(libs.json)
    testImplementation(libs.ktor.server.test.host)
    testImplementation(libs.ktor.client.websockets)
    testImplementation(kotlin("test"))
}

tasks.test { useJUnitPlatform() }
tasks.jar { manifest { attributes("Implementation-Version" to version) } }
