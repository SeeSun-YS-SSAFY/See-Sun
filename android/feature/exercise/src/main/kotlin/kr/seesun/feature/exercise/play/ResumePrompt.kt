package kr.seesun.feature.exercise.play

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.view.WindowManager
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalView
import kr.seesun.core.designsystem.component.BodyText
import kr.seesun.core.designsystem.component.BottomActions
import kr.seesun.core.designsystem.component.ButtonKind
import kr.seesun.core.designsystem.component.LiveStatusText
import kr.seesun.core.ui.AutoGuidance
import kr.seesun.core.ui.VoiceCommandButton
import kr.seesun.core.ui.VoiceCommandController
import kr.seesun.feature.exercise.R
import kr.seesun.feature.exercise.ui.ActionButton

/** 다른 앱으로 나갔다 돌아왔을 때 말로 고를 수 있는 것. */
enum class ResumeCommand { RESUME, END }

/** "이어서"·"계속"·"시작" → 이어서, "끝내"·"그만" → 끝내기. 둘 다 아니면 null. */
object ResumeVoiceMatcher {
    private val END = listOf("끝내", "끝낼", "그만", "종료", "나가")
    private val RESUME = listOf("이어", "계속", "시작", "재생", "다시해", "할게", "해줘")

    fun match(text: String): ResumeCommand? {
        val t = text.replace(" ", "")
        return when {
            END.any { t.contains(it) } -> ResumeCommand.END
            RESUME.any { t.contains(it) } -> ResumeCommand.RESUME
            else -> null
        }
    }
}

/** 설정에서 돌아왔을 때 읽어 주는 안내. */
const val RESUME_PROMPT_SETTINGS = "설정을 여느라 운동 안내를 멈췄어요. 이어서 하려면 이어서 시작을 누르거나 「이어서」라고 말씀해 주세요."

/** 다시 돌아왔을 때 읽어 주는 안내(TalkBack 꺼짐: 앱 음성, 켜짐: liveRegion). */
const val RESUME_PROMPT = "다른 앱으로 나가서 운동 안내를 멈췄어요. 이어서 하려면 이어서 시작을 누르거나 「이어서」라고 말씀해 주세요."

/**
 * 「이어서 시작」 화면 조각(소유자 결정 2026-10-15: 다른 앱으로 나가면 안내를 멈추고, 돌아오면 저절로 다시 틀지 않고 이어서 시작을 고르게 한다).
 * 버튼 [이어서 시작]·[운동 끝내기] + [말로 고르기](음성 자동 인식이 켜져 있으면 안내가 끝난 뒤 저절로 듣는다).
 */
@Composable
fun ColumnScope.ResumePrompt(
    onResume: () -> Unit,
    onEnd: () -> Unit,
    voiceCommand: VoiceCommandController?,
    talkBackEnabled: Boolean,
    speak: (String) -> Unit,
    fromSettings: Boolean = false
) {
    if (fromSettings) {
        // 설정에서 돌아왔을 때는 앱 안 이동이라 앱 보임 알림이 없다 → 여기서 한 번 알린다.
        LiveStatusText("설정을 여느라 안내를 멈췄어요.")
        AutoGuidance("resume-settings", RESUME_PROMPT_SETTINGS, talkBackEnabled, speak)
    } else {
        LiveStatusText("다른 앱으로 나가서 안내를 멈췄어요.")
    }
    BodyText("이어서 시작을 누르거나 「이어서」라고 말씀하시면 멈춘 곳부터 다시 들려 드려요.")
    BottomActions {
        ActionButton(text = "이어서 시작", iconRes = R.drawable.ic_exercise_play, onClick = onResume)
        if (voiceCommand != null) {
            VoiceCommandButton(voiceCommand, "이어서, 또는 끝내기라고 말씀해 주세요.", talkBackEnabled, speak)
        }
        ActionButton(text = "운동 끝내기", iconRes = R.drawable.ic_exercise_stop, kind = ButtonKind.SECONDARY, onClick = onEnd)
    }
}

/**
 * 운동하는 동안 화면이 저절로 꺼지지 않게 한다(창의 FLAG_KEEP_SCREEN_ON, 소유자 결정 2026-10-15).
 * [active]가 false가 되거나 화면을 떠나면 바로 놓아 준다. 전원 버튼으로 끄는 것은 막지 않는다.
 * View.keepScreenOn은 끌 때 창 속성이 바로 갱신되지 않는 경우가 있어(에뮬레이터 실측: 운동을 마친 뒤에도 남음) 창 플래그를 직접 다룬다.
 */
@Composable
fun KeepScreenOn(active: Boolean) {
    val window = LocalView.current.context.findActivity()?.window ?: return
    DisposableEffect(window, active) {
        if (active) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onDispose { if (active) window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
    }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
