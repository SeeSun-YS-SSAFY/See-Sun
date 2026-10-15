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
    }
}

rootProject.name = "seesun"

include(":app")

// 공통 기반(03 문서 §3)
include(":core:designsystem")
include(":core:a11y")
include(":core:feedback")
include(":core:voice")
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
