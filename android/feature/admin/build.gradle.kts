plugins {
    alias(libs.plugins.seesun.android.feature)
}

// v7 관리자 모드(소유자 msg 1063: 설정 톱니 연타 → 관리자 모드 → 음성 감지 시험). 가운영 자체 업데이트 빌드에만 들어간다.
// -Pseesun.selfUpdate=true(app·feature:update와 같은 gradle 속성)면 src/selfUpdateOn/kotlin(톱니 횟수·저장·시험 화면)을,
// 끄면(Play·일반 빌드) 아무것도 하지 않는 src/selfUpdateOff/kotlin을 컴파일한다 — 끈 빌드에는 관리자 모드 코드·문구가 아예 없다.
// 시험도 같은 방식: 켠 빌드는 src/test/selfUpdateOn, 끈 빌드는 src/test/selfUpdateOff(없음 확인)를 더한다.
val selfUpdate = providers.gradleProperty("seesun.selfUpdate").map(String::toBoolean).getOrElse(false)

android {
    namespace = "kr.seesun.feature.admin"
    sourceSets {
        named("main") {
            kotlin.directories.add(if (selfUpdate) "src/selfUpdateOn/kotlin" else "src/selfUpdateOff/kotlin")
        }
        named("test") {
            kotlin.directories.add(if (selfUpdate) "src/test/selfUpdateOn/kotlin" else "src/test/selfUpdateOff/kotlin")
        }
    }
}

dependencies {
    implementation(project(":core:voice"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.kotlinx.coroutines.android)
}
