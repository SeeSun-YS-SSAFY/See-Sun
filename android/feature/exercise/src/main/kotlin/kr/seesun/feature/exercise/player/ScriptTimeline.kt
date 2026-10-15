package kr.seesun.feature.exercise.player

/**
 * 안내 대본의 문장 나누기와 문장별 시간 추정(EX-04 "문장 싱크", 01 §7 P2).
 *
 * 서버에는 아직 문장별 타임스탬프가 없어서(1차 mp3는 구간 단위), 구간 음성 길이를 문장 글자 수 비율로 나눠
 * 지금 읽는 문장을 추정한다. 기기 음성(TTS)으로 읽을 때는 문장마다 따로 말하므로 정확히 맞는다.
 * 서버가 문장별 시작 시각을 주게 되면 이 클래스의 가중치만 실제 시각으로 바꾸면 된다(화면·플레이어는 그대로).
 */
class ScriptTimeline private constructor(val sentences: List<String>, private val weights: List<Int>) {

    private val total: Int = weights.sum().coerceAtLeast(1)

    /** 구간 전체 길이 [durationMs]에서 [positionMs]일 때 읽고 있는 문장 번호(0부터). */
    fun sentenceAt(positionMs: Long, durationMs: Long): Int {
        if (sentences.isEmpty()) return 0
        if (durationMs <= 0) return 0
        var acc = 0L
        weights.forEachIndexed { index, w ->
            acc += w
            if (positionMs < durationMs * acc / total) return index
        }
        return sentences.lastIndex
    }

    /** [index] 문장이 시작하는 시각(ms). [다시 듣기]·문장 이동에 쓴다. */
    fun startOf(index: Int, durationMs: Long): Long {
        val before = weights.take(index.coerceIn(0, weights.size)).sum()
        return durationMs * before / total
    }

    companion object {
        /** 문장 사이 쉼을 글자 몇 개만큼으로 칠지. 짧은 문장("2.")이 0초가 되지 않게 한다. */
        private const val PAUSE_WEIGHT = 4

        // 한글 뒤 마침표·물음표·느낌표 다음(숫자 목록 "1."은 나누지 않음), " : " 머리말, "들이쉬고-" 같은 구령 뒤
        private val SPLIT = Regex("""(?<=[가-힣)\]][.!?])\s+|\s+:\s+|(?<=[가-힣]-)\s+""")

        fun split(text: String): List<String> = text
            .split('\n')
            .flatMap { line -> line.split(SPLIT) }
            .map { it.trim() }
            .filter { it.isNotEmpty() }

        fun of(text: String): ScriptTimeline {
            val sentences = split(text)
            return ScriptTimeline(sentences, sentences.map { s -> s.count { !it.isWhitespace() } + PAUSE_WEIGHT })
        }
    }
}
