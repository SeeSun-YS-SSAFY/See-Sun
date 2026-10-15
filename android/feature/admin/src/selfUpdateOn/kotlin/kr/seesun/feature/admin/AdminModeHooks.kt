package kr.seesun.feature.admin

import android.content.Context
import android.os.SystemClock
import android.util.Log
import android.widget.Toast
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.serialization.Serializable
import kr.seesun.core.designsystem.component.BodyText
import kr.seesun.core.designsystem.component.ListButton
import kr.seesun.core.designsystem.component.SettingsExtras
import kr.seesun.core.designsystem.theme.SeeSunTheme
import kr.seesun.core.voice.recognition.OnsetTuningSource
import kr.seesun.core.voice.recognition.VOICE_LOG_TAG

/**
 * 관리자 모드(가운영 자체 업데이트 빌드만, 소유자 msg 1063).
 *
 * 켜는 법: 어느 화면에서든 머리의 [설정](톱니바퀴)을 3초 안에 7번 누른다(로그인 전 시작 화면·권한 안내 포함).
 * 첫 누름은 평소처럼 설정을 연다. 설정 목록에도 같은 자리에 톱니가 있어(이 빌드만) 이어지는 누름이 거기서 세어진다 —
 * 설정 목록의 톱니는 화면을 바꾸지 않는다. 4번째부터 「관리자 모드까지 n번」, 7번째에 「관리자 모드를 켰어요」를 짧게 띄운다.
 * 켜진 상태는 기기에 남고, 설정 맨 아래 「관리자 모드」 묶음에서 끈다.
 */
@Singleton
class AdminModeHooks @Inject constructor(@param:ApplicationContext private val context: Context, private val settings: AdminSettings) :
    AdminHooks {
    override val available: Boolean = true
    private val counter = AdminTapCounter()
    private var toast: Toast? = null

    override fun onGearTap() {
        if (settings.enabled.value) return
        when (val step = counter.tap(SystemClock.elapsedRealtime())) {
            TapStep.Silent -> Unit

            is TapStep.Countdown -> show(AdminTapCounter.countdownText(step.remaining))

            TapStep.Enabled -> {
                settings.setEnabled(true)
                Log.i(VOICE_LOG_TAG, "admin mode enabled by gear taps")
                show(AdminTapCounter.ENABLED_TEXT)
            }
        }
    }

    fun disable() {
        settings.setEnabled(false)
        counter.reset()
        Log.i(VOICE_LOG_TAG, "admin mode disabled")
        show(AdminTapCounter.DISABLED_TEXT)
    }

    private fun show(text: String) {
        toast?.cancel()
        toast = Toast.makeText(context, text, Toast.LENGTH_SHORT).also { it.show() }
    }

    override val settingsExtras: SettingsExtras = SettingsExtras(
        gearOnSettingsList = true,
        settingsFooter = { onOpen -> AdminSettingsSection(settings, onOpen, ::disable) },
        voiceAutoNote = { TuningInUseNote(settings) }
    )
}

/** 관리자 화면 경로. */
@Serializable
data object VoiceLabRoute

fun NavGraphBuilder.adminGraph(nav: NavController) {
    composable<VoiceLabRoute> { VoiceLabRoute(onBack = { nav.popBackStack() }) }
}

/** 설정 맨 아래 「관리자 모드」 묶음(켜져 있을 때만): [음성 감지 시험] · 관리자 모드 끄기 스위치. */
@Composable
private fun ColumnScope.AdminSettingsSection(settings: AdminSettings, onOpen: (Any) -> Unit, onDisable: () -> Unit) {
    val enabled by settings.enabled.collectAsStateWithLifecycle()
    if (!enabled) return
    Text(
        text = "관리자 모드",
        style = SeeSunTheme.typography.listItem,
        color = SeeSunTheme.colors.emphasis,
        modifier = Modifier.semantics { heading() }
    )
    BodyText("가운영 시험용이에요. 여기서 바꾼 값은 이 휴대폰에만 저장돼요.")
    ListButton(
        text = "음성 감지 시험",
        description = "말소리 감지 기준을 이 휴대폰에 맞춰요",
        descriptionVisible = true,
        onClick = { onOpen(VoiceLabRoute) }
    )
    // 스위치: 「관리자 모드, 선택됨」 — 누르면 꺼지고 이 묶음이 사라진다.
    ListButton(text = "관리자 모드", description = "누르면 꺼요", selected = true, descriptionVisible = true, onClick = onDisable)
}

/** 「음성 자동 인식」 화면의 작은 알림: 관리자 모드에서 맞춘 값으로 말소리를 기다리는 중. */
@Composable
private fun TuningInUseNote(settings: AdminSettings) {
    val tuning by settings.tuning.collectAsStateWithLifecycle()
    val t = tuning ?: return
    Text(
        text = "조정값 사용 중 — 관리자 모드 음성 감지 시험 값(여유 ${t.marginDb}dB · ${t.onsetMs}ms)",
        style = SeeSunTheme.typography.caption,
        color = SeeSunTheme.colors.emphasis
    )
}

@Module
@InstallIn(SingletonComponent::class)
internal abstract class AdminBindings {
    @Binds
    abstract fun hooks(impl: AdminModeHooks): AdminHooks

    @Binds
    abstract fun tuning(impl: AdminSettings): OnsetTuningSource

    companion object {
        @Provides
        @Singleton
        fun settings(@ApplicationContext context: Context): AdminSettings =
            AdminSettings(SharedPrefsKeyValue(context.getSharedPreferences(AdminSettings.PREFS, Context.MODE_PRIVATE)))
    }
}
