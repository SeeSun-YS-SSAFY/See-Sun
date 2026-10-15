plugins {
    alias(libs.plugins.seesun.android.library.compose)
}

android {
    namespace = "kr.seesun.core.uitest"
}

// 기기 시험 공통 도우미(화면 맞춤·배치 기준 판정). feature·app 모듈이 androidTestImplementation으로만 쓴다.
// 판정 기준은 docs/design/11_레이아웃_기준.md 를 따른다.
dependencies {
    implementation(project(":core:designsystem"))
    api(platform(libs.androidx.compose.bom))
    api(libs.androidx.compose.ui.test.junit4)
    api(libs.androidx.test.runner)
    api(libs.junit4)
}
