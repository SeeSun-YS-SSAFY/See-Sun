plugins {
    alias(libs.plugins.seesun.android.feature)
}

android {
    namespace = "kr.seesun.feature.diet"
}

// v2.0 식단 음성 기록·경고: 음성 화면 조각(core:ui = 디자인 시스템·음성·시선 비서), 진동·소리(core:feedback), 기기 암호화 저장(core:auth).
dependencies {
    implementation(project(":core:data"))
    implementation(project(":core:ui"))
    implementation(project(":core:feedback"))
    implementation(project(":core:auth"))
    implementation(libs.androidx.activity.compose)
    implementation(libs.kotlinx.coroutines.android)
    testImplementation(libs.turbine)
}
