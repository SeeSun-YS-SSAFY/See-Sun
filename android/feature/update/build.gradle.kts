plugins {
    alias(libs.plugins.seesun.android.feature)
}

// 가운영 자체 업데이트(-Pseesun.selfUpdate=true, app과 같은 gradle 속성). 켜면 백그라운드 확인(WorkManager)·업데이트 알림 코드가
// 담긴 src/selfUpdateOn/kotlin을, 끄면(Play·일반 빌드) 아무것도 하지 않는 src/selfUpdateOff/kotlin을 컴파일한다 —
// 끈 빌드에는 업데이트 확인 작업·알림 클래스가 아예 들어가지 않는다(core:auth의 firebase/firebaseStub과 같은 방식).
val selfUpdate = providers.gradleProperty("seesun.selfUpdate").map(String::toBoolean).getOrElse(false)

android {
    namespace = "kr.seesun.feature.update"
    sourceSets {
        named("main") {
            kotlin.directories.add(if (selfUpdate) "src/selfUpdateOn/kotlin" else "src/selfUpdateOff/kotlin")
        }
    }
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
    implementation(libs.androidx.work.runtime)
    implementation(libs.androidx.hilt.work)
    ksp(libs.androidx.hilt.compiler)
    testImplementation(platform(libs.okhttp.bom))
    testImplementation(libs.okhttp.mockwebserver)
}
