plugins {
    alias(libs.plugins.seesun.android.feature)
}

android {
    namespace = "kr.seesun.feature.exercise"
    defaultConfig {
        // 기기 테스트는 Hilt 테스트 앱으로 돌린다(가짜 플레이어·전송 예약 교체).
        testInstrumentationRunner = "kr.seesun.feature.exercise.HiltTestRunner"
    }
}

dependencies {
    androidTestImplementation(project(":core:uitest"))
    implementation(project(":core:data"))
    implementation(project(":core:feedback"))
    implementation(project(":core:voice"))
    // 운동 중 「이어서 시작」의 말로 하기(음성 명령 버튼·자동 인식)
    implementation(project(":core:ui"))
    implementation(project(":core:auth"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.androidx.media3.exoplayer)
    implementation(libs.androidx.media3.datasource)
    implementation(libs.androidx.media3.database)
    // 이어폰 버튼으로 일시정지·재생(안전 안내의 "즉시 멈춤" 방법)
    implementation(libs.androidx.media3.session)
    // 다른 앱으로 나감(일시정지) / 화면 꺼짐(계속 재생) 구분
    implementation(libs.androidx.lifecycle.process)

    testImplementation(libs.turbine)

    androidTestImplementation(libs.hilt.android.testing)
    kspAndroidTest(libs.hilt.compiler)
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.compose.ui.test.junit4.accessibility)
}
