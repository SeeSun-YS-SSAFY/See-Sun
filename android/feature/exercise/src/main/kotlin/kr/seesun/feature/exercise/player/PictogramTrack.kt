package kr.seesun.feature.exercise.player

import kr.seesun.core.data.model.ExerciseDetail
import kr.seesun.core.data.model.GuideSection
import kr.seesun.core.data.model.GuideSections
import kr.seesun.core.data.model.PictogramCycle
import kr.seesun.core.data.model.PictogramFrame

/**
 * 픽토그램과 안내 대본을 잇는 규칙(2026-10-14 소유자·의뢰인 요청 「해당 이미지 혹은 대사 선택 시 해당 부분으로」,
 * 연결은 육안 검토 연결표 — 서버 pictogram_frames·pictogram_cycle).
 *
 * - 그림마다 "보이기 시작하는 대본 위치"(구간 이름 + 그 구간 안 문장 번호, anchor)가 있으면, 지금 위치까지 가장 최근에 지난
 *   anchor의 그림을 보인다. 첫 anchor 전에는 첫 그림을 보인다.
 * - 반복·유지 구간([PictogramCycle], 따라 하기)의 시작 문장부터는 적힌 그림들을 재생 중 1초마다 되풀이한다(한 장뿐이면 그대로).
 * - anchor가 하나도 없으면(서버 연결표 없음·기기 내장 대본) 예전처럼 모든 그림을 1초마다 돌린다.
 * - 그림을 누르면 그 anchor 위치로 간다([seekTarget]). 지금 재생 목록에 없는 구간(루틴 2세트째는 따라 하기만)이면 갈 수 없다.
 */
class PictogramTrack(val frames: List<PictogramFrame>, private val cycle: PictogramCycle? = null) {

    /** 화면에 그릴 그림 주소(보기용 사본 우선). */
    val urls: List<String> = frames.map { it.shownUrl }

    private val anchored: List<Int> = frames.indices.filter { anchorOf(frames[it]) != null }

    /** 대본 위치 연결이 있는가. 없으면 예전처럼 모든 그림을 1초마다 돌린다. */
    val mapped: Boolean get() = anchored.isNotEmpty()

    private val cycleFrames: List<Int> = cycle?.sequences.orEmpty().mapNotNull { seq ->
        frames.indexOfFirst { it.sequence == seq }.takeIf { it >= 0 }
    }

    /**
     * 지금 위치([sectionKey]의 [sentence]번째 문장)에서 보일 그림 번호들. 둘 이상이면 재생 중 1초마다 돌린다.
     */
    fun framesAt(sectionKey: String?, sentence: Int): List<Int> {
        if (frames.isEmpty()) return emptyList()
        if (!mapped) return frames.indices.toList()
        if (inCycle(sectionKey, sentence)) return cycleFrames
        val order = SECTION_KEYS.indexOf(sectionKey)
        if (order < 0) return listOf(anchored.first())
        val reached = anchored.lastOrNull { i -> compare(anchorOf(frames[i])!!, order to sentence) <= 0 }
        return listOf(reached ?: anchored.first())
    }

    private fun inCycle(sectionKey: String?, sentence: Int): Boolean {
        val c = cycle ?: return false
        return cycleFrames.isNotEmpty() && sectionKey == c.section && sentence >= c.startSentence
    }

    /** [index] 그림을 눌렀을 때 갈 곳(지금 재생 목록의 구간 번호, 문장 번호). 갈 수 없으면 null. */
    fun seekTarget(index: Int, sections: List<GuideSection>): Pair<Int, Int>? {
        val frame = frames.getOrNull(index) ?: return null
        val key = frame.section ?: return null
        val sectionIndex = sections.indexOfFirst { it.key == key }
        if (sectionIndex < 0) return null
        return sectionIndex to (frame.sentenceIndex ?: 0).coerceAtLeast(0)
    }

    /** TalkBack·화면에 쓰는 그림 이름. 연결표 이름이 없으면 "동작 그림 n". */
    fun label(index: Int): String = frames.getOrNull(index)?.label?.takeIf { it.isNotBlank() } ?: "동작 그림 ${index + 1}"

    private fun anchorOf(frame: PictogramFrame): Pair<Int, Int>? {
        val key = frame.section ?: return null
        val index = frame.sentenceIndex ?: return null
        val order = SECTION_KEYS.indexOf(key)
        return if (order < 0) null else order to index
    }

    private fun compare(a: Pair<Int, Int>, b: Pair<Int, Int>): Int = if (a.first != b.first) a.first - b.first else a.second - b.second

    companion object {
        private val SECTION_KEYS: List<String> = GuideSections.ORDER.map { it.first }

        /** 운동 상세의 그림. 서버 그림 정보(pictogram_frames)가 없으면(이전 서버·기기 내장) URL 목록만으로 만든다. */
        fun of(detail: ExerciseDetail): PictogramTrack = if (detail.pictogramFrames.isNotEmpty()) {
            PictogramTrack(detail.pictogramFrames, detail.pictogramCycle)
        } else {
            PictogramTrack(detail.pictograms.mapIndexed { i, url -> PictogramFrame(url, sequence = i + 1) })
        }
    }
}
