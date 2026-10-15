package kr.seesun.core.ui

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.core.content.edit
import kr.seesun.core.a11y.rememberTalkBackEnabled
import kr.seesun.core.voice.recognition.SpeechInput
import kr.seesun.core.voice.tts.Speaker

/** 앱 안내 음성(app이 내려 준다). ViewModel이 없는 화면도 진입 안내·말로 고르기를 붙일 수 있게. */
val LocalGuidanceSpeaker = staticCompositionLocalOf<Speaker?> { null }

/** 음성 인식(app이 내려 준다). */
val LocalSpeechInput = staticCompositionLocalOf<SpeechInput?> { null }

/**
 * 화면 진입 안내 한 번(「[화면] + [핵심] + [다음 행동]」, 04 §5). 화면 낭독기가 켜져 있으면 말하지 않고(paneTitle이 맡음)
 * [화면 다시 듣기]와 되울림 필터에만 기록한다. 같은 [key]면 다시 말하지 않는다.
 */
@Composable
fun EntryGuidance(key: String, message: String) {
    val speaker = LocalGuidanceSpeaker.current
    val talkBack = rememberTalkBackEnabled()
    val voice = remember(speaker) { speaker?.let(::GuidanceVoice) }
    AutoGuidance(key = key, message = message, talkBackEnabled = talkBack, speak = { voice?.auto(it, talkBack) })
}

/** 말로 고를 메뉴 하나: [words] 중 하나가 들린 말에 들어 있으면 [onPick]. */
data class VoiceMenuOption(val words: List<String>, val onPick: () -> Unit)

/** 들린 말에서 메뉴를 고른다(순수 함수, 시험용). 띄어쓰기는 무시하고, 더 긴 낱말이 먼저 맞는다("마이페이지" > "페이지"). */
object VoiceMenuMatcher {
    fun match(text: String, options: List<VoiceMenuOption>): VoiceMenuOption? {
        val t = text.replace(" ", "")
        return options
            .flatMap { o -> o.words.map { w -> w.replace(" ", "") to o } }
            .sortedByDescending { it.first.length }
            .firstOrNull { (w, _) -> w.isNotEmpty() && t.contains(w) }
            ?.second
    }
}

/**
 * 메뉴 화면의 [말로 고르기](흐름 문서 P0-4): 화면이 「…중에서 골라 주세요」로 안내하는 곳에 마이크를 붙인다.
 * 음성 화면과 같은 듣기 방식(질문 뒤 듣기·말하면 듣기·이어폰 버튼)을 쓴다. app이 음성 모듈을 내려 주지 않으면(미리보기·시험) 그리지 않는다.
 */
@Composable
fun VoiceMenuButton(options: List<VoiceMenuOption>, hint: String, modifier: Modifier = Modifier) {
    val speech = LocalSpeechInput.current ?: return
    val speaker = LocalGuidanceSpeaker.current
    val talkBack = rememberTalkBackEnabled()
    val scope = rememberCoroutineScope()
    val latest by rememberUpdatedState(options)
    val voice = remember(speaker) { speaker?.let(::GuidanceVoice) }
    val controller = remember(speech, scope) {
        VoiceCommandController(scope, speech, onBeforeListen = { voice?.stop() }) { candidates ->
            val picked = candidates.firstNotNullOfOrNull { VoiceMenuMatcher.match(it, latest) }
            picked?.onPick?.invoke()
            picked != null
        }
    }
    VoiceCommandButton(controller, hint, talkBack, { voice?.auto(it, talkBack) }, modifier)
}

/** 첫 실행 사용법 안내를 이미 들었는지(기기에만, 「사용법 들음」 — 소유자 msg 1029). */
class UsageGuideStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    val heard: Boolean get() = prefs.getBoolean(KEY_HEARD, false)

    fun markHeard() = prefs.edit { putBoolean(KEY_HEARD, true) }

    private companion object {
        const val PREFS = "seesun_usage_guide"
        const val KEY_HEARD = "usage_guide_heard"
    }
}
