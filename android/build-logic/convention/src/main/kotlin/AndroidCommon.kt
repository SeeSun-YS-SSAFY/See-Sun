import com.android.build.api.dsl.CommonExtension
import org.gradle.api.JavaVersion
import org.gradle.api.Project
import org.gradle.api.artifacts.VersionCatalog
import org.gradle.api.artifacts.VersionCatalogsExtension
import org.gradle.api.tasks.testing.Test
import org.gradle.kotlin.dsl.getByType
import org.gradle.kotlin.dsl.withType

internal val Project.libs: VersionCatalog
    get() = extensions.getByType<VersionCatalogsExtension>().named("libs")

internal fun VersionCatalog.version(alias: String): String = findVersion(alias).get().requiredVersion

internal fun VersionCatalog.lib(alias: String) = findLibrary(alias).get()

/**
 * 앱·라이브러리 공통 Android 설정.
 * - compileSdk 36 / minSdk 26 (08 문서 §14)
 * - Java 17 (AGP 9 내장 Kotlin은 jvmTarget을 compileOptions에서 따른다)
 * - Android Lint: 접근성 검사를 오류로 올리고, 버전 알림류만 끈다.
 */
internal fun Project.configureAndroidCommon(extension: CommonExtension) {
    extension.apply {
        compileSdk = libs.version("compileSdk").toInt()
        defaultConfig.minSdk = libs.version("minSdk").toInt()
        compileOptions.sourceCompatibility = JavaVersion.VERSION_17
        compileOptions.targetCompatibility = JavaVersion.VERSION_17
        lint.apply {
            abortOnError = true
            warningsAsErrors = false
            checkDependencies = false
            // 의존성 새 버전 알림은 버전표(08 §14)로 관리하므로 끈다.
            disable += setOf(
                "GradleDependency",
                "NewerVersionAvailable",
                "AndroidGradlePluginVersion",
                "OldTargetApi",
            )
            // 접근성 관련 검사는 오류로 올린다.
            error += setOf(
                "ContentDescription",
                "ClickableViewAccessibility",
                "KeyboardInaccessibleWidget",
                "LabelFor",
            )
        }
        packaging.resources.excludes += setOf(
            "META-INF/AL2.0",
            "META-INF/LGPL2.1",
            "META-INF/LICENSE.md",
            "META-INF/LICENSE-notice.md",
        )
    }
}

/** 로컬 단위 테스트는 JUnit 5(Jupiter)로 실행한다(08 문서 §11). */
internal fun Project.configureUnitTests() {
    tasks.withType<Test>().configureEach {
        useJUnitPlatform()
        testLogging {
            events("failed")
        }
    }
    dependencies.apply {
        add("testImplementation", platform(libs.lib("junit5-bom")))
        add("testImplementation", libs.lib("junit5-jupiter"))
        add("testRuntimeOnly", libs.lib("junit5-launcher"))
        add("testImplementation", libs.lib("kotlinx-coroutines-test"))
    }
}

/** 기기(에뮬레이터) 테스트 공통 의존성. androidx.test는 JUnit 4 러너를 쓴다. */
internal fun Project.configureInstrumentedTests() {
    dependencies.apply {
        add("androidTestImplementation", libs.lib("androidx-test-core"))
        add("androidTestImplementation", libs.lib("androidx-test-runner"))
        add("androidTestImplementation", libs.lib("androidx-test-ext-junit"))
        add("androidTestImplementation", libs.lib("junit4"))
    }
}

/** Compose 공통 설정: 컴파일러 플러그인, BOM, UI 테스트(접근성 검사 포함) 의존성. */
internal fun Project.configureCompose(extension: CommonExtension) {
    pluginManager.apply("org.jetbrains.kotlin.plugin.compose")
    extension.buildFeatures.compose = true
    dependencies.apply {
        val bom = platform(libs.lib("androidx-compose-bom"))
        add("implementation", bom)
        add("androidTestImplementation", bom)
        add("implementation", libs.lib("androidx-compose-ui"))
        add("implementation", libs.lib("androidx-compose-foundation"))
        add("implementation", libs.lib("androidx-compose-material3"))
        add("implementation", libs.lib("androidx-compose-ui-tooling-preview"))
        add("debugImplementation", libs.lib("androidx-compose-ui-tooling"))
        add("debugImplementation", libs.lib("androidx-compose-ui-test-manifest"))
        add("androidTestImplementation", libs.lib("androidx-compose-ui-test-junit4"))
        add("androidTestImplementation", libs.lib("androidx-compose-ui-test-junit4-accessibility"))
    }
}
