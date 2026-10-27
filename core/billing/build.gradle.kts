plugins {
    id("g5000.android.library")
}

android {
    namespace = "com.vocab5000.core.billing"
}

dependencies {
    implementation(project(":core:database"))
    implementation(libs.billing.ktx)
    implementation(libs.work.runtime)
    implementation(libs.kotlinx.coroutines.android)

    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.kotlinx.coroutines.test)
}
