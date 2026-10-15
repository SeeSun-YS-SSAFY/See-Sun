import java.util.Properties

plugins {
    alias(libs.plugins.seesun.android.application)
    alias(libs.plugins.seesun.hilt)
    alias(libs.plugins.kotlin.serialization)
}

val apiBaseUrl = providers.gradleProperty("seesun.apiBaseUrl").getOrElse("http://127.0.0.1:8000/api/v1/")
val useFakeData = providers.gradleProperty("seesun.fakeData").map(String::toBoolean).getOrElse(false)
val firebaseEnabled = providers.gradleProperty("seesun.firebase.enabled").map(String::toBoolean).getOrElse(false)

// 앱 버전: -Pseesun.versionCode=2 -Pseesun.versionName=0.2.0 (가운영 빌드 스크립트가 넘긴다). 기본값은 첫 배포 값.
val appVersionCode = providers.gradleProperty("seesun.versionCode").map(String::toInt).getOrElse(1)
val appVersionName = providers.gradleProperty("seesun.versionName").getOrElse("0.1.0")

// 가운영 자체 업데이트(-Pseesun.selfUpdate=true). 켜면 설치 권한(REQUEST_INSTALL_PACKAGES)·FileProvider가 담긴
// src/selfUpdate/AndroidManifest.xml을 합치고, 앱이 시작할 때 서버의 latest.json을 확인한다. Play·일반 빌드는 끈 채로 둔다.
val selfUpdate = providers.gradleProperty("seesun.selfUpdate").map(String::toBoolean).getOrElse(false)
val selfUpdateManifestUrl = providers.gradleProperty("seesun.selfUpdate.manifestUrl")
    .getOrElse(apiBaseUrl.replace(Regex("/api/v1/?$"), "/media/app/latest.json"))

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
                        "android/README.md의 'Firebase 전환'을 참고하세요."
                )
            }
        }
        tasks.named("preBuild") { dependsOn(missing) }
    }
}

// release 서명: -Pseesun.signing.file=<signing.properties 경로>를 줄 때만 서명한다(없으면 서명 안 된 release).
// 파일 내용: storeFile, storePassword, keyAlias, keyPassword. 키스토어·암호 파일은 저장소 밖에 둔다(.gitignore에도 막음).
val signingFile = providers.gradleProperty("seesun.signing.file").orNull?.let { file(it) }
val signingProps =
    signingFile?.let { f ->
        if (!f.exists()) throw GradleException("seesun.signing.file이 가리키는 파일이 없습니다: $f")
        Properties().apply { f.inputStream().use { load(it) } }
    }

android {
    namespace = "kr.seesun.app"

    signingConfigs {
        if (signingProps != null) {
            create("external") {
                storeFile = file(signingProps.getProperty("storeFile"))
                storePassword = signingProps.getProperty("storePassword")
                keyAlias = signingProps.getProperty("keyAlias")
                keyPassword = signingProps.getProperty("keyPassword")
            }
        }
    }

    defaultConfig {
        // 패키지명은 Play 등록 전 확정 필요(06 문서 §배포). 임시 값.
        applicationId = "kr.seesun.app"
        versionCode = appVersionCode
        versionName = appVersionName
        buildConfigField("String", "API_BASE_URL", "\"$apiBaseUrl\"")
        buildConfigField("boolean", "SELF_UPDATE", selfUpdate.toString())
        buildConfigField("String", "SELF_UPDATE_MANIFEST_URL", "\"$selfUpdateManifestUrl\"")
        buildConfigField("boolean", "USE_FAKE_DATA", useFakeData.toString())
        // 실서버 기기 시험(@RealServer)은 평소 게이트에서 뺀다. scripts/e2e 실행기가 클래스를 직접 골라 돌린다.
        testInstrumentationRunnerArguments["notAnnotation"] = "kr.seesun.app.RealServer"
    }

    buildFeatures.buildConfig = true

    if (selfUpdate) {
        // 빌드 유형 소스 세트의 매니페스트로 합친다(main 매니페스트는 그대로). 플래그가 꺼진 빌드에는 들어가지 않는다.
        sourceSets.getByName("debug").manifest.srcFile("src/selfUpdate/AndroidManifest.xml")
        sourceSets.getByName("release").manifest.srcFile("src/selfUpdate/AndroidManifest.xml")
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            if (signingProps != null) signingConfig = signingConfigs.getByName("external")
        }
    }
}

// 설정 → 오픈소스 라이선스 화면이 읽는 목록(assets/open_source_licenses.json)을 빌드 때 자동 생성(법적 검토 T-11).
registerOssLicenses()

dependencies {
    androidTestImplementation(project(":core:uitest"))
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
    implementation(project(":feature:update"))

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

// ── Play 정책 검사(법률 검토 P-10): release 병합 매니페스트에 SMS·통화기록 권한이 들어오면 빌드 실패 ──
// Firebase·Play services 등 라이브러리가 권한을 끌어올 수 있어 병합 결과를 검사한다. 전체 권한 목록도 로그에 남긴다.
val forbiddenPermissions = setOf(
    "android.permission.READ_SMS",
    "android.permission.RECEIVE_SMS",
    "android.permission.READ_CALL_LOG"
)
val installPermission = "android.permission.REQUEST_INSTALL_PACKAGES"
androidComponents {
    onVariants(selector().withBuildType("release")) { variant ->
        val merged = variant.artifacts.get(com.android.build.api.artifact.SingleArtifact.MERGED_MANIFEST)
        val taskName = "check${variant.name.replaceFirstChar { it.uppercase() }}ManifestPermissions"
        val check = tasks.register(taskName) {
            group = "verification"
            description = "release 병합 매니페스트 권한 목록을 출력하고 금지 권한(SMS·통화기록)이 있으면 실패한다."
            inputs.file(merged)
            doLast {
                val xml = merged.get().asFile.readText()
                val permissions = Regex("""<uses-permission(?:-sdk-23)?\s[^>]*android:name="([^"]+)"""")
                    .findAll(xml).map { it.groupValues[1] }.toSortedSet()
                logger.lifecycle("[$taskName] 병합 매니페스트 권한 ${permissions.size}개:")
                permissions.forEach { logger.lifecycle("  - $it") }
                val found = permissions intersect forbiddenPermissions
                if (found.isNotEmpty()) {
                    throw GradleException("금지 권한이 release 매니페스트에 있습니다(Play SMS·통화기록 정책): $found")
                }
                // 자체 업데이트를 끈 빌드(Play 등)에 설치 권한이 끌려 들어오면 실패시킨다.
                if (!selfUpdate && installPermission in permissions) {
                    throw GradleException("seesun.selfUpdate=false 빌드에 $installPermission 권한이 있습니다")
                }
            }
        }
        tasks.matching { it.name == "check" }.configureEach { dependsOn(check) }
    }
}
