pluginManagement {
    includeBuild("build-logic")
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        // 음성 시작 감지(WebRTC VAD, MIT) 하나만 JitPack에서 받는다. 다른 의존성이 이 저장소를 쓰지 않게 묶어 둔다.
        exclusiveContent {
            forRepository { maven("https://jitpack.io") }
            filter { includeGroup("com.github.gkonovalov.android-vad") }
        }
    }
}

rootProject.name = "seesun"

include(":app")

// 공통 기반(03 문서 §3)
include(":core:designsystem")
include(":core:a11y")
include(":core:feedback")
include(":core:voice")
include(":core:assistant")
include(":core:auth")
include(":core:network")
include(":core:data")
include(":core:ui")
include(":core:uitest")

// 기능 모듈(T3 3-6에서 채운다)
include(":feature:onboarding")
include(":feature:main")
include(":feature:exercise")
include(":feature:diet")
include(":feature:mypage")

// 가운영 자체 업데이트(-Pseesun.selfUpdate=true 빌드에서만 앱이 켠다)
include(":feature:update")
include(":feature:admin")
