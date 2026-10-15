plugins {
    alias(libs.plugins.seesun.android.library)
    alias(libs.plugins.seesun.hilt)
}

android {
    namespace = "kr.seesun.core.voice"
}

dependencies {
    api(project(":core:a11y"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.kotlinx.coroutines.android)
    // 음성 시작 감지(VAD 대기, 소유자 결정 2026-10-15 하이브리드 방식): WebRTC VAD(MIT, 네이티브 .so 포함)
    implementation(libs.android.vad.webrtc)
    // 테스트 벡터(JSON) 읽기용
    testImplementation(libs.kotlinx.serialization.json)
    testImplementation(libs.turbine)
}
