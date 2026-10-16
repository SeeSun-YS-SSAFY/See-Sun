package kr.seesun.feature.exercise.play

import kotlin.math.roundToInt
import kr.seesun.core.designsystem.component.ButtonKind
import kr.seesun.core.designsystem.component.MoreMenuItem
import kr.seesun.core.designsystem.component.progressPercent
import kr.seesun.feature.exercise.player.GuidePlayer
import kr.seesun.feature.exercise.player.GuideState
import kr.seesun.feature.exercise.player.GuideStatus
import kr.seesun.feature.exercise.player.ScriptTimeline

/**
 * 재생 화면 간소화(v1.9, 화면 간소화 설계 v1 §6, 소유자 msg 1149 「그렇게 진행해줘」).
 * 보이는 층: 그림 · [속도] · [재생/일시정지] · [대본] · 진행 슬라이더 + 머리줄 [◀ 뒤로] · [⋯ 더 보기].
 * 숨긴 기능은 하나도 없애지 않고 네 길로 똑같이 남긴다: ① [⋯ 더 보기] 목록 ② TalkBack 작업(재생 버튼·슬라이더)
 * ③ 이 화면 음성 명령(멈춘 동안만 듣기) ④ 이어폰 버튼(한 번 = 재생/일시정지, 그대로).
 * 이 파일은 화면과 무관한 규칙(단위 시험 대상)만 둔다.
 */
enum class PlayerMode {
    /** 한 가지 운동 */
    SINGLE,

    /** 루틴의 운동 단계 */
    ROUTINE,

    /** 루틴의 휴식 단계 */
    REST
}

/** 재생 화면에서 할 수 있는 일(버튼·TalkBack 작업·음성 명령이 모두 이 하나로 모인다). */
enum class PlayerCommand {
    PLAY,
    PAUSE,
    REPLAY,
    PREVIOUS,
    NEXT,
    SKIP_EXERCISE,
    UNDO_SKIP,
    SKIP_REST,
    END,
    FAVORITE,
    SPEED_SLOW,
    SPEED_NORMAL,
    SPEED_FAST,
    SCRIPT_ON,
    SCRIPT_OFF,
    SETTINGS,
    HELP,
    SPEAK
}

/** [⋯ 더 보기] 목록 · TalkBack 작업에 쓰는 이름과 순서(그림 1 오른쪽). */
object PlayerMenu {
    /** [⋯] 버튼의 TalkBack 이름: 안에 있는 기능 이름을 함께 읽는다(숨긴 기능 알리기 §6.4 ⓑ). */
    fun moreLabel(mode: PlayerMode): String = when (mode) {
        PlayerMode.SINGLE -> "더 보기. 다시 듣기, 이전·다음 구간, 즐겨찾기, 끝내기, 설정"
        PlayerMode.ROUTINE -> "더 보기. 다시 듣기, 이전·다음 구간, 건너뛰기, 끝내기, 설정"
        PlayerMode.REST -> "더 보기. 루틴 끝내기, 설정, 도움말"
    }

    /** 제목 아래 한 줄. */
    fun hint(mode: PlayerMode): String = when (mode) {
        PlayerMode.SINGLE -> "말로도 돼요: 「다음」 「다시」 「끝내기」"
        PlayerMode.ROUTINE -> "말로도 돼요: 「다음」 「건너뛰기」 「끝내기」"
        PlayerMode.REST -> "말로도 돼요: 「바로 시작」 「끝내기」"
    }

    /**
     * [⋯ 더 보기] 목록. 맨 위 [말로 하기](음성 모듈이 있을 때), 끝내기는 빨강, 설정·도움말은 테두리 버튼.
     * @param favorite 즐겨찾기 상태(null이면 항목 없음 — 휴식 단계)
     * @param settings 앱이 설정 열기를 넘겨 줬는가
     * @param voice 음성 인식을 쓸 수 있는가
     */
    fun items(mode: PlayerMode, favorite: Boolean?, settings: Boolean, voice: Boolean): List<MoreMenuItem> = buildList {
        if (voice) add(item(PlayerCommand.SPEAK, "말로 하기"))
        if (mode != PlayerMode.REST) {
            add(item(PlayerCommand.REPLAY, "이 구간 다시 듣기"))
            add(item(PlayerCommand.PREVIOUS, "이전 구간"))
            add(item(PlayerCommand.NEXT, "다음 구간"))
        }
        if (mode == PlayerMode.ROUTINE) add(item(PlayerCommand.SKIP_EXERCISE, "이 운동 건너뛰기"))
        if (favorite != null) add(favoriteItem(favorite))
        add(item(PlayerCommand.END, endLabel(mode), ButtonKind.SECONDARY))
        if (settings) add(item(PlayerCommand.SETTINGS, "설정", ButtonKind.OUTLINE))
        add(item(PlayerCommand.HELP, "도움말", ButtonKind.OUTLINE))
    }

    /**
     * 가운데 재생 버튼의 TalkBack 「작업」: [⋯] 목록과 같은 기능(말로 하기 포함 — TalkBack 사용자는 자동 듣기가 꺼져 있다).
     * 화면에는 안 보이므로 [⋯]와 함께 둔다(§3.3).
     */
    fun playActions(mode: PlayerMode, favorite: Boolean?, settings: Boolean, voice: Boolean): List<MoreMenuItem> =
        items(mode, favorite, settings, voice)

    /** 진행 슬라이더의 TalkBack 「작업」(구간 이동은 위아래로 쓸어 넘기기로도 된다). */
    fun sliderActions(): List<MoreMenuItem> = listOf(
        item(PlayerCommand.PREVIOUS, "이전 구간"),
        item(PlayerCommand.NEXT, "다음 구간"),
        item(PlayerCommand.REPLAY, "이 구간 다시 듣기")
    )

    /** 속도 버튼의 TalkBack 「작업」: 한 번에 고르기. */
    fun speedActions(): List<MoreMenuItem> = listOf(
        item(PlayerCommand.SPEED_SLOW, "느리게"),
        item(PlayerCommand.SPEED_NORMAL, "보통"),
        item(PlayerCommand.SPEED_FAST, "빠르게")
    )

    fun endLabel(mode: PlayerMode): String = if (mode == PlayerMode.SINGLE) "운동 끝내기" else "루틴 끝내기"

    /** 「도움말」: 이 화면에서 할 수 있는 말(「뭐라고 말할 수 있어」, U-04). */
    fun help(mode: PlayerMode): String = when (mode) {
        PlayerMode.SINGLE ->
            "멈췄을 때 이렇게 말씀하시면 돼요. 「계속」, 「다시」는 이 구간 다시 듣기, 「다음」, 「이전 구간」, 「빠르게」, 「천천히」, " +
                "「대본 보여줘」, 「즐겨찾기」, 「끝내기」. 재생 중에는 가운데 버튼이나 이어폰 버튼을 한 번 눌러 멈춰 주세요."

        PlayerMode.ROUTINE ->
            "멈췄을 때 이렇게 말씀하시면 돼요. 「계속」, 「다시」는 이 구간 다시 듣기, 「다음」은 다음 구간, 「다음 운동」이나 「건너뛰기」는 " +
                "이 운동 건너뛰기, 「되돌려」는 건너뛰기 취소, 「빠르게」, 「천천히」, 「끝내기」. 재생 중에는 가운데 버튼이나 이어폰 버튼을 한 번 눌러 멈춰 주세요."

        PlayerMode.REST ->
            "휴식을 멈췄을 때 이렇게 말씀하시면 돼요. 「바로 시작」은 휴식 건너뛰기, 「계속」은 휴식 이어서 세기, 「끝내기」는 루틴 끝내기."
    }

    /** 첫 재생 때 한 번 알리는 말(§6.4 ⓐ). */
    const val FIRST_TIP = "멈추려면 가운데 버튼을 누르세요. 건너뛰기와 끝내기는 오른쪽 위 더 보기에 있어요. 멈춘 뒤 말로 하셔도 돼요."

    /** 멈춘 뒤 듣기 전에 하는 말(TalkBack 꺼짐, §6.4 「언제 듣나」 ⓑ). */
    fun pausedPrompt(mode: PlayerMode): String = when (mode) {
        PlayerMode.SINGLE -> "멈췄어요. 「다음」, 「다시」, 「끝내기」라고 말씀하셔도 돼요."
        PlayerMode.ROUTINE -> "멈췄어요. 「다음」, 「건너뛰기」, 「끝내기」라고 말씀하셔도 돼요."
        PlayerMode.REST -> "휴식을 멈췄어요. 「바로 시작」, 「끝내기」라고 말씀하셔도 돼요."
    }

    /** 음성 명령을 알아듣지 못했을 때 덧붙이는 요령. */
    fun voiceHint(mode: PlayerMode): String = when (mode) {
        PlayerMode.SINGLE -> "「계속」, 「다음」, 「다시」, 「끝내기」처럼 말씀해 주세요."
        PlayerMode.ROUTINE -> "「계속」, 「다음」, 「건너뛰기」, 「끝내기」처럼 말씀해 주세요."
        PlayerMode.REST -> "「바로 시작」, 「계속」, 「끝내기」처럼 말씀해 주세요."
    }

    fun favoriteItem(favorite: Boolean) = item(
        PlayerCommand.FAVORITE,
        if (favorite) "즐겨찾기 빼기" else "즐겨찾기 넣기",
        state = if (favorite) "즐겨찾기에 있음" else "즐겨찾기에 없음"
    )

    fun commandOf(item: MoreMenuItem): PlayerCommand? = PlayerCommand.entries.firstOrNull { it.name == item.key }

    private fun item(command: PlayerCommand, label: String, kind: ButtonKind = ButtonKind.PRIMARY, state: String? = null) =
        MoreMenuItem(command.name, label, kind, state)
}

/**
 * [⋯]를 열고 닫을 때 다시 틀지(§6.4: 열면 안내를 잠시 멈추고, 항목을 고르면 그 동작 뒤 다시 재생, [닫기]·뒤로면 멈추기 전 상태로).
 * 구간 이동(다시 듣기·이전·다음·건너뛰기)은 그 동작이 스스로 재생한다. 끝내기·설정·도움말·말로 하기는 멈춘 채로 둔다
 * (끝내기 확인 창에서 [계속하기]를 고르면 그때 멈추기 전 상태로).
 */
object MenuFlow {
    fun resumeAfterPick(command: PlayerCommand, wasRunning: Boolean): Boolean = wasRunning &&
        when (command) {
            PlayerCommand.FAVORITE, PlayerCommand.SPEED_SLOW, PlayerCommand.SPEED_NORMAL, PlayerCommand.SPEED_FAST,
            PlayerCommand.SCRIPT_ON, PlayerCommand.SCRIPT_OFF -> true

            else -> false
        }

    fun resumeAfterClose(wasRunning: Boolean): Boolean = wasRunning
}

/**
 * 재생 화면 음성 명령(§6.4 ③, 시선 비서 §4.6 2단계 「이 화면 명령」). 띄어쓰기는 무시하고 더 긴 말이 먼저 맞는다.
 * 충돌 말 정리(§6.4): 「그만」은 일시정지(「그만할래」는 끝내기), 「다음」은 다음 구간·「다음 운동」은 건너뛰기,
 * 「다시」는 이 구간 다시 듣기·「다시 시작」은 이어서, 「뒤로」는 끝내기 확인 창.
 */
object PlayerVoiceMatcher {
    private val COMMON = listOf(
        "일시정지" to PlayerCommand.PAUSE,
        "멈춰" to PlayerCommand.PAUSE,
        "멈춰줘" to PlayerCommand.PAUSE,
        "잠깐" to PlayerCommand.PAUSE,
        "정지" to PlayerCommand.PAUSE,
        "그만" to PlayerCommand.PAUSE,
        "계속" to PlayerCommand.PLAY,
        "이어서" to PlayerCommand.PLAY,
        "다시시작" to PlayerCommand.PLAY,
        "재생" to PlayerCommand.PLAY,
        "틀어" to PlayerCommand.PLAY,
        "끝내기" to PlayerCommand.END,
        "끝내" to PlayerCommand.END,
        "끝낼래" to PlayerCommand.END,
        "운동끝" to PlayerCommand.END,
        "그만할래" to PlayerCommand.END,
        "그만할게" to PlayerCommand.END,
        "종료" to PlayerCommand.END,
        "마칠래" to PlayerCommand.END,
        "뒤로" to PlayerCommand.END,
        "나가" to PlayerCommand.END,
        "설정" to PlayerCommand.SETTINGS,
        "도움말" to PlayerCommand.HELP,
        "뭐라고말" to PlayerCommand.HELP,
        "명령어" to PlayerCommand.HELP,
        "어떻게써" to PlayerCommand.HELP
    )
    private val GUIDE = listOf(
        "다시" to PlayerCommand.REPLAY,
        "다시들려" to PlayerCommand.REPLAY,
        "한번더" to PlayerCommand.REPLAY,
        "다시듣기" to PlayerCommand.REPLAY,
        "이전" to PlayerCommand.PREVIOUS,
        "이전구간" to PlayerCommand.PREVIOUS,
        "앞구간" to PlayerCommand.PREVIOUS,
        "전단계" to PlayerCommand.PREVIOUS,
        "다음" to PlayerCommand.NEXT,
        "다음구간" to PlayerCommand.NEXT,
        "넘겨" to PlayerCommand.NEXT,
        "빠르게" to PlayerCommand.SPEED_FAST,
        "빨리" to PlayerCommand.SPEED_FAST,
        "천천히" to PlayerCommand.SPEED_SLOW,
        "느리게" to PlayerCommand.SPEED_SLOW,
        "보통속도" to PlayerCommand.SPEED_NORMAL,
        "원래속도" to PlayerCommand.SPEED_NORMAL,
        "대본" to PlayerCommand.SCRIPT_ON,
        "자막" to PlayerCommand.SCRIPT_ON,
        "그림" to PlayerCommand.SCRIPT_OFF
    )
    private val SINGLE = listOf("즐겨찾기" to PlayerCommand.FAVORITE)
    private val ROUTINE = listOf(
        "다음운동" to PlayerCommand.SKIP_EXERCISE,
        "건너뛰" to PlayerCommand.SKIP_EXERCISE,
        "건너띄" to PlayerCommand.SKIP_EXERCISE,
        "스킵" to PlayerCommand.SKIP_EXERCISE,
        "되돌려" to PlayerCommand.UNDO_SKIP,
        "되돌리" to PlayerCommand.UNDO_SKIP,
        "즐겨찾기" to PlayerCommand.FAVORITE
    )
    private val REST = listOf(
        "바로시작" to PlayerCommand.SKIP_REST,
        "휴식건너뛰" to PlayerCommand.SKIP_REST,
        "건너뛰" to PlayerCommand.SKIP_REST,
        "다음운동" to PlayerCommand.SKIP_REST,
        "바로다음" to PlayerCommand.SKIP_REST
    )

    fun match(text: String, mode: PlayerMode): PlayerCommand? {
        val t = text.replace(" ", "")
        if (t.isEmpty()) return null
        val words = COMMON + when (mode) {
            PlayerMode.SINGLE -> GUIDE + SINGLE
            PlayerMode.ROUTINE -> GUIDE + ROUTINE
            PlayerMode.REST -> REST
        }
        return words.sortedByDescending { it.first.length }.firstOrNull { t.contains(it.first) }?.second
    }

    /** 인식 후보 중 처음 맞는 명령. */
    fun matchAny(candidates: List<String>, mode: PlayerMode): PlayerCommand? = candidates.firstNotNullOfOrNull { match(it, mode) }
}

/** 속도 명령을 실제 배속으로(지금 속도 목록 [GuidePlayer.SPEEDS] 안에서). 「빠르게」는 한 단계 빠르게(최대 1.5배). */
fun speedFor(command: PlayerCommand, current: Float): Float? {
    val speeds = GuidePlayer.SPEEDS.sorted()
    return when (command) {
        PlayerCommand.SPEED_SLOW -> speeds.first()
        PlayerCommand.SPEED_NORMAL -> 1f
        PlayerCommand.SPEED_FAST -> speeds.firstOrNull { it > current && it > 1f } ?: speeds.last()
        else -> null
    }
}

/** 진행 한 줄(§6.1 4번): 화면 글자와 TalkBack 낭독. 상태(재생 중/멈춤)는 재생 버튼 상태 설명으로 읽으므로 넣지 않는다. */
data class ProgressText(val shown: String, val spoken: String)

/** 루틴 위치(운동 n개 중 몇 번째, 몇 세트째). */
data class RoutinePosition(val itemIndex: Int, val itemCount: Int, val set: Int, val totalSets: Int)

fun progressText(state: GuideState, routine: RoutinePosition? = null): ProgressText {
    val count = state.sections.size
    val section = if (count == 0) null else "구간 ${state.sectionIndex.coerceIn(0, count - 1) + 1}/$count"
    val title = state.section?.title
    return if (routine == null) {
        ProgressText(section.orEmpty(), listOfNotNull(section, title).joinToString(", "))
    } else {
        val sets = if (routine.totalSets > 1) "${routine.set}/${routine.totalSets}세트" else "1세트"
        ProgressText(
            listOfNotNull("${routine.itemIndex + 1}/${routine.itemCount} 운동", sets, section).joinToString(" · "),
            listOfNotNull("운동 ${routine.itemCount}개 중 ${routine.itemIndex + 1}번째", "${routine.set}세트째", section, title)
                .joinToString(", ")
        )
    }
}

/**
 * 진행 슬라이더의 TalkBack 값(§6.3): 조절 가능한 슬라이더, **한 단계 = 한 구간**(steps = 구간 수 − 2).
 * 화면 채움([fraction])은 문장 단위로 부드럽게, 낭독 값은 10퍼센트 단위로만 바뀐다(재생 중 끼어들지 않게).
 */
data class SliderSpec(
    val sectionIndex: Int,
    val sectionCount: Int,
    val fraction: Float,
    val label: String,
    val state: String,
    val rangeEnd: Float,
    val steps: Int
)

fun sliderSpec(state: GuideState): SliderSpec {
    val count = state.sections.size
    val index = if (count == 0) 0 else state.sectionIndex.coerceIn(0, count - 1)
    val fraction = guideProgress(state)
    val title = state.section?.title?.let { " $it" }.orEmpty()
    return SliderSpec(
        sectionIndex = index,
        sectionCount = count,
        fraction = fraction,
        label = SLIDER_LABEL,
        state = if (count == 0) "${progressPercent(fraction)}퍼센트" else "구간 ${index + 1}/$count$title, ${progressPercent(fraction)}퍼센트",
        rangeEnd = (count - 1).coerceAtLeast(1).toFloat(),
        steps = (count - 2).coerceAtLeast(0)
    )
}

/** TalkBack이 슬라이더 값을 바꿨을 때(위아래로 쓸기 = 한 단계): 갈 구간. */
fun sliderTargetSection(value: Float, sectionCount: Int): Int = value.roundToInt().coerceIn(0, (sectionCount - 1).coerceAtLeast(0))

/**
 * 막대의 한 곳을 눌렀을 때(WCAG 2.5.7 끌기 대안)·손잡이를 놓았을 때 그 자리의 구간·문장.
 * 진행 바와 같은 문장 단위 눈금([guideProgress])으로 거꾸로 셈한다. 끝(1)은 마지막 구간의 마지막 문장.
 */
fun positionAt(state: GuideState, fraction: Float): Pair<Int, Int> {
    if (state.sections.isEmpty()) return 0 to 0
    val counts = state.sections.map { ScriptTimeline.split(it.text).size.coerceAtLeast(1) }
    val total = counts.sum()
    var target = (fraction.coerceIn(0f, 1f) * total).toInt().coerceAtMost(total - 1)
    counts.forEachIndexed { s, n ->
        if (target < n) return s to target
        target -= n
    }
    return counts.lastIndex to counts.last() - 1
}

/** 재생 버튼의 보이는 짧은 글자(원 아래 20sp). TalkBack 이름은 [playLabel](지금 할 수 있는 동작). */
fun playCaption(state: GuideState): String = when {
    state.isPlaying -> "일시정지"
    state.status == GuideStatus.FINISHED -> "처음부터"
    state.status == GuideStatus.PAUSED || state.status == GuideStatus.INTERRUPTED -> "이어서 듣기"
    else -> "재생"
}

/** 재생 버튼 TalkBack 이름(지금 할 수 있는 동작). 상태는 상태 설명([GuideStatus.label])으로 읽는다. */
fun playLabel(state: GuideState): String = when {
    state.isPlaying -> "일시정지"
    state.status == GuideStatus.FINISHED -> "처음부터 다시 듣기"
    state.status == GuideStatus.PAUSED || state.status == GuideStatus.INTERRUPTED -> "이어서 듣기"
    else -> "재생"
}

const val SLIDER_LABEL = "운동 안내 진행"

/** 건너뛰기를 말로 되돌릴 수 있는 시간(§6.4 「확인」). */
const val UNDO_WINDOW_MS = 10_000L

/** 끝내기 확인 창의 고르기(버튼 순서와 같다). */
enum class ExitChoice { FINISH, CONTINUE, QUIT }

/** 끝내기 확인 창을 말로 답하기(의뢰서 A5 「버튼뿐 아니라 음성으로도」): 「기록하고 끝내기」, 「계속」, 「기록하지 않고 나가기」. */
object ExitVoiceMatcher {
    fun match(text: String): ExitChoice? {
        val t = text.replace(" ", "")
        return when {
            t.isEmpty() -> null
            listOf("기록하지않", "기록안", "안하고", "않고", "그냥나가").any { t.contains(it) } -> ExitChoice.QUIT
            listOf("계속", "이어서", "안끝", "취소", "아니").any { t.contains(it) } -> ExitChoice.CONTINUE
            listOf("기록", "끝내", "끝낼", "마칠", "네", "응", "예").any { t.contains(it) } -> ExitChoice.FINISH
            else -> null
        }
    }

    fun matchAny(candidates: List<String>): ExitChoice? = candidates.firstNotNullOfOrNull(::match)

    const val HINT = "「기록하고 끝내기」, 「계속」, 「기록하지 않고 나가기」 중에서 말씀해 주세요."
}

/** 재생 화면이 음성 명령을 들을지(§6.4 「언제 듣나」, 단위 시험 대상). */
object PlayerListening {
    /**
     * @param overlay [⋯]·확인 창이 열려 있음(그 창이 따로 듣는다)
     * @param running 안내가 재생 중(루틴 휴식은 타이머가 도는 중)
     * @param pauseSeq 사용자가 멈추거나 [말로 하기]를 누른 횟수
     */
    fun shouldListen(overlay: Boolean, running: Boolean, pauseSeq: Int): Boolean = !overlay && !running && pauseSeq > 0
}
