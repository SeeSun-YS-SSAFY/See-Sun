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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
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
import kr.seesun.core.voice.recognition.AutoListenOutcome
import kr.seesun.core.voice.recognition.ListenOptions
import kr.seesun.core.voice.recognition.PreRollAudio
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

    /** 시선 비서(화면에 그려질 때 [LinkAssistant]가 붙인다). 없으면 화면 명령만. */
    internal var assistant: AssistantHost? = null

    /**
     * 들은 말 처리(처리 순서 §4.6): 비서 0·1단계(안전·묻던 것의 답·「시선아」) → 화면 명령 → 비서 3·4단계(어디서나 명령·전역 의도).
     * 기기 시험이 받아쓴 말을 넣을 때도 이 길로 온다.
     */
    internal fun handle(candidates: List<String>): Boolean {
        val host = assistant
        if (host?.before(candidates, this) == true) return true
        if (handler(candidates)) return true
        return host?.after(candidates, this) == true
    }

    fun onMicClick() {
        when (_state.value.mic) {
            MicState.LISTENING -> {
                job?.cancel()
                _state.update { it.copy(mic = MicState.IDLE) }
            }

            MicState.PROCESSING -> Unit

            MicState.IDLE, MicState.STANDBY -> listen()
        }
    }

    fun onPermissionDenied() = _state.update { it.copy(error = FieldError.PermissionDenied, suggestButtons = true) }

    /**
     * 음성 자동 인식으로 한 번 듣는다([AutoListenEffect]가 부른다). 수동 듣기와 달리 무음·못 알아들음·화면에 맞지 않는 말에
     * 오류 문구·실패 횟수를 남기지 않는다. 이미 듣는 중이면 아무것도 하지 않는다.
     */
    suspend fun listenAuto(preRoll: PreRollAudio? = null): AutoListenOutcome {
        if (_state.value.mic != MicState.IDLE) return AutoListenOutcome.USER_STOPPED
        onBeforeListen()
        _state.update { it.copy(error = null) }
        return autoListenOnce(
            scope = scope,
            speech = speech,
            register = { job = it },
            setMic = { mic -> _state.update { it.copy(mic = mic.toMicState()) } },
            accept = { candidates ->
                handle(candidates).also { ok ->
                    if (ok) {
                        tracker.onSuccess()
                        _state.update { it.copy(error = null, suggestButtons = false) }
                    }
                }
            },
            preRoll = preRoll
        )
    }

    fun clearError() = _state.update { it.copy(error = null) }

    private fun listen() {
        onBeforeListen()
        _state.update { it.copy(mic = MicState.LISTENING, error = null) }
        job = scope.launch {
            speech.listen(ListenOptions(manual = true)).collect { event ->
                when (event) {
                    is RecognitionEvent.SpeechEnded -> _state.update { it.copy(mic = MicState.PROCESSING) }

                    is RecognitionEvent.Final -> if (handle(event.candidates)) {
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
    val press = rememberDirectStart(gate.request)
    VoiceTriggers(controller, talkBackEnabled, speak, controller::listenAuto, press)
    LinkAssistant(controller)
    val mic = displayedMic(state.mic)
    Column(
        modifier = modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(SeeSunDimens.Gap)
    ) {
        MicButton(
            state = mic,
            onClick = press,
            size = adaptiveMicSize(SeeSunDimens.MicReserve + CHOICE_RESERVE),
            showState = mic != MicState.IDLE
        )
        ChoiceResult(label = resultLabel, value = result)
        AssistantResultStrip(controller)
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
 * @param idleText 쉬는 동안 버튼 글자(기본 「말로 고르기」, 메인은 「시선에게 말하기」)
 */
@Composable
fun VoiceCommandButton(
    controller: VoiceCommandController,
    hint: String,
    talkBackEnabled: Boolean,
    speak: (String) -> Unit,
    modifier: Modifier = Modifier,
    textStyle: TextStyle = SeeSunTheme.typography.button,
    idleText: String? = null
) {
    val state by controller.state.collectAsStateWithLifecycle()
    val gate = rememberMicPermissionGate(onGranted = controller::onMicClick, onDeclined = controller::onPermissionDenied)
    val press = rememberDirectStart(gate.request)
    VoiceTriggers(controller, talkBackEnabled, speak, controller::listenAuto, press)
    LinkAssistant(controller)
    val mic = displayedMic(state.mic)
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(SeeSunDimens.Gap)) {
        // 결과 띠는 마이크 버튼 바로 위(포커스 순서: 본문 → 결과 띠 → 마이크, §4.5).
        AssistantResultStrip(controller)
        SeeSunButton(
            text = when (mic) {
                MicState.IDLE -> idleText ?: stringResource(R.string.ui_voice_command_idle)
                MicState.STANDBY -> stringResource(R.string.ui_voice_command_standby)
                MicState.LISTENING -> stringResource(R.string.ui_voice_command_listening)
                MicState.PROCESSING -> stringResource(R.string.ui_voice_command_processing)
            },
            kind = if (mic == MicState.LISTENING || mic == MicState.STANDBY) ButtonKind.PRIMARY else ButtonKind.OUTLINE,
            iconRes = kr.seesun.core.designsystem.R.drawable.ic_mic,
            enabled = state.mic != MicState.PROCESSING,
            supportingText = when {
                state.mic == MicState.PROCESSING -> stringResource(R.string.ui_voice_command_wait)

                // 마이크를 쓸 수 없으면 버튼에 상태를 글자로(반 폭 버튼 옆에 긴 안내를 늘 두면 화면이 넘친다). 누르면 아래 안내.
                gate.availability == MicAvailability.PERMISSION_OFF -> stringResource(R.string.ui_mic_off_short)

                gate.availability == MicAvailability.RECOGNIZER_UNAVAILABLE -> stringResource(R.string.ui_mic_unavailable_short)

                else -> null
            },
            onClick = press,
            textStyle = textStyle
        )
        if (gate.nudges > 0) MicPermissionRationale(gate)
        CommandStatus(state, hint, talkBackEnabled, speak)
    }
}

/**
 * 화면 명령을 처리할 음성 명령 컨트롤러(보이는 버튼 없이 쓰는 화면용, 운동 재생 화면). app이 음성 모듈을 내려 주지 않으면 null.
 * [handler]는 마지막에 넘긴 것을 쓴다(화면 상태가 바뀌어도 컨트롤러를 새로 만들지 않음).
 */
@Composable
fun rememberScreenCommands(handler: (List<String>) -> Boolean): VoiceCommandController? {
    val speech = LocalSpeechInput.current ?: return null
    val speaker = LocalGuidanceSpeaker.current
    val scope = rememberCoroutineScope()
    val latest by rememberUpdatedState(handler)
    return remember(speech, scope, speaker) {
        VoiceCommandController(scope, speech, onBeforeListen = { speaker?.stop() }) { latest(it) }
    }
}

/**
 * 보이는 마이크 버튼 없이 붙이는 화면 명령 듣기(운동 재생 화면, 화면 간소화 설계 v1 §6.4 「언제 듣나」).
 * 이 조각이 화면에 있는 동안만 듣는다 — 재생 화면은 **멈춘 동안에만** 둔다(안내 음성이 나오는 동안에는 듣지 않음, 반이중).
 * 설정의 음성 자동 인식(질문 뒤·말하면 듣기)을 그대로 따르고(TalkBack 켜짐이면 자동으로 듣지 않음, A9),
 * [startNow]가 true가 되면 바로 듣기를 시작하고 [onStarted]를 부른다([⋯ 더 보기]의 [말로 하기], 한 번만).
 * 듣는 중이면 「듣고 있어요」 한 줄을, 알아듣지 못하면 오류 안내를 보인다(liveRegion + 앱 음성).
 *
 * @param key 바뀌면 자동 듣기 한도를 새로 받는다(새로 멈출 때마다)
 */
@Composable
fun ScreenCommandListener(
    controller: VoiceCommandController,
    key: Any?,
    hint: String,
    talkBackEnabled: Boolean,
    speak: (String) -> Unit,
    startNow: Boolean = false,
    onStarted: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val state by controller.state.collectAsStateWithLifecycle()
    val gate = rememberMicPermissionGate(onGranted = controller::onMicClick, onDeclined = controller::onPermissionDenied)
    val press = rememberDirectStart(gate.request)
    VoiceTriggers(controller to key, talkBackEnabled, speak, controller::listenAuto, press)
    LinkAssistant(controller)
    val latestPress by rememberUpdatedState(press)
    val latestStarted by rememberUpdatedState(onStarted)
    LaunchedEffect(startNow) {
        if (!startNow) return@LaunchedEffect
        if (controller.state.value.mic != MicState.LISTENING) latestPress()
        latestStarted()
    }
    val mic = displayedMic(state.mic)
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(SeeSunDimens.Gap)) {
        val listening = when (mic) {
            MicState.LISTENING -> stringResource(R.string.ui_screen_command_listening, hint)
            MicState.PROCESSING -> stringResource(R.string.ui_voice_command_processing)
            else -> ""
        }
        LiveStatusText(listening)
        AssistantResultStrip(controller)
        if (gate.nudges > 0) MicPermissionRationale(gate)
        CommandStatus(state, hint, talkBackEnabled, speak)
    }
}

/** 음성 명령 컨트롤러를 시선 비서에 잇는다(화면에 있는 동안). 받아쓴 말 주입(기기 시험)도 이 컨트롤러로 온다. */
@Composable
internal fun LinkAssistant(controller: VoiceCommandController) {
    val host = LocalAssistant.current
    DisposableEffect(controller, host) {
        controller.assistant = host
        onDispose { if (controller.assistant === host) controller.assistant = null }
    }
    AttachAssistant(controller, controller::handle)
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
