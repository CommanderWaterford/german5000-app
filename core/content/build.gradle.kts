plugins {
    id("g5000.jvm.library")
    alias(libs.plugins.kotlin.serialization)
}

dependencies {
    api(project(":core:model"))
    api(project(":core:learning"))
    testImplementation(project(":languages:german"))
}
