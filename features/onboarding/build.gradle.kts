plugins {
    id("g5000.android.feature")
}

android {
    namespace = "com.vocab5000.feature.onboarding"
}

dependencies {
    implementation(project(":core:settings"))
}

dependencies {
    implementation(libs.androidx.activity.compose)
}
