plugins {
    id("g5000.android.feature")
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "com.vocab5000.feature.study"
}

dependencies {
    implementation(project(":core:learning"))
    implementation(project(":core:database"))
    implementation(project(":core:content"))
    implementation(project(":core:settings"))
    implementation(project(":core:audio"))
}
