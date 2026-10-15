plugins {
    alias(libs.plugins.seesun.android.library.compose)
}

android {
    namespace = "kr.seesun.core.ui"
}

// 화면 공통 조각: 음성·키보드 입력 칸(확인 되읽기 포함), 안내 음성 규칙(A9), 건강 항목 음성 해석.
// feature 모듈끼리 서로 의존하지 않도록 온보딩·마이페이지가 함께 쓰는 것을 여기에 둔다.
dependencies {
    api(project(":core:designsystem"))
    api(project(":core:voice"))
    api(project(":core:a11y"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.kotlinx.coroutines.android)
    testImplementation(libs.turbine)
}
