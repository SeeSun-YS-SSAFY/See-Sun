package kr.seesun.feature.admin

import androidx.navigation.NavController
import androidx.navigation.NavGraphBuilder
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Inject
import javax.inject.Singleton
import kr.seesun.core.designsystem.component.SettingsExtras
import kr.seesun.core.voice.recognition.DefaultOnsetTuningSource
import kr.seesun.core.voice.recognition.OnsetTuningSource

/**
 * 자체 업데이트를 끈 빌드(Play·일반 release, `-Pseesun.selfUpdate` 없음): 관리자 모드가 없다.
 * 톱니를 몇 번 눌러도 아무 일이 없고, 말 시작 판정은 늘 코드 기본값이다. 실제 구현은 src/selfUpdateOn/kotlin.
 */
@Singleton
class NoAdminHooks @Inject constructor() : AdminHooks {
    override val available: Boolean = false

    override fun onGearTap() = Unit

    override val settingsExtras: SettingsExtras = SettingsExtras()
}

/** 관리자 화면 경로가 없다. */
@Suppress("UnusedReceiverParameter", "UNUSED_PARAMETER")
fun NavGraphBuilder.adminGraph(nav: NavController) = Unit

@Module
@InstallIn(SingletonComponent::class)
internal abstract class AdminBindings {
    @Binds
    abstract fun hooks(impl: NoAdminHooks): AdminHooks

    companion object {
        @Provides
        @Singleton
        fun tuning(): OnsetTuningSource = DefaultOnsetTuningSource()
    }
}
