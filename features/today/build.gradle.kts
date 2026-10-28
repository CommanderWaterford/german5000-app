plugins {
    id("g5000.android.feature")
}

android {
    namespace = "com.vocab5000.feature.today"
}

dependencies {
    implementation(project(":core:learning"))
    implementation(project(":core:database"))
    implementation(project(":core:content"))
    implementation(project(":core:settings"))
    implementation(libs.compose.material.icons.extended)
}
