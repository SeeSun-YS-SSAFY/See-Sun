package kr.seesun.core.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.flow.StateFlow
import kr.seesun.core.a11y.politeLiveRegion
import kr.seesun.core.assistant.LocalCommand
import kr.seesun.core.assistant.ScreenMode
import kr.seesun.core.assistant.ScreenVoiceContext
import kr.seesun.core.assistant.StripButton
import kr.seesun.core.designsystem.component.ActionRow
import kr.seesun.core.designsystem.component.ActionSpec
import kr.seesun.core.designsystem.component.ButtonKind
import kr.seesun.core.designsystem.theme.SeeSunDimens
import kr.seesun.core.designsystem.theme.SeeSunTheme

/**
 * 결과 띠 한 줄(설계 §4.5 「내 말: … / 시선: …」). 다음 말이 오면 덮어쓰고 화면을 떠나면 지운다.
 * @param owner 이 말을 받은 듣기 입구(그 입구 옆에만 띠를 그린다 — liveRegion은 한 곳)
 */
data class AssistantStrip(val heard: String, val say: String?, val buttons: List<StripButton>, val owner: Any?)

/** 화면 문맥 등록(§5.2). [onSafetyStop]: 안전 표현을 들으면 부를 것(운동 재생 화면 = 멈춤). */
data class AssistantScreenEntry(val context: ScreenVoiceContext, val onSafetyStop: (() -> Unit)? = null)

/**
 * 시선 비서(app이 내려 준다, 설계 voice_assistant_design_v1). 없으면(미리보기·기능 단독 시험) 화면 명령만 쓴다.
 * 듣기 방식은 새로 만들지 않는다 — 지금의 마이크 버튼·이어폰·두 번 탭·자동 듣기가 받은 말을 여기로 넘긴다(§1 「맨 아래 받는 곳」).
 */
interface AssistantHost {
    val strip: StateFlow<AssistantStrip?>

    /** 말로 고르기 화면: 처리 순서 0~4를 한 번에(화면 명령은 낱말 목록으로 구체성 비교, §4.6). 처리했으면 true. */
    fun hearMenu(candidates: List<String>, local: List<LocalCommand>, runLocal: (Int) -> Unit, owner: Any): Boolean

    /**
     * 화면이 말을 따로 해석하는 곳에서 화면보다 먼저: 안전(0단계)·묻던 것의 답(1단계)·「시선아 …」.
     * [valueCapture]면 값 입력 칸이다 — 말 전체가 「처음으로」「그만」「취소」일 때만 더 받는다(R2).
     */
    fun before(candidates: List<String>, owner: Any, valueCapture: Boolean = false): Boolean

    /** 화면이 알아듣지 못한 말: 어디서나 명령·전역 의도(3·4단계). 처리했으면 true. */
    fun after(candidates: List<String>, owner: Any): Boolean

    /** 결과 띠 버튼(말 대신 누름). */
    fun press(button: StripButton)

    /** 화면 문맥 등록(맨 위 화면이 받는다). 돌려준 함수로 뺀다. */
    fun enter(entry: AssistantScreenEntry): () -> Unit

    /** 듣기 입구 등록(맨 위 것이 [hearText]를 받는다 — 기기 시험이 받아쓴 말을 넣는 통로). */
    fun listen(owner: Any, handler: (List<String>) -> Boolean): () -> Unit
}

val LocalAssistant = staticCompositionLocalOf<AssistantHost?> { null }

/**
 * 화면이 비서에게 「나는 이 화면」이라고 알리는 한 줄(R13: 화면 파일에는 이 한 줄만). 화면이 앞에 있는 동안(RESUMED)만 등록한다.
 * 제목·요약·예시는 [kr.seesun.core.assistant.ScreenCatalog]가 [screenId]로 찾는다.
 */
@Composable
fun AssistantScreen(screenId: String, mode: ScreenMode = ScreenMode.COMMAND, title: String? = null, onSafetyStop: (() -> Unit)? = null) {
    val host = LocalAssistant.current ?: return
    if (LocalInspectionMode.current) return
    val latestStop by rememberUpdatedState(onSafetyStop)
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(host, lifecycle, screenId, mode, title) {
        val entry = AssistantScreenEntry(ScreenVoiceContext(screenId, mode, title = title), onSafetyStop = { latestStop?.invoke() })
        var remove: (() -> Unit)? = null
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> if (remove == null) remove = host.enter(entry)

                Lifecycle.Event.ON_PAUSE -> {
                    remove?.invoke()
                    remove = null
                }

                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer)
            remove?.invoke()
        }
    }
}

/** 듣기 입구를 비서에 등록한다(화면이 앞에 있는 동안). [VoiceMenuButton]·음성 명령 버튼·화면 명령 듣기가 쓴다. */
@Composable
internal fun AttachAssistant(owner: Any, handler: (List<String>) -> Boolean) {
    val host = LocalAssistant.current ?: return
    if (LocalInspectionMode.current) return
    val latest by rememberUpdatedState(handler)
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(host, owner, lifecycle) {
        var remove: (() -> Unit)? = null
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> if (remove == null) remove = host.listen(owner) { latest(it) }

                Lifecycle.Event.ON_PAUSE -> {
                    remove?.invoke()
                    remove = null
                }

                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer)
            remove?.invoke()
        }
    }
}

/**
 * 결과 띠(§4.5): 「내 말: … / 시선: …」 한 줄 + 묻는 중이면 답 버튼(전화 걸기·이동 등, TalkBack 사용자가 말 대신 누른다).
 * polite liveRegion 한 곳으로만 읽고 포커스를 빼앗지 않는다(a11y C10·C11). [owner]가 받은 말일 때만 그린다.
 */
@Composable
fun AssistantResultStrip(owner: Any, modifier: Modifier = Modifier) {
    val host = LocalAssistant.current ?: return
    val strip by host.strip.collectAsStateWithLifecycle()
    val s = strip?.takeIf { it.owner === owner } ?: return
    val line = if (s.say.isNullOrBlank()) {
        stringResource(R.string.ui_assistant_heard_only, s.heard)
    } else {
        stringResource(R.string.ui_assistant_strip, s.heard, s.say)
    }
    // 보통 글자 크기에서는 화면을 밀어내지 않게 세 줄까지만 보인다(메인 2×2 타일은 남는 높이를 받는다). 말 전체는 앱 음성과
    // TalkBack(liveRegion 설명)이 읽고, 글자를 키우면(본문이 스크롤됨) 다 보인다.
    val lines = if (LocalDensity.current.fontScale <= 1f) STRIP_MAX_LINES else Int.MAX_VALUE
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(SeeSunDimens.Gap)) {
        Text(
            text = line,
            style = SeeSunTheme.typography.body,
            color = SeeSunTheme.colors.onBackground,
            maxLines = lines,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.fillMaxWidth().politeLiveRegion().semantics { contentDescription = line }
        )
        if (s.buttons.isNotEmpty()) {
            ActionRow(
                s.buttons.mapIndexed { i, b ->
                    ActionSpec(b.label, { host.press(b) }, if (i == 0) ButtonKind.PRIMARY else ButtonKind.OUTLINE)
                }
            )
        }
    }
}

private const val STRIP_MAX_LINES = 3
