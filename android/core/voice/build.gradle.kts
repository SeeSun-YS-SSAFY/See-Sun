plugins {
    alias(libs.plugins.seesun.android.library)
    alias(libs.plugins.seesun.hilt)
}

android {
    namespace = "kr.seesun.core.voice"
}

dependencies {
    implementation(project(":core:a11y"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.kotlinx.coroutines.android)
    // 테스트 벡터(JSON) 읽기용
    testImplementation(libs.kotlinx.serialization.json)
    testImplementation(libs.turbine)
}
