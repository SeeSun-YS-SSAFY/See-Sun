package kr.seesun.core.voice.recognition

/**
 * 앱이 방금 한 말·화면 글자·화면 낭독기의 상태 낭독("음성 입력, 녹음 중")을 기억한다(되울림 필터 재료, 연구 문서 §6 P0-1·§4.4).
 * [kr.seesun.core.voice.tts.AndroidGuidanceSpeaker]가 발화를, 화면 틀이 제목과 진입 안내를 넣는다. 메인 스레드 밖에서도 읽으므로 잠근다.
 * 말 내용은 기기 메모리에만 잠깐 두고 로그로 남기지 않는다.
 */
object ScreenSpeechLog {
    private data class Entry(val text: String, val at: Long)

    private val lock = Any()
    private val utterances = ArrayDeque<Entry>()
    private var guidance: Entry? = null
    private var title: Entry? = null

    /** 앱 안내 음성이 말한 문장. */
    fun onAppSpoke(text: String, at: Long) = synchronized(lock) {
        if (text.isBlank()) return
        utterances.addLast(Entry(text, at))
        while (utterances.size > MAX_UTTERANCES) utterances.removeFirst()
    }

    /** 화면의 진입·상태 안내(TalkBack 여부와 관계없이 넣는다 — [화면 다시 듣기]와 되울림 필터가 쓴다). */
    fun onGuidance(text: String, at: Long) = synchronized(lock) {
        if (text.isNotBlank()) guidance = Entry(text, at)
    }

    /** 화면 제목(화면 틀이 화면에 들어갈 때). */
    fun onScreen(screenTitle: String, at: Long) = synchronized(lock) {
        title = Entry(screenTitle, at)
    }

    /** [since] 뒤에 들어온 가장 최근 안내(없으면 null) — 헤더 [화면 다시 듣기]가 읽는다. */
    fun guidanceSince(since: Long): String? = synchronized(lock) { guidance?.takeIf { it.at >= since }?.text }

    /** 되울림으로 볼 글자: 직전 [windowMs] 안의 앱 발화 + 지금 안내 + 화면 제목. */
    fun echoSources(now: Long, windowMs: Long = EchoFilter.APP_SPEECH_WINDOW_MS): List<String> = synchronized(lock) {
        utterances.filter { now - it.at <= windowMs }.map { it.text } + listOfNotNull(guidance?.text, title?.text)
    }

    /** 시험용. */
    fun clear() = synchronized(lock) {
        utterances.clear()
        guidance = null
        title = null
    }

    private const val MAX_UTTERANCES = 8
}

/**
 * 되울림 필터(순수 함수, 시험용): 인식 결과가 앱 자신의 소리이거나 화면 낭독기의 상태 낭독이면 버린다.
 *
 * 비교는 띄어쓰기·문장부호를 지운 뒤 한다. 결과가 (1) 앱 발화·화면 안내·화면 제목과 통째로 같거나, (2) 앱 발화·안내 한 문장을
 * 통째로 품고 있거나(긴 문장을 다시 들은 것), (3) 화면 낭독기의 마이크 상태 낭독·역할 낱말만으로 이루어져 있으면 되울림으로 본다.
 * 안내 문장의 **일부**와 같은 말("…마이페이지 중에서 골라 주세요" 뒤의 "마이페이지")은 사용자의 답이므로 버리지 않는다.
 */
object EchoFilter {
    /** 직전 앱 발화를 되울림으로 볼 시간(레퍼런스 확인 문서 §4.4: 10초). */
    const val APP_SPEECH_WINDOW_MS = 10_000L

    /** 통째로 품은 문장으로 볼 최소 길이(정규화한 글자 수). 짧은 낱말을 품었다고 버리지 않게. */
    private const val MIN_SENTENCE = 6

    /** 화면 낭독기가 마이크를 누른 순간 읽는 말과 역할 낱말(TalkBack 한국어). 앱 MicState 문구도 함께. */
    val SCREEN_READER_PHRASES = listOf(
        "음성 입력 시작", "음성 입력 멈추기", "음성 입력", "음성 인식 중", "녹음 중", "인식 중", "대기 중", "듣는 중",
        "누르고 말씀해 주세요", "듣고 있어요", "다 말씀하시면 눌러 주세요", "잠시만 기다려 주세요", "말씀하세요",
        "버튼", "두 번 탭하여 활성화", "두 번 탭하여", "활성화", "선택됨", "선택 안 됨", "사용 중지됨"
    )

    fun normalize(text: String): String = text.lowercase().replace(Regex("[\\s\\p{P}\\p{S}]"), "")

    fun isEcho(candidate: String, sources: List<String>): Boolean {
        val c = normalize(candidate)
        if (c.isEmpty()) return true
        val known = sources.map(::normalize).filter { it.isNotEmpty() }
        if (known.any { it == c }) return true
        if (known.any { it.length >= MIN_SENTENCE && c.contains(it) }) return true
        var rest = c
        SCREEN_READER_PHRASES.map(::normalize).sortedByDescending { it.length }.forEach { rest = rest.replace(it, "") }
        return rest.isEmpty()
    }

    /** 되울림이 아닌 후보만 남긴다(신뢰도는 같은 순서로 따라간다). */
    fun filter(final: RecognitionEvent.Final, sources: List<String>): RecognitionEvent.Final {
        val keep = final.candidates.indices.filterNot { isEcho(final.candidates[it], sources) }
        return RecognitionEvent.Final(
            candidates = keep.map { final.candidates[it] },
            confidences = keep.mapNotNull { final.confidences.getOrNull(it) }.takeIf { it.size == keep.size }.orEmpty()
        )
    }
}
