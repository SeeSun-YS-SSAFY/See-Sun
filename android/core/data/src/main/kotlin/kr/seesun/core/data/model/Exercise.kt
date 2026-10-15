package kr.seesun.core.data.model

import kr.seesun.core.network.model.ExerciseAudioDto

/** 운동 카테고리(근력·유산소·유연성·균형). */
data class ExerciseCategory(val id: String, val name: String)

/** 목록 한 줄. [count]는 자주 하는 운동에서만 온다. */
data class ExerciseSummary(
    val id: String,
    val name: String,
    val categoryName: String? = null,
    val pictogramUrl: String? = null,
    val count: Int? = null
)

/**
 * 안내 대본 한 구간과 음성. [audioUrl]이 없으면 기기 음성(TTS)으로 대본을 읽는다(03 §7 예외 처리).
 * @param key 서버 구간 이름(exercise_description, first_description, main_form, …)
 */
data class GuideSection(val key: String, val title: String, val text: String, val audioUrl: String?)

/** 운동 상세(재생 화면). */
data class ExerciseDetail(
    val id: String,
    val name: String,
    val categoryName: String?,
    val sections: List<GuideSection>,
    val pictograms: List<String>,
    /** 보조자가 필요한 운동(EX-12, 운동 가이드 대본: 조깅). */
    val needsAssistant: Boolean,
    /** 그림별 대본 위치(번호 순). 비었거나 위치가 없으면 [pictograms]를 1초마다 돌린다(예전 동작). */
    val pictogramFrames: List<PictogramFrame> = emptyList(),
    /** 반복·유지 구간(따라 하기)에서 되풀이할 그림. 없으면 null. */
    val pictogramCycle: PictogramCycle? = null
)

/**
 * 픽토그램 한 장과 대본 위치. [section]([GuideSection.key])의 [sentenceIndex]번째 문장부터 이 그림을 보인다.
 * [label]은 그림 이름(TalkBack), [displayUrl]은 여백을 잘라 그림을 크게 한 보기용 사본(없으면 [url]).
 */
data class PictogramFrame(
    val url: String,
    val sequence: Int = 0,
    val section: String? = null,
    val sentenceIndex: Int? = null,
    val label: String? = null,
    val displayUrl: String? = null
) {
    /** 화면에 그릴 그림 주소(보기용 사본 우선). */
    val shownUrl: String get() = displayUrl ?: url
}

/** 반복·유지 구간: [section]의 [startSentence]번째 문장부터 [sequences] 그림(번호, 중복 가능)을 1초마다 되풀이. */
data class PictogramCycle(val section: String, val sequences: List<Int>, val startSentence: Int = 0)

/** 루틴 항목. 기본 루틴은 [itemId]가 없다. */
data class RoutineItem(
    val itemId: String?,
    val exerciseId: String,
    val exerciseName: String,
    val setCount: Int,
    val repsCount: Int?,
    val restSec: Int
)

/**
 * 루틴. [playlistId]가 있으면 사용자가 만든 루틴(서버 플레이리스트), 없으면 카테고리 기본 루틴.
 * 기록에는 실제 playlist_id를 보낸다(1차 B6: 운동 ID를 playlist_id로 보내 400).
 */
data class Routine(val playlistId: String?, val title: String, val items: List<RoutineItem>)

/** 서버에 닿지 못해 기기에 내장한 운동 정보를 쓴 경우 [fromDevice]=true. */
data class Catalog<T>(val data: T, val fromDevice: Boolean)

/** 구간 이름 → 화면 제목. 순서도 이 순서(1차 update_media 재생 순서 + form_description). */
object GuideSections {
    val ORDER: List<Pair<String, String>> = listOf(
        "exercise_description" to "운동 소개",
        "first_description" to "준비 자세",
        "main_form" to "동작 시작",
        "form_description" to "동작 설명",
        "stay_form" to "자세 유지",
        "fixed_form" to "원래 자세로",
        "exercise_guide_text" to "따라 하기"
    )

    /** 대본이 비었거나 1차 자리표시("x")면 구간을 뺀다. */
    fun usable(text: String?): String? = text?.trim()?.takeIf { it.isNotEmpty() && !it.equals("x", ignoreCase = true) }

    /**
     * 대본과 서버 음성(section별)을 짝짓는다. 음성만 있고 대본이 없는 구간은 버린다.
     * [audioStale](대본을 고쳤는데 녹음을 아직 다시 만들지 않은 운동)이면 녹음을 쓰지 않고 운동 전체를 기기 음성으로 읽는다
     * (한 운동 안에서 목소리가 바뀌지 않게).
     */
    fun build(texts: Map<String, String?>, audios: List<ExerciseAudioDto>, audioStale: Boolean = false): List<GuideSection> {
        val audioBySection = if (audioStale) emptyMap() else audios.mapNotNull { a -> (a.section ?: a.type)?.let { it to a.url } }.toMap()
        return ORDER.mapNotNull { (key, title) ->
            usable(texts[key])?.let { GuideSection(key, title, it, audioBySection[key]?.takeIf(String::isNotBlank)) }
        }
    }

    /** 보조자 필요 운동(운동 가이드 대본 기준). 서버 필드가 생기면 그 값으로 바꾼다. */
    private val ASSISTANT_KEYWORDS = listOf("조깅")

    fun needsAssistant(name: String): Boolean = ASSISTANT_KEYWORDS.any { name.contains(it) }
}
