package kr.seesun.core.assistant

import kr.seesun.core.voice.parse.Jamo

/**
 * 운동 이름 사전(N-03 「스쿼트 하고 싶어」). 앱이 운동 목록(서버, 없으면 기기 내장 29개)을 읽어 [ExerciseCatalog.of]로 만든다.
 * 이름마다 별칭: 띄어쓰기 뺀 전체 이름, 괄호를 뺀 이름, 다른 운동과 겹치지 않는 낱말(「맨몸 스쿼트」 → 「스쿼트」), 흔히 부르는 말([COMMON]).
 * 받아쓰기 오류(「스쿼드」)는 자모 유사도 [FUZZY_MIN] 이상이면 받는다(음식 이름과 같은 방식).
 */
class ExerciseCatalog private constructor(val entries: List<Entry>) {

    data class Entry(val id: String, val name: String, val aliases: List<String>)

    val isEmpty: Boolean get() = entries.isEmpty()

    /** 이름이 맞은 운동(맞은 글자 수 큰 순). 같은 글자 수로 둘 이상 맞으면 고르기를 묻는다. */
    data class Hit(val entry: Entry, val chars: Int, val exact: Boolean)

    fun find(compact: String): List<Hit> {
        if (compact.isEmpty() || entries.isEmpty()) return emptyList()
        val hits = entries.mapNotNull { e ->
            val exact = e.aliases.filter { compact.contains(it) }.maxByOrNull { it.length }
            if (exact != null) return@mapNotNull Hit(e, exact.length, exact = true)
            val fuzzy = e.aliases.filter { it.length >= FUZZY_MIN_LENGTH }.maxByOrNull { a -> fuzzyScore(compact, a) }
            fuzzy?.takeIf { fuzzyScore(compact, it) >= FUZZY_MIN }?.let { Hit(e, it.length - 1, exact = false) }
        }
        return hits.sortedWith(compareByDescending<Hit> { it.chars }.thenByDescending { it.exact })
    }

    /** [alias]와 같은 길이(±1) 조각 중 가장 비슷한 것의 자모 유사도. */
    private fun fuzzyScore(compact: String, alias: String): Double {
        val a = Jamo.decompose(alias)
        var best = 0.0
        for (len in (alias.length - 1)..(alias.length + 1)) {
            if (len <= 0 || len > compact.length) continue
            for (start in 0..compact.length - len) {
                val s = Jamo.similarity(Jamo.decompose(compact.substring(start, start + len)), a)
                if (s > best) best = s
            }
        }
        return best
    }

    companion object {
        const val FUZZY_MIN = 0.8
        private const val FUZZY_MIN_LENGTH = 3

        val EMPTY = ExerciseCatalog(emptyList())

        /** 흔히 부르는 말(이름 → 별칭). 이름은 띄어쓰기 뺀 형태로 찾는다. */
        private val COMMON: Map<String, List<String>> = mapOf(
            "맨몸스쿼트" to listOf("스쿼트", "스쿼트운동", "앉았다일어나기"),
            "푸쉬업" to listOf("푸시업", "팔굽혀펴기", "팔굽혀"),
            "버피테스트" to listOf("버피"),
            "레그레이즈" to listOf("다리들어올리기", "다리들기"),
            "조깅" to listOf("달리기", "가볍게뛰기"),
            "점핑잭" to listOf("팔벌려뛰기", "팔벌리기뛰기"),
            "명상및호흡운동" to listOf("명상", "호흡운동", "숨쉬기운동"),
            "고양이소자세" to listOf("고양이자세", "고양이소", "캣카우"),
            "타이치운동" to listOf("타이치", "태극권"),
            "힙브릿지" to listOf("힙브리지", "엉덩이들기", "브릿지"),
            "트렁크트위스트" to listOf("트렁크"),
            "스텝박스를활용한운동" to listOf("스텝박스", "스텝운동", "발판운동"),
            "t밸런스자세" to listOf("티밸런스", "t밸런스"),
            "요가우르드바하스타사나" to listOf("요가", "우르드바"),
            "햄스트링스트레칭" to listOf("햄스트링"),
            "다리스트레칭" to listOf("다리스트레칭"),
            "발끝합장자세" to listOf("합장자세", "발끝합장"),
            "하이런지자세" to listOf("하이런지"),
            "버드도그홀드" to listOf("버드도그"),
            "마운틴클라이머" to listOf("마운틴"),
            "밴드풀어파트" to listOf("밴드운동", "풀어파트"),
            "벽에다리올리기" to listOf("벽에다리"),
            "나비자세" to listOf("나비")
        )

        /** 낱말 별칭에서 뺄 흔한 말(여러 운동에 들어 있거나 다른 뜻과 겹침). */
        private val GENERIC = setOf(
            "운동", "자세", "스트레칭", "다리", "활용한", "및", "요가", "체중", "허리", "이동", "하이", "맨몸", "홀드", "올리기", "밸런스",
            "lying", "trunk", "twist"
        )

        fun of(items: List<Pair<String, String>>): ExerciseCatalog {
            val base = items.map { (id, name) -> Triple(id, name, AssistantText.compact(name.replace(Regex("""\(.*?\)"""), ""))) }
            val words = base.associate { (id, name, _) ->
                id to
                    name.replace(Regex("""\(.*?\)"""), " ").split(Regex("""[\s\-]+""")).map { AssistantText.compact(it).removeSuffix("를") }
                        .filter { it.length >= 2 && it !in GENERIC }
            }
            val counts = words.values.flatten().groupingBy { it }.eachCount()
            return ExerciseCatalog(
                base.map { (id, name, full) ->
                    val unique = words[id].orEmpty().filter { counts[it] == 1 }
                    val aliases = (listOf(full) + unique + COMMON[full].orEmpty()).map { it.lowercase() }.distinct()
                    Entry(id, name, aliases)
                }
            )
        }
    }
}
