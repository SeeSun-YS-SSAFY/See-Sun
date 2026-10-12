plugins {
    alias(libs.plugins.seesun.android.library.compose)
}

android {
    namespace = "kr.seesun.core.designsystem"
}

dependencies {
    api(project(":core:a11y"))
    implementation(libs.androidx.core.ktx)
}
