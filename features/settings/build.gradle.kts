plugins {
    id("g5000.android.feature")
}

android {
    namespace = "com.vocab5000.feature.settings"
}

dependencies {
    implementation(project(":core:settings"))
    implementation(project(":core:billing"))
    implementation(project(":core:content"))
}

dependencies {
    implementation(project(":core:database"))
    implementation(libs.androidx.activity.compose)
}
