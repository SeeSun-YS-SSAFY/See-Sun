import org.gradle.api.Plugin
import org.gradle.api.Project

/** feature:* 모듈 공통: Compose 라이브러리 + Hilt + 디자인 시스템·접근성 모듈. */
class AndroidFeatureConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) {
        with(target) {
            pluginManager.apply("seesun.android.library.compose")
            pluginManager.apply("seesun.hilt")
            dependencies.apply {
                add("implementation", project(":core:designsystem"))
                add("implementation", project(":core:a11y"))
                add("implementation", libs.lib("androidx-lifecycle-runtime-compose"))
                add("implementation", libs.lib("androidx-lifecycle-viewmodel-compose"))
                add("implementation", libs.lib("androidx-hilt-navigation-compose"))
                add("implementation", libs.lib("androidx-navigation-compose"))
            }
        }
    }
}
