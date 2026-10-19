package kr.seesun.core.assistant

/**
 * 받아쓴 말 다듬기(§4.2). 맞추기는 띄어쓰기를 뺀 글자([compact])에서 「들어 있나」로 하되,
 * 말 전체 중 얼마나 덮었는지([Cover])를 함께 재서 긴 문장 속 낱말 하나에 끌려가지 않게 한다(§4.6 구체성).
 * 끝 어미(「줘/줄래/주세요/해 줘/할래/하고 싶어」)·조사는 [FILLERS]로 덮어 「요청」으로 본다.
 */
object AssistantText {
    private val WAKE = Regex("""^\s*시선(아|이|님|씨|이야|아요)?(?=[\s,.!?~]|$)[\s,.!?~]*""")
    private val WAKE_COMPACT = listOf("시선아", "시선님", "시선씨", "시선이")
    private val STRIP = Regex("""[\s,.?!~·…'"“”‘’「」()\-]""")

    /** 다듬은 말 한 벌: 원문(형태 보존), 띄어쓰기 뺀 글자, 「시선아」가 붙었는지. */
    data class Utterance(val raw: String, val compact: String, val wake: Boolean)

    /** 맨 앞 「시선아/시선」을 떼고 두 벌(형태 보존·띄어쓰기 제거)을 만든다. */
    fun utterance(text: String): Utterance {
        val (body, wake) = stripWake(text)
        return Utterance(body, compact(body), wake)
    }

    /** 맨 앞 「시선아/시선」을 떼고, 붙어 있었는지 함께 돌려준다(붙어 있으면 화면 명령을 건너뛰고 비서로, §4.6). */
    fun stripWake(text: String): Pair<String, Boolean> {
        val m = WAKE.find(text)
        if (m != null) return text.substring(m.range.last + 1).trim() to true
        val c = text.trimStart()
        WAKE_COMPACT.firstOrNull { c.startsWith(it) }?.let { return c.removePrefix(it).trim() to true }
        return text.trim() to false
    }

    /** 띄어쓰기·문장부호를 뺀 소문자. */
    fun compact(text: String): String = STRIP.replace(text, "").lowercase()

    /**
     * 말이 「요청 어미·조사」로만 남는지 볼 때 쓰는 낱말(길이 순). 핵심어가 덮지 못한 글자 중 이것들이 덮는 만큼은 덮은 것으로 친다.
     * 한 글자는 조사·어미만(낱말 속에 섞여 뜻을 덮지 않게 짧은 목록).
     */
    private val FILLERS: List<String> = listOf(
        "해주시겠어요", "해주실수있어", "보여주시겠어요", "알려주시겠어요", "해주실래요", "해주십시오", "부탁드립니다", "부탁드려요",
        "해주세요", "해주실래", "하고싶어요", "하고싶은데", "하고싶습니다", "해줄수있어", "해줄래요", "보여주세요", "알려주세요", "가주세요",
        "열어주세요", "들려주세요", "말해주세요", "보여줄래", "알려줄래", "가고싶어", "보고싶어", "듣고싶어", "싶습니다",
        "하고싶어", "하고싶다", "해줄래", "보여줘", "알려줘", "말해줘", "들려줘", "가볼까", "할래요", "할게요", "해줘요", "부탁해",
        "주세요", "줄래요", "해볼까", "할까요", "갈까요", "볼래요", "싶어요", "싶은데", "할거야", "하러가", "가고싶", "들어가", "열어줘",
        "합니다", "할께요", "갈게요", "가볼래", "볼게요", "해봐요", "해볼래", "주실래", "줄수있", "돌아가", "돌아갈래", "가고",
        "해줘", "해봐", "해요", "할래", "할게", "할께", "하자", "하러", "가줘", "가자", "갈래", "갈게", "가기", "줄래", "줘요", "볼래", "싶어",
        "보여", "알려", "말해", "들려", "열어", "켜줘", "꺼줘", "으로", "에서", "화면", "지금", "이제", "그냥", "제발", "한번", "좀더",
        "있어", "있나", "있니", "할까", "까요", "이야", "예요", "에요", "인가", "인데", "거야", "건데", "시작", "시선", "어서", "빨리",
        "좀", "해", "줘", "요", "로", "를", "을", "은", "는", "이", "가", "어", "야", "다", "나", "봐", "고", "에", "도", "만", "게", "랑", "와", "과"
    )

    /** 덮은 글자 표. 핵심어를 먼저 덮고([mark]), 남은 자리를 어미·조사로 채워([withFillers]) 비율을 잰다. */
    class Cover(val text: String) {
        private val covered = BooleanArray(text.length)

        /** [phrase]가 처음 나오는 곳(이미 덮인 곳과 겹치지 않는 첫 자리)을 덮고 덮은 글자 수를 돌려준다. 없으면 0. */
        fun mark(phrase: String): Int {
            if (phrase.isEmpty()) return 0
            var from = 0
            while (true) {
                val at = text.indexOf(phrase, from)
                if (at < 0) return 0
                if ((at until at + phrase.length).none { covered[it] }) {
                    covered.fill(true, at, at + phrase.length)
                    return phrase.length
                }
                from = at + 1
            }
        }

        /** 남은 글자를 어미·조사로 덮은 뒤의 덮은 비율. 글자가 없으면 0. */
        fun withFillers(): Float {
            if (text.isEmpty()) return 0f
            val copy = covered.copyOf()
            FILLERS.forEach { f ->
                var from = 0
                while (true) {
                    val at = text.indexOf(f, from)
                    if (at < 0) break
                    if ((at until at + f.length).none { copy[it] }) copy.fill(true, at, at + f.length)
                    from = at + 1
                }
            }
            return copy.count { it }.toFloat() / text.length
        }
    }

    /** 「첫 번째」「두 번째」「1번」「둘째」 → 0부터 센 차례. 없으면 null(고르기 답, §4.4). */
    fun ordinal(text: String): Int? {
        val c = compact(text)
        return ORDINALS.entries.firstOrNull { (words, _) -> words.any { c.contains(it) } }?.value
    }

    private val ORDINALS: Map<List<String>, Int> = linkedMapOf(
        listOf("세번째", "셋째", "3번", "세번", "삼번") to 2,
        listOf("두번째", "둘째", "2번", "두번", "이번째", "두개째", "뒤에거", "뒤에꺼", "뒤의것", "나중거", "나중꺼") to 1,
        listOf("첫번째", "첫째", "1번", "한번째", "일번", "처음거", "처음꺼", "앞에거", "앞에꺼", "앞의것", "첫번") to 0
    )
}
