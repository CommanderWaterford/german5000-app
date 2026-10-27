plugins {
    id("g5000.android.feature")
}

android {
    namespace = "com.vocab5000.feature.progress"
}

dependencies {
    implementation(project(":core:learning"))
    implementation(project(":core:database"))
    implementation(project(":core:content"))
}
