import org.gradle.api.Plugin
import org.gradle.api.Project

/** Hilt(DI) + KSP 설정(08 문서 §4.2). */
class HiltConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) {
        with(target) {
            pluginManager.apply("com.google.devtools.ksp")
            pluginManager.apply("com.google.dagger.hilt.android")
            dependencies.add("implementation", libs.lib("hilt-android"))
            dependencies.add("ksp", libs.lib("hilt-compiler"))
        }
    }
}
