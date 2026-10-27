plugins {
    id("g5000.android.library")
}

android {
    namespace = "com.vocab5000.core.settings"
}

dependencies {
    api(project(":core:model"))
    implementation(project(":core:learning"))
    implementation(project(":core:database"))
    implementation(project(":core:content"))
    api(libs.datastore.preferences)
    implementation(libs.work.runtime)
    implementation(libs.androidx.core.ktx)
    implementation(libs.gson)
    implementation(libs.kotlinx.coroutines.android)

    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
}
