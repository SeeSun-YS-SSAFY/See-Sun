import com.android.build.api.dsl.LibraryExtension
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.getByType

/** Compose를 쓰는 Android 라이브러리 규칙. */
class AndroidLibraryComposeConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) {
        with(target) {
            pluginManager.apply("seesun.android.library")
            configureCompose(extensions.getByType<LibraryExtension>())
        }
    }
}
