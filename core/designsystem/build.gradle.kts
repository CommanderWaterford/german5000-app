plugins {
    id("g5000.android.library")
    id("g5000.android.compose")
}

android {
    namespace = "com.vocab5000.core.designsystem"
}

dependencies {
    // Speaker, menu-book and chart icons used by the shared components and every feature.
    api(libs.compose.material.icons.extended)
}
