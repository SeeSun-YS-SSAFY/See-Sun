package kr.seesun.core.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kr.seesun.core.a11y.politeLiveRegion
import kr.seesun.core.designsystem.component.ButtonKind
import kr.seesun.core.designsystem.component.LiveStatusText
import kr.seesun.core.designsystem.component.MicButton
import kr.seesun.core.designsystem.component.MicState
import kr.seesun.core.designsystem.component.SeeSunButton
import kr.seesun.core.designsystem.component.adaptiveMicSize
import kr.seesun.core.designsystem.theme.SeeSunDimens
import kr.seesun.core.designsystem.theme.SeeSunTheme
import kr.seesun.core.voice.recognition.RecognitionError
import kr.seesun.core.voice.recognition.RecognitionEvent
import kr.seesun.core.voice.recognition.RecognitionFailureTracker
import kr.seesun.core.voice.recognition.SpeechInput

/** 음성 명령(선택 화면용) 상태. */
data class VoiceCommandState(val mic: MicState = MicState.IDLE, val error: FieldError? = null, val suggestButtons: Boolean = false)

/**
 * 선택 화면(성별·질환·알레르기·목표·동의)의 음성 명령. 들은 말을 [handler]가 처리하면 끝, 못 하면 들은 말과 함께 오류.
 * 선택 결과는 화면의 선택 상태(글자·체크·liveRegion)로 알리고, 단계 끝 확인 화면에서 한 번에 되읽는다.
 *
 * @param handler 인식 후보 목록을 받아 처리했으면 true
 */
class VoiceCommandController(
    private val scope: CoroutineScope,
    private val speech: SpeechInput,
    private val onBeforeListen: () -> Unit = {},
    private val handler: (List<String>) -> Boolean
) {
    private val _state = MutableStateFlow(VoiceCommandState())
    val state: StateFlow<VoiceCommandState> = _state.asStateFlow()
    private val tracker = RecognitionFailureTracker()
    private var job: Job? = null

    fun onMicClick() {
        when (_state.value.mic) {
            MicState.LISTENING -> {
                job?.cancel()
                _state.update { it.copy(mic = MicState.IDLE) }
            }

            MicState.PROCESSING -> Unit

            MicState.IDLE -> listen()
        }
    }

    fun onPermissionDenied() = _state.update { it.copy(error = FieldError.PermissionDenied, suggestButtons = true) }

    fun clearError() = _state.update { it.copy(error = null) }

    private fun listen() {
        onBeforeListen()
        _state.update { it.copy(mic = MicState.LISTENING, error = null) }
        job = scope.launch {
            speech.listen().collect { event ->
                when (event) {
                    is RecognitionEvent.SpeechEnded -> _state.update { it.copy(mic = MicState.PROCESSING) }

                    is RecognitionEvent.Final -> if (handler(event.candidates)) {
                        tracker.onSuccess()
                        _state.update { it.copy(mic = MicState.IDLE, error = null, suggestButtons = false) }
                    } else {
                        fail(FieldError.NotUnderstood(event.best))
                    }

                    is RecognitionEvent.Failed -> fail(FieldError.Recognition(event.error))

                    else -> Unit
                }
            }
            _state.update { if (it.mic != MicState.IDLE) it.copy(mic = MicState.IDLE) else it }
        }
    }

    private fun fail(error: FieldError) {
        val suggest = tracker.onFailure()
        _state.update { it.copy(mic = MicState.IDLE, error = error, suggestButtons = it.suggestButtons || suggest) }
    }
}

/**
 * 선택 화면의 큰 마이크(Figma 성별 1031:2910~2919): 질문 아래 마이크 188dp + 결과 밑줄([result]). 말이 어려우면 화면의 선택 버튼으로 고른다.
 * 결과는 화면의 선택 상태가 바뀌면 liveRegion으로 읽힌다.
 */
@Composable
fun VoiceCommandMic(
    controller: VoiceCommandController,
    hint: String,
    talkBackEnabled: Boolean,
    speak: (String) -> Unit,
    result: String,
    resultLabel: String,
    modifier: Modifier = Modifier
) {
    val state by controller.state.collectAsStateWithLifecycle()
    val gate = rememberMicPermissionGate(onGranted = controller::onMicClick, onDeclined = controller::onPermissionDenied)
    Column(
        modifier = modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(SeeSunDimens.Gap)
    ) {
        MicButton(
            state = state.mic,
            onClick = gate.request,
            size = adaptiveMicSize(SeeSunDimens.MicReserve + CHOICE_RESERVE),
            showState = state.mic != MicState.IDLE
        )
        ChoiceResult(label = resultLabel, value = result)
        MicPermissionRationale(gate)
        CommandStatus(state, hint, talkBackEnabled, speak)
    }
}

/** 고른 값 한 줄(가운데 큰 글자 + 흰 밑줄, Figma 결과 「남자」). */
@Composable
private fun ChoiceResult(label: String, value: String) {
    val colors = SeeSunTheme.colors
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = value,
            style = SeeSunTheme.typography.result,
            color = colors.onBackground,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = RESULT_MIN_HEIGHT)
                .politeLiveRegion()
                .semantics { contentDescription = "$label, ${value.ifEmpty { NOT_CHOSEN }}" }
        )
        Box(Modifier.fillMaxWidth().height(UNDERLINE).background(colors.onBackground, RoundedCornerShape(2.dp)))
    }
}

private const val NOT_CHOSEN = "고르지 않음"
private val RESULT_MIN_HEIGHT = 48.dp

/** 성별처럼 마이크 아래 선택 버튼이 더 있는 화면에서 마이크 높이에서 더 빼는 값. */
private val CHOICE_RESERVE = 80.dp
private val UNDERLINE = 3.dp

/**
 * 선택 화면의 [말로 고르기] 버튼(큰 마이크 대신 목록 위에 두는 보통 버튼). 상태를 글자로 보여 주고 오류는 liveRegion으로 알린다.
 *
 * @param hint 알아듣지 못했을 때 덧붙일 요령(예: "당뇨, 고혈압, 없음 중에서 말씀해 주세요")
 */
@Composable
fun VoiceCommandButton(
    controller: VoiceCommandController,
    hint: String,
    talkBackEnabled: Boolean,
    speak: (String) -> Unit,
    modifier: Modifier = Modifier,
    textStyle: TextStyle = SeeSunTheme.typography.button
) {
    val state by controller.state.collectAsStateWithLifecycle()
    val gate = rememberMicPermissionGate(onGranted = controller::onMicClick, onDeclined = controller::onPermissionDenied)
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(SeeSunDimens.Gap)) {
        SeeSunButton(
            text = when (state.mic) {
                MicState.IDLE -> stringResource(R.string.ui_voice_command_idle)
                MicState.LISTENING -> stringResource(R.string.ui_voice_command_listening)
                MicState.PROCESSING -> stringResource(R.string.ui_voice_command_processing)
            },
            kind = if (state.mic == MicState.LISTENING) ButtonKind.PRIMARY else ButtonKind.OUTLINE,
            iconRes = kr.seesun.core.designsystem.R.drawable.ic_mic,
            enabled = state.mic != MicState.PROCESSING,
            supportingText = if (state.mic == MicState.PROCESSING) stringResource(R.string.ui_voice_command_wait) else null,
            onClick = gate.request,
            textStyle = textStyle
        )
        MicPermissionRationale(gate)
        CommandStatus(state, hint, talkBackEnabled, speak)
    }
}

/** 음성 명령 오류·버튼 안내(liveRegion + 앱 음성). */
@Composable
private fun CommandStatus(state: VoiceCommandState, hint: String, talkBackEnabled: Boolean, speak: (String) -> Unit) {
    val error = state.error?.let {
        when (it) {
            is FieldError.NotUnderstood -> if (it.heard.isBlank()) {
                stringResource(R.string.ui_not_understood, hint)
            } else {
                stringResource(R.string.ui_heard_not_choice, it.heard, hint)
            }

            FieldError.Invalid -> stringResource(R.string.ui_not_understood, hint)

            // 선택 화면에는 키보드 칸이 없다: 인식기를 못 쓰면 "키보드로 입력" 대신 버튼으로 고르라고 안내한다(통합 E2E BUG-09).
            is FieldError.Recognition -> when (it.error) {
                RecognitionError.LANGUAGE_UNAVAILABLE, RecognitionError.UNAVAILABLE ->
                    stringResource(R.string.ui_voice_unavailable_buttons)

                else -> it.error.message
            }

            FieldError.PermissionDenied -> stringResource(R.string.ui_mic_permission_denied_buttons)
        }
    }.orEmpty()
    val suggestion = if (state.suggestButtons &&
        state.error !is FieldError.PermissionDenied
    ) {
        stringResource(R.string.ui_suggest_buttons)
    } else {
        ""
    }
    LiveStatusText(listOf(error, suggestion).filter { it.isNotEmpty() }.joinToString(" "))
    if (error.isNotEmpty()) AutoGuidance("cmd-error:${state.error}", error, talkBackEnabled, speak)
}
