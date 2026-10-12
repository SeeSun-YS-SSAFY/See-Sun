plugins {
    alias(libs.plugins.seesun.android.application)
    alias(libs.plugins.seesun.hilt)
    alias(libs.plugins.kotlin.serialization)
}

val apiBaseUrl = providers.gradleProperty("seesun.apiBaseUrl").getOrElse("http://10.0.2.2:8000/api/v1/")
val useFakeData = providers.gradleProperty("seesun.fakeData").map(String::toBoolean).getOrElse(false)
val firebaseEnabled = providers.gradleProperty("seesun.firebase.enabled").map(String::toBoolean).getOrElse(false)

if (firebaseEnabled) {
    // Firebase를 켤 때만 google-services 플러그인을 적용한다. app/google-services.json이 있어야 한다.
    if (file("google-services.json").exists()) {
        apply(plugin = "com.google.gms.google-services")
    } else {
        // 설정 단계가 아니라 app 빌드 때 실패시킨다(라이브러리 모듈은 따로 컴파일해 볼 수 있게).
        val missing = tasks.register("checkGoogleServicesJson") {
            doLast {
                throw GradleException(
                    "seesun.firebase.enabled=true 이지만 app/google-services.json이 없습니다. " +
                        "android/README.md의 'Firebase 전환'을 참고하세요.",
                )
            }
        }
        tasks.named("preBuild") { dependsOn(missing) }
    }
}

android {
    namespace = "kr.seesun.app"

    defaultConfig {
        // 패키지명은 Play 등록 전 확정 필요(06 문서 §배포). 임시 값.
        applicationId = "kr.seesun.app"
        versionCode = 1
        versionName = "0.1.0"
        buildConfigField("String", "API_BASE_URL", "\"$apiBaseUrl\"")
        buildConfigField("boolean", "USE_FAKE_DATA", useFakeData.toString())
    }

    buildFeatures.buildConfig = true

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
        }
        release {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
}

dependencies {
    implementation(project(":core:designsystem"))
    implementation(project(":core:a11y"))
    implementation(project(":core:feedback"))
    implementation(project(":core:voice"))
    implementation(project(":core:auth"))
    implementation(project(":core:network"))
    implementation(project(":core:data"))
    implementation(project(":feature:onboarding"))
    implementation(project(":feature:main"))
    implementation(project(":feature:exercise"))
    implementation(project(":feature:diet"))
    implementation(project(":feature:mypage"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.hilt.navigation.compose)
    implementation(libs.androidx.hilt.work)
    implementation(libs.androidx.work.runtime)
    implementation(libs.kotlinx.serialization.json)

    androidTestImplementation(libs.androidx.test.espresso.core)
}
