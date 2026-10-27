plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.room) apply false
}

// Runs the plain-JVM module tests. Use together with `testDebugUnitTest`; the release-variant
// unit tests intentionally hit the release content gate until the full catalog exists.
tasks.register("jvmTest") {
    group = "verification"
    description = "Runs tests of all pure Kotlin/JVM modules"
    dependsOn(
        ":core:model:test",
        ":core:learning:test",
        ":core:content:test",
        ":languages:german:test",
        ":tools:content-pipeline:test",
    )
}
