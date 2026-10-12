plugins {
    alias(libs.plugins.seesun.android.library)
    alias(libs.plugins.seesun.hilt)
}

android {
    namespace = "kr.seesun.core.feedback"
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.kotlinx.coroutines.android)
}
