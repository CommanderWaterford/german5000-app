plugins {
    id("g5000.android.feature")
}

android {
    namespace = "com.vocab5000.feature.premium"
}

dependencies {
    implementation(project(":core:billing"))
}
