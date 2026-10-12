plugins {
    alias(libs.plugins.seesun.android.library.compose)
}

android {
    namespace = "kr.seesun.core.a11y"
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.kotlinx.coroutines.core)
}
