plugins {
    id("g5000.jvm.library")
    alias(libs.plugins.kotlin.serialization)
    application
}

application {
    mainClass.set("com.vocab5000.tools.content.ContentPipelineKt")
}

dependencies {
    implementation(project(":core:content"))
    implementation(project(":languages:german"))
    implementation(project(":core:learning"))
}
