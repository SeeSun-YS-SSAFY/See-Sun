plugins {
    alias(libs.plugins.seesun.android.library)
    alias(libs.plugins.seesun.hilt)
}

// Firebase 전화번호 인증 사용 여부(gradle.properties 또는 -Pseesun.firebase.enabled=true).
// false면 Firebase SDK 없이 컴파일되는 스텁 구현을 쓴다.
val firebaseEnabled = providers.gradleProperty("seesun.firebase.enabled").map(String::toBoolean).getOrElse(false)

android {
    namespace = "kr.seesun.core.auth"
    buildFeatures.buildConfig = true
    defaultConfig {
        buildConfigField("boolean", "FIREBASE_ENABLED", firebaseEnabled.toString())
    }
    sourceSets {
        named("main") {
            kotlin.directories.add(if (firebaseEnabled) "src/firebase/kotlin" else "src/firebaseStub/kotlin")
        }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.kotlinx.coroutines.android)
    if (firebaseEnabled) {
        implementation(platform(libs.firebase.bom))
        implementation(libs.firebase.auth)
    }
    testImplementation(libs.turbine)
}
