plugins {
    alias(libs.plugins.seesun.android.feature)
}

android {
    namespace = "kr.seesun.feature.update"
}

// 가운영 자체 업데이트: 매니페스트(latest.json) 확인 → APK 내려받기·검증 → 시스템 설치 화면.
// 이 모듈의 AndroidManifest에는 설치 권한·FileProvider를 넣지 않는다. 그 둘은 app이 -Pseesun.selfUpdate=true일 때만
// app/src/selfUpdate/AndroidManifest.xml로 합친다(Play·일반 release에 REQUEST_INSTALL_PACKAGES가 들어가지 않게).
dependencies {
    implementation(project(":core:voice"))
    implementation(libs.androidx.core.ktx)
    implementation(platform(libs.okhttp.bom))
    implementation(libs.okhttp.core)
    implementation(libs.kotlinx.coroutines.android)
    testImplementation(platform(libs.okhttp.bom))
    testImplementation(libs.okhttp.mockwebserver)
}
