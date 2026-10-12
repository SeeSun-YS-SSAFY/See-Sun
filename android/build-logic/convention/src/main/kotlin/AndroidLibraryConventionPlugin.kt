import com.android.build.api.dsl.LibraryExtension
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure

/** core:* 공통 Android 라이브러리 규칙. */
class AndroidLibraryConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) {
        with(target) {
            pluginManager.apply("com.android.library")
            extensions.configure<LibraryExtension> {
                configureAndroidCommon(this)
                defaultConfig.testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
                lint.targetSdk = libs.version("targetSdk").toInt()
                testOptions.targetSdk = libs.version("targetSdk").toInt()
            }
            configureUnitTests()
            configureInstrumentedTests()
        }
    }
}
