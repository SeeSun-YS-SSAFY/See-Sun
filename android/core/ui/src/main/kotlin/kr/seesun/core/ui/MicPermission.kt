package kr.seesun.core.ui

import android.speech.SpeechRecognizer
import android.util.Log
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.LifecycleResumeEffect
import kr.seesun.core.designsystem.component.ButtonKind
import kr.seesun.core.designsystem.component.LiveStatusText
import kr.seesun.core.designsystem.component.SeeSunButton
import kr.seesun.core.designsystem.theme.SeeSunDimens
import kr.seesun.core.designsystem.theme.SeeSunTheme
import kr.seesun.core.voice.recognition.VOICE_LOG_TAG

/** 마이크를 쓸 수 있는 상태(음성 입력 화면이 보여 줄 안내를 고른다). */
enum class MicAvailability {
    READY,

    /** 마이크 권한이 꺼져 있다 → 「마이크 권한이 꺼져 있어요 [허용하기]」(앱 설정 열기). */
    PERMISSION_OFF,

    /** 휴대폰에 음성 인식 서비스가 없거나 꺼져 있다(SpeechRecognizer.isRecognitionAvailable=false). */
    RECOGNIZER_UNAVAILABLE;

    companion object {
        /** 권한이 먼저(권한이 없으면 인식기 상태와 관계없이 권한 안내). */
        fun of(permissionGranted: Boolean, recognizerAvailable: Boolean): MicAvailability = when {
            !permissionGranted -> PERMISSION_OFF
            !recognizerAvailable -> RECOGNIZER_UNAVAILABLE
            else -> READY
        }
    }
}

/** 마이크 버튼 창구: 쓸 수 있으면 바로 실행, 아니면 안내를 보이고 키보드·버튼 입력으로 돌린다. */
class MicPermissionGate internal constructor(
    val request: () -> Unit,
    val availability: MicAvailability,
    /** 마이크를 눌렀는데 쓸 수 없었던 횟수(안내를 다시 읽히는 데 쓴다). */
    internal val nudges: Int,
    internal val openSettings: () -> Unit,
    internal val settingsFailed: Boolean
)

/**
 * 마이크 권한·음성 인식 확인(소유자 결정 msg 946). **시스템 권한 창은 여기서 띄우지 않는다** — 권한은 앱을 처음 열 때
 * [PermissionPrimerGate]에서 한 번에 묻고, 그 뒤로는 거절했으면 화면에 「마이크 권한이 꺼져 있어요 [허용하기]」를 두고
 * [허용하기]는 앱 설정을 연다. 설정에서 돌아오면(onResume) 다시 확인해 안내를 거둔다.
 *
 * @param onGranted 쓸 수 있을 때 마이크를 눌렀다(듣기 시작)
 * @param onDeclined 쓸 수 없을 때 마이크를 눌렀다(키보드·버튼 입력으로 돌림)
 */
@Composable
fun rememberMicPermissionGate(onGranted: () -> Unit, onDeclined: () -> Unit): MicPermissionGate {
    val context = LocalContext.current
    val inspection = LocalInspectionMode.current
    fun check(): MicAvailability = if (inspection) {
        MicAvailability.READY
    } else {
        MicAvailability.of(context.isGranted(AppPermissions.RECORD_AUDIO), SpeechRecognizer.isRecognitionAvailable(context))
    }
    var availability by remember { mutableStateOf(check()) }
    var nudges by remember { mutableIntStateOf(0) }
    var settingsFailed by remember { mutableStateOf(false) }
    LifecycleResumeEffect(Unit) {
        availability = check()
        if (availability != MicAvailability.READY) Log.i(VOICE_LOG_TAG, "mic not ready on screen: $availability")
        onPauseOrDispose { }
    }
    return MicPermissionGate(
        request = {
            availability = check()
            if (availability == MicAvailability.READY) {
                onGranted()
            } else {
                Log.i(VOICE_LOG_TAG, "mic tapped but not ready: $availability (no system permission dialog)")
                nudges++
                onDeclined()
            }
        },
        availability = availability,
        nudges = nudges,
        openSettings = { settingsFailed = !openAppSettings(context) },
        settingsFailed = settingsFailed
    )
}

/**
 * 마이크를 쓸 수 없을 때만 보이는 작은 안내. 권한이 꺼져 있으면 [허용하기](앱 설정), 인식기가 없으면 키보드·버튼 안내.
 * 마이크를 누를 때마다 다시 읽힌다(liveRegion).
 */
@Composable
fun MicPermissionRationale(gate: MicPermissionGate, modifier: Modifier = Modifier) {
    if (gate.availability == MicAvailability.READY) return
    // 같은 문구를 다시 알릴 때도 읽도록 순번에 따라 보이지 않는 문자를 붙인다.
    val mark = if (gate.nudges % 2 == 0) "" else ZERO_WIDTH
    Column(
        modifier = modifier.fillMaxWidth().testTag("mic-off-hint"),
        verticalArrangement = Arrangement.spacedBy(SeeSunDimens.Gap)
    ) {
        if (gate.availability == MicAvailability.RECOGNIZER_UNAVAILABLE) {
            LiveStatusText(stringResource(R.string.ui_mic_unavailable_hint) + mark)
            return@Column
        }
        LiveStatusText(
            stringResource(R.string.ui_mic_off_hint) + mark,
            spoken = stringResource(R.string.ui_mic_off_hint_spoken) + mark
        )
        SeeSunButton(
            text = stringResource(R.string.ui_mic_off_allow),
            onClick = gate.openSettings,
            kind = ButtonKind.OUTLINE,
            minHeight = SeeSunDimens.SmallButtonMinHeight,
            textStyle = SeeSunTheme.typography.buttonCompact
        )
        if (gate.settingsFailed) LiveStatusText(stringResource(R.string.ui_mic_off_settings_failed))
    }
}

private const val ZERO_WIDTH = "​"
