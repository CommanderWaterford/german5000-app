plugins {
    id("g5000.android.library")
}

android {
    namespace = "com.vocab5000.core.audio"
}

dependencies {
    api(project(":core:model"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.kotlinx.coroutines.android)
}
