package kr.seesun.feature.exercise.safety

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kr.seesun.core.a11y.LiveAnnouncement
import kr.seesun.core.auth.security.SecretStore
import kr.seesun.core.data.repository.AccountScopedStore
import kr.seesun.core.designsystem.component.BodyText
import kr.seesun.core.designsystem.component.BottomActions
import kr.seesun.core.designsystem.component.ButtonKind
import kr.seesun.core.designsystem.component.ScreenScaffold
import kr.seesun.core.voice.tts.Speaker
import kr.seesun.feature.exercise.session.TalkBackState
import kr.seesun.feature.exercise.ui.ActionButton
import kr.seesun.feature.exercise.ui.SectionHeading

/**
 * 운동 첫 실행 안전 안내(법률 검토 구현 보완 T-08, 검토서 §12 R-23, 02 EX-12).
 *
 * 1. 공간 안전·즉시 멈추는 방법을 말로 안내한다(TalkBack 꺼짐: 기기 음성, 켜짐: 화면 글자·알림을 TalkBack이 읽음).
 * 2. PAR-Q+ 요약 문답 7개(예/아니요). **응답은 기기에만** 암호화해 두고 서버로 보내지 않는다.
 *    "예"가 하나라도 있으면 전문가 상담과 가벼운 운동·보조자 동행을 권한다(운동을 막지는 않음 — 사용자 결정).
 * 3. 보조자가 필요한 운동은 목록·재생 화면에 따로 표시한다(GuideSections.needsAssistant).
 */
object SafetyContent {
    const val VERSION = 1

    const val NOTICE = "운동 전 안전 안내예요. 주변에 부딪힐 가구나 물건이 없는지, 바닥이 미끄럽지 않은지 확인해 주세요. " +
        "균형이 걱정되면 의자나 벽을 손 닿는 곳에 두세요. 어지럽거나, 가슴이 아프거나, 숨이 너무 차면 바로 멈추고 쉬세요. " +
        "멈추는 방법: 재생 화면의 일시정지 버튼을 누르거나, 이어폰의 재생 버튼을 한 번 누르거나, 뒤로 가기를 하세요. 바로 멈춰요."

    /** PAR-Q+(2023) 일반 건강 문항 7개를 쉬운 말로 줄였다. 의료 판정이 아니라 상담 권유용이다. */
    val QUESTIONS = listOf(
        "의사에게 심장 질환이나 고혈압이 있다는 말을 들은 적이 있나요?",
        "쉬고 있을 때, 일상생활 중, 또는 운동할 때 가슴 통증을 느낀 적이 있나요?",
        "지난 1년 사이 어지러워서 균형을 잃거나 정신을 잃은 적이 있나요? 숨을 몰아쉬어서 어지러웠던 경우는 아니요로 답해 주세요.",
        "심장 질환이나 고혈압 말고 다른 만성 질환을 진단받은 적이 있나요?",
        "만성 질환 때문에 처방받은 약을 먹고 있나요?",
        "운동하면 더 나빠질 수 있는 뼈, 관절, 근육 문제가 지금 있거나 지난 1년 안에 있었나요?",
        "의사가 의료진의 관리 아래에서만 운동하라고 한 적이 있나요?"
    )

    const val ALL_NO = "모두 아니요예요. 무리하지 말고 천천히 시작하세요. 운동 중 이상하면 바로 멈추세요."
    const val SOME_YES = "예라고 답한 문항이 있어요. 운동을 늘리기 전에 의사나 운동 전문가와 상담하세요. " +
        "오늘 운동한다면 유연성·균형 같은 가벼운 운동부터, 가능하면 보조자와 함께 하세요."
}

@Serializable
data class SafetyRecord(val version: Int, val answers: List<Boolean>, val completedAtMs: Long)

/** 안전 문답 기록(기기에만, Keystore 암호화 저장소). 서버·백업으로 나가지 않는다. 로그아웃·탈퇴 때 지운다. */
@Singleton
class SafetyStore @Inject constructor(private val secrets: SecretStore, private val json: Json) : AccountScopedStore {
    fun load(): SafetyRecord? = secrets.get(KEY)?.let { runCatching { json.decodeFromString(SafetyRecord.serializer(), it) }.getOrNull() }
        ?.takeIf { it.version == SafetyContent.VERSION }

    fun isDone(): Boolean = load() != null

    fun save(answers: List<Boolean>) = secrets.put(
        KEY,
        json.encodeToString(SafetyRecord.serializer(), SafetyRecord(SafetyContent.VERSION, answers, System.currentTimeMillis()))
    )

    fun clear() = secrets.put(KEY, null)

    override fun clearOnSignOut() = clear()

    private companion object {
        const val KEY = "exercise.safety.v1"
    }
}

enum class SafetyStep { NOTICE, QUESTIONS, RESULT }

data class SafetyUi(
    val step: SafetyStep = SafetyStep.NOTICE,
    val index: Int = 0,
    val answers: List<Boolean> = emptyList(),
    val notice: String = ""
)

@HiltViewModel
class SafetyViewModel @Inject constructor(
    private val store: SafetyStore,
    private val speaker: Speaker,
    private val talkBack: TalkBackState
) : ViewModel() {
    private val _done = MutableStateFlow(store.isDone())
    val done: StateFlow<Boolean> = _done.asStateFlow()
    private val _ui = MutableStateFlow(SafetyUi())
    val ui: StateFlow<SafetyUi> = _ui.asStateFlow()

    /** 화면이 뜰 때 안내를 말로 들려준다. TalkBack이 켜져 있으면 TalkBack이 읽도록 알림으로 넘긴다(A9). */
    fun speakNotice() {
        if (!talkBack.isOn()) speaker.speak(SafetyContent.NOTICE)
        _ui.update { it.copy(notice = SafetyContent.NOTICE) }
    }

    fun startQuestions() {
        speaker.stop()
        _ui.update { it.copy(step = SafetyStep.QUESTIONS, index = 0, answers = emptyList()) }
        say(SafetyContent.QUESTIONS[0])
    }

    fun answer(yes: Boolean) {
        val s = _ui.value
        if (s.step != SafetyStep.QUESTIONS) return
        val answers = s.answers + yes
        if (answers.size == SafetyContent.QUESTIONS.size) {
            _ui.update { it.copy(step = SafetyStep.RESULT, answers = answers) }
            say(if (answers.any { it }) SafetyContent.SOME_YES else SafetyContent.ALL_NO)
        } else {
            _ui.update { it.copy(index = answers.size, answers = answers) }
            say(SafetyContent.QUESTIONS[answers.size])
        }
    }

    fun previousQuestion() {
        val s = _ui.value
        if (s.step != SafetyStep.QUESTIONS || s.index == 0) return
        _ui.update { it.copy(index = s.index - 1, answers = s.answers.dropLast(1)) }
    }

    fun finish() {
        speaker.stop()
        store.save(_ui.value.answers)
        _done.value = true
    }

    /** 홈의 [운동 안전 안내 다시 보기]: 처음부터 다시. */
    fun restart() {
        _ui.value = SafetyUi()
        speakNotice()
    }

    private fun say(text: String) {
        if (!talkBack.isOn()) speaker.speak(text)
    }

    override fun onCleared() = speaker.stop()
}

/**
 * 운동을 처음 시작할 때 재생 화면 앞에 끼우는 안전 안내. 끝나면 [content]를 그린다.
 * @param onDone 안전 안내를 마친 순간 한 번(재생 화면이 그때 자동 재생 여부를 정한다)
 */
@Composable
fun SafetyGate(viewModel: SafetyViewModel, onBack: () -> Unit, onDone: () -> Unit, content: @Composable () -> Unit) {
    val done by viewModel.done.collectAsStateWithLifecycle()
    if (done) {
        content()
        return
    }
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { viewModel.speakNotice() }
    SafetyScreen(ui, onBack, viewModel::startQuestions, viewModel::answer, viewModel::previousQuestion) {
        viewModel.finish()
        onDone()
    }
}

/** 홈에서 다시 보는 안전 안내(독립 화면). */
@Composable
fun SafetyReviewRoute(viewModel: SafetyViewModel, onClose: () -> Unit) {
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { viewModel.restart() }
    SafetyScreen(ui, onClose, viewModel::startQuestions, viewModel::answer, viewModel::previousQuestion) {
        viewModel.finish()
        onClose()
    }
}

@Composable
fun SafetyScreen(
    ui: SafetyUi,
    onBack: () -> Unit,
    onStartQuestions: () -> Unit,
    onAnswer: (Boolean) -> Unit,
    onPrevious: () -> Unit,
    onFinish: () -> Unit
) {
    val title = when (ui.step) {
        SafetyStep.NOTICE -> "운동 안전 안내"
        SafetyStep.QUESTIONS -> "건강 확인 ${ui.index + 1}/${SafetyContent.QUESTIONS.size}"
        SafetyStep.RESULT -> "건강 확인 결과"
    }
    ScreenScaffold(title = title, onBack = onBack) {
        when (ui.step) {
            SafetyStep.NOTICE -> {
                BodyText(SafetyContent.NOTICE)
                BodyText("다음으로 건강 확인 질문 7개에 예, 아니요로 답해 주세요. 답은 이 휴대폰에만 저장하고 서버로 보내지 않아요.")
                BottomActions { ActionButton(text = "건강 확인 시작", onClick = onStartQuestions) }
            }

            SafetyStep.QUESTIONS -> {
                BodyText(SafetyContent.QUESTIONS[ui.index], emphasis = true)
                BottomActions {
                    ActionButton(text = "예", onClick = { onAnswer(true) })
                    ActionButton(text = "아니요", kind = ButtonKind.OUTLINE, onClick = { onAnswer(false) })
                    if (ui.index > 0) ActionButton(text = "이전 질문", kind = ButtonKind.OUTLINE, onClick = onPrevious)
                }
            }

            SafetyStep.RESULT -> {
                BodyText(if (ui.answers.any { it }) SafetyContent.SOME_YES else SafetyContent.ALL_NO, emphasis = true)
                SectionHeading("보조자가 필요한 운동")
                BodyText("조깅처럼 움직이며 하는 운동은 목록과 재생 화면에 '보조자 필요'로 표시돼요. 꼭 보조자와 함께 하세요.")
                BottomActions { ActionButton(text = "확인하고 운동하기", onClick = onFinish) }
            }
        }
        LiveAnnouncement(if (ui.step == SafetyStep.NOTICE) ui.notice else "")
    }
}

/** 로그아웃·탈퇴의 기기 데이터 삭제에 안전 문답 기록을 포함한다(다음 로그인 사람에게 넘어가지 않게). */
@Module
@InstallIn(SingletonComponent::class)
abstract class SafetyStoreModule {
    @Binds
    @IntoSet
    abstract fun accountScoped(impl: SafetyStore): AccountScopedStore
}
