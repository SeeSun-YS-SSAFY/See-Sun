package kr.seesun.core.ui

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kr.seesun.core.designsystem.component.InputState
import kr.seesun.core.designsystem.component.MicState
import kr.seesun.core.voice.parse.Answer
import kr.seesun.core.voice.parse.AnswerParser
import kr.seesun.core.voice.recognition.AutoListenOutcome
import kr.seesun.core.voice.recognition.ListenOptions
import kr.seesun.core.voice.recognition.PreRollAudio
import kr.seesun.core.voice.recognition.RecognitionError
import kr.seesun.core.voice.recognition.RecognitionEvent
import kr.seesun.core.voice.recognition.RecognitionFailureTracker
import kr.seesun.core.voice.recognition.SpeechInput

/** 입력 칸 오류. 문구는 화면(문자열 리소스)이 고른다. */
sealed interface FieldError {
    /** 음성은 들었지만 값으로 해석하지 못했다. [heard]는 인식된 말(되읽어 주면 무엇이 틀렸는지 알 수 있다). */
    data class NotUnderstood(val heard: String) : FieldError

    /** 키보드로 넣은 값이 규칙에 맞지 않는다. */
    data object Invalid : FieldError

    /** 음성 인식 자체가 실패했다(무음·네트워크 등). 문구는 core:voice의 안내를 쓴다. */
    data class Recognition(val error: RecognitionError) : FieldError

    /** 마이크 권한이 없다. 키보드로 입력할 수 있다고 알린다. */
    data object PermissionDenied : FieldError
}

/**
 * 음성·키보드 입력 칸 하나의 상태(02 ONB-02 4상태 + 확인 대기).
 *
 * @property text 결과칸 글자(인식·편집 중인 값, 해석된 값은 표준 형식)
 * @property pending 음성으로 알아들어 "맞으세요?"를 묻는 중인 값(COM-05). null이면 확인 대기 아님
 * @property repeatCount "다시 들려줘"를 받을 때마다 늘어난다(화면이 확인 문구를 다시 낭독)
 * @property suggestKeyboard 인식 실패가 이어져 버튼·키보드 입력을 권할 때(COM-14)
 * @property autoListening 음성 자동 인식으로 듣는 중(사용자가 누르지 않음). 이때도 [다음]은 누를 수 있고, 누르면 듣기를 끊는다
 */
data class VoiceFieldState(
    val text: String = "",
    val inputState: InputState = InputState.WAITING,
    val mic: MicState = MicState.IDLE,
    val pending: String? = null,
    val error: FieldError? = null,
    val repeatCount: Int = 0,
    val suggestKeyboard: Boolean = false,
    val autoListening: Boolean = false
) {
    /**
     * 확정된 값이 있어 [다음]을 누를 수 있는가(편집 중이면 [다음]이 검사 후 확정한다).
     * 자동으로 듣는 중이면 [다음]을 막지 않는다 — 누르면 듣기를 끊고 지금 값으로 넘어간다(값이 이미 있는 화면에서 30초씩 막히지 않게).
     */
    val canProceed: Boolean
        get() = pending == null && (mic == MicState.IDLE || autoListening) && (inputState == InputState.EDITING || text.isNotBlank())
}

/**
 * 입력 칸 하나의 음성·키보드 흐름(화면과 무관한 상태 기계 — ViewModel이 들고 단위 테스트한다).
 *
 * - 음성: [onMicClick] → 듣기 → 후보 중 해석되는 첫 값을 [VoiceFieldState.pending]에 두고 되읽기 확인을 기다린다.
 *   확인 중에 다시 말하면 "네/아니요/수정/다시"로 답할 수 있고(COM-06), 새 값을 말하면 그 값으로 바꾼다.
 * - 키보드: [onEditRequest] → 편집 → [commitTyped]가 같은 해석기로 검사한 뒤 확정한다(사용자가 직접 쓴 값이라 되읽기 생략).
 * - 확정되면 [onCommit]에 표준 형식 값을 넘긴다.
 *
 * @param parser 말·글을 표준 형식 값으로 바꾼다. 못 바꾸면 null
 * @param onBeforeListen 듣기 직전(안내 음성 중단 등 — 마이크가 앱 음성을 듣지 않도록)
 */
class VoiceFieldController(
    private val scope: CoroutineScope,
    private val speech: SpeechInput,
    private val parser: (String) -> String?,
    private val onBeforeListen: () -> Unit = {},
    private val onCommit: (String) -> Unit
) {
    private val _state = MutableStateFlow(VoiceFieldState())
    val state: StateFlow<VoiceFieldState> = _state.asStateFlow()

    private val tracker = RecognitionFailureTracker()
    private var listening: Job? = null

    /** 저장된 값으로 칸을 채운다(이전 단계로 돌아오거나 수정 화면에서 현재 값을 보여 줄 때). */
    fun setValue(value: String?) {
        cancelListening()
        _state.value = VoiceFieldState(
            text = value.orEmpty(),
            inputState = if (value.isNullOrEmpty()) InputState.WAITING else InputState.RECOGNIZED
        )
    }

    fun onMicClick() {
        when (_state.value.mic) {
            MicState.LISTENING -> {
                cancelListening()
                _state.update { it.copy(mic = MicState.IDLE, inputState = restingState(it.text), autoListening = false) }
            }

            MicState.PROCESSING -> Unit

            MicState.IDLE, MicState.STANDBY -> startListening()
        }
    }

    fun onPermissionDenied() {
        _state.update { it.copy(error = FieldError.PermissionDenied, suggestKeyboard = true, mic = MicState.IDLE) }
    }

    /**
     * 키보드 입력 시작. 이미 확정된 값이 있으면 칸을 비우고 새로 받는다 — 화면을 보지 않는 사용자가 기존 값 뒤에
     * 이어 쓰면("1990-05-01" + "20150101") 해석기가 앞의 옛 값을 읽어 조용히 다시 저장하던 문제를 막는다.
     * 지금 값은 화면 안내로 먼저 읽어 준다.
     */
    fun onEditRequest() {
        cancelListening()
        _state.update {
            val fresh = it.inputState == InputState.RECOGNIZED && it.pending == null
            it.copy(
                text = if (fresh) "" else it.text,
                inputState = InputState.EDITING,
                mic = MicState.IDLE,
                pending = null,
                error = null,
                autoListening = false
            )
        }
    }

    fun onTextChange(text: String) {
        _state.update { it.copy(text = text, error = null) }
    }

    /**
     * 키보드로 쓴 값(또는 이미 확정된 값)을 검사해 확정한다. [다음] 버튼과 키보드 완료 키가 부른다.
     * @return 확정했으면 true
     */
    fun commitTyped(): Boolean {
        if (_state.value.autoListening) {
            cancelListening()
            _state.update { it.copy(mic = MicState.IDLE, autoListening = false, inputState = restingState(it.text)) }
        }
        val current = _state.value
        if (current.pending != null || current.mic != MicState.IDLE) return false
        val value = parser(current.text.trim())
        if (value == null) {
            _state.update { it.copy(error = FieldError.Invalid) }
            return false
        }
        _state.update { it.copy(text = value, inputState = InputState.RECOGNIZED, error = null) }
        onCommit(value)
        return true
    }

    /** 확인 화면의 [확인]. */
    fun confirm() {
        val value = _state.value.pending ?: return
        _state.update { it.copy(pending = null, inputState = InputState.RECOGNIZED) }
        onCommit(value)
    }

    /** 확인 화면의 [수정]: 값을 비우고 다시 말하거나 키보드로 쓰게 한다. */
    fun reject() {
        _state.update { it.copy(pending = null, text = "", inputState = InputState.WAITING) }
    }

    /**
     * 음성 자동 인식으로 한 번 듣는다([AutoListenEffect]가 부른다). 되읽기 확인 중이면 "네/아니요/다시"와 새 값을, 아니면 이 칸에
     * 맞는 값만 받는다. 무음·못 알아들음·맞지 않는 말에는 오류 문구·실패 횟수를 남기지 않는다. 편집 중·듣는 중이면 듣지 않는다.
     */
    suspend fun listenAuto(preRoll: PreRollAudio? = null): AutoListenOutcome {
        val now = _state.value
        if (now.mic != MicState.IDLE || now.inputState == InputState.EDITING) return AutoListenOutcome.USER_STOPPED
        onBeforeListen()
        return autoListenOnce(
            scope = scope,
            speech = speech,
            register = { listening = it },
            setMic = { mic ->
                _state.update {
                    when (mic) {
                        AutoMic.LISTENING ->
                            it.copy(mic = MicState.LISTENING, inputState = InputState.RECORDING, error = null, autoListening = true)

                        AutoMic.PROCESSING -> it.copy(mic = MicState.PROCESSING)

                        AutoMic.IDLE -> if (it.mic == MicState.IDLE && !it.autoListening) {
                            it
                        } else {
                            it.copy(mic = MicState.IDLE, inputState = restingState(it.text), autoListening = false)
                        }
                    }
                }
            },
            accept = ::acceptAuto,
            preRoll = preRoll
        )
    }

    /** 자동 듣기 결과: 화면에 쓸 수 있는 말이면 수동 듣기와 같이 처리하고 true. */
    private fun acceptAuto(candidates: List<String>): Boolean {
        val answer = if (_state.value.pending != null) candidates.firstNotNullOfOrNull { AnswerParser.parse(it) } else null
        val usable = answer != null || candidates.any { parser(it.trim()) != null }
        if (usable) onFinal(candidates)
        return usable
    }

    private fun startListening() {
        onBeforeListen()
        _state.update { it.copy(mic = MicState.LISTENING, inputState = InputState.RECORDING, error = null) }
        listening = scope.launch {
            speech.listen(ListenOptions(manual = true)).collect { event ->
                when (event) {
                    is RecognitionEvent.SpeechEnded -> _state.update { it.copy(mic = MicState.PROCESSING) }
                    is RecognitionEvent.Final -> onFinal(event.candidates)
                    is RecognitionEvent.Failed -> onFailure(FieldError.Recognition(event.error))
                    else -> Unit
                }
            }
            // 결과 없이 끝나면(취소 등) 대기로 돌린다.
            _state.update { if (it.mic != MicState.IDLE) it.copy(mic = MicState.IDLE, inputState = restingState(it.text)) else it }
        }
    }

    private fun onFinal(candidates: List<String>) {
        if (_state.value.pending != null) {
            when (candidates.firstNotNullOfOrNull { AnswerParser.parse(it) }) {
                Answer.YES -> return idle().also { confirm() }
                Answer.NO, Answer.EDIT -> return idle().also { reject() }
                Answer.REPEAT -> return _state.update { it.copy(mic = MicState.IDLE, repeatCount = it.repeatCount + 1) }
                null -> Unit // 답이 아니면 새 값을 말한 것으로 본다.
            }
        }
        val value = candidates.firstNotNullOfOrNull { parser(it.trim()) }
        if (value == null) {
            onFailure(FieldError.NotUnderstood(candidates.firstOrNull().orEmpty()))
        } else {
            tracker.onSuccess()
            _state.update {
                it.copy(
                    text = value,
                    pending = value,
                    inputState = InputState.RECOGNIZED,
                    mic = MicState.IDLE,
                    error = null,
                    suggestKeyboard = false
                )
            }
        }
    }

    private fun idle() = _state.update { it.copy(mic = MicState.IDLE) }

    private fun onFailure(error: FieldError) {
        val suggest = tracker.onFailure()
        _state.update {
            it.copy(
                mic = MicState.IDLE,
                inputState = restingState(it.text.takeIf { _ -> it.pending != null }.orEmpty()),
                text = if (it.pending != null) it.text else "",
                error = error,
                suggestKeyboard = it.suggestKeyboard || suggest
            )
        }
    }

    private fun cancelListening() {
        listening?.cancel()
        listening = null
    }

    private fun restingState(text: String) = if (text.isEmpty()) InputState.WAITING else InputState.RECOGNIZED
}
