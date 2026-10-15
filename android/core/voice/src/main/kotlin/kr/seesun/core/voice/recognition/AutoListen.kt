package kr.seesun.core.voice.recognition

/**
 * 음성 자동 인식(소유자 결정 2026-10-15 msg 920): 화면 안내 음성이 끝나면 마이크를 저절로 켜되, 사람 말소리로 알아들은
 * 쓸 수 있는 말에만 반응한다. 잡음·무음에는 아무 말도, 아무 소리도, 아무 동작도 하지 않고 조용히 다시 듣거나 멈춘다.
 *
 * 걸러 내는 순서(인식기가 주는 신호만 쓴다 — 마이크를 인식기와 동시에 따로 열 수 없어 자체 VAD는 두지 않는다):
 * 1. 말소리 흔적([SpeechEvidence]): 말 시작(onBeginningOfSpeech)·중간 결과(partial)·소리 크기 상승 중 하나도 없으면 버린다.
 *    소리 크기(RMS)를 받았는데 바닥보다 [MIN_RMS_RISE_DB] 이상 오른 적이 없으면(일정한 웅웅거림) 버린다.
 * 2. 신뢰도: 첫 후보 신뢰도를 인식기가 줬는데 [MIN_CONFIDENCE]보다 낮으면 버린다. 신뢰도가 0 이하(모름)면 이 단계는 건너뛴다.
 * 3. 화면 맞춤: 남은 후보를 화면의 명령·입력 해석기에 넘겨 아무것도 맞지 않으면 버린다(화면 쪽에서 판단).
 * 4. 무음·못 알아들음·클라이언트 오류는 오류 안내 없이 다시 듣는다. 권한·인식기 없음·네트워크 오류면 자동 듣기를 멈춘다.
 * 5. 다시 듣기는 [AutoListenBudget] 안에서만: 조용한 회차가 [AutoListenBudget.maxSilentCycles]번이거나 들은 시간이
 *    [AutoListenBudget.maxListenMs]를 넘으면 멈추고 보통 마이크 버튼으로 돌아간다(전지·인식기 시작음 반복 방지).
 */
enum class AutoListenOutcome {
    /** 화면이 말을 받아 처리했다. */
    ACCEPTED,

    /** 결과는 왔지만 말소리 흔적·신뢰도·화면 맞춤에서 걸렀다(조용히 다시 듣기). */
    IGNORED,

    /** 무음·못 알아들음·클라이언트 오류(조용히 다시 듣기). */
    SILENT,

    /** 안내 음성이 시작돼 듣기를 끊었다(반이중). 회차로 세지 않는다. */
    INTERRUPTED,

    /** 권한 없음·인식기 없음·언어 없음·네트워크 — 자동 듣기를 멈춘다(오류 안내 없이). */
    FATAL,

    /** 사용자가 마이크 버튼으로 멈췄거나, 이미 수동으로 듣는 중이었다. 자동 듣기를 멈춘다. */
    USER_STOPPED
}

/**
 * 한 번 켜진 자동 듣기의 한도.
 * @param maxSilentCycles 조용히 끝난 회차(무음·걸러냄)가 이만큼이면 멈춘다
 * @param maxListenMs 실제로 들은 시간 합계 한도(안내를 기다린 시간은 넣지 않는다)
 * @param maxCycleMs 한 번 듣기의 한도. 잡음을 말 시작으로 착각한 인식기는 1분까지 듣기도 해서(에뮬레이터 실측 60초) 끊고 조용한 회차로 센다
 * @param settleMs 안내 음성이 끝난 뒤 이만큼 조용해야 마이크를 켠다(문장 사이 쉼에 켜지지 않게)
 * @param backoffMs 다시 듣기 전 쉼(회차마다 늘어난다)
 */
data class AutoListenBudget(
    val maxSilentCycles: Int = 3,
    val maxListenMs: Long = 30_000L,
    val maxCycleMs: Long = 15_000L,
    val settleMs: Long = 600L,
    val backoffMs: Long = 400L
)

/** 한 번 듣는 동안 모은 말소리 흔적. */
class SpeechEvidence {
    var speechStarted: Boolean = false
        private set
    var partials: Int = 0
        private set
    var levelCount: Int = 0
        private set
    var minRms: Float = Float.POSITIVE_INFINITY
        private set
    var maxRms: Float = Float.NEGATIVE_INFINITY
        private set

    fun record(event: RecognitionEvent) {
        when (event) {
            RecognitionEvent.SpeechStarted -> speechStarted = true

            is RecognitionEvent.Partial -> if (event.text.isNotBlank()) partials++

            is RecognitionEvent.Level -> {
                levelCount++
                minRms = minOf(minRms, event.rmsDb)
                maxRms = maxOf(maxRms, event.rmsDb)
            }

            else -> Unit
        }
    }

    /** 소리 크기를 충분히 받았는가(받지 못한 인식기는 크기 검사를 건너뛴다). */
    val hasLevels: Boolean get() = levelCount >= AutoListenPolicy.MIN_LEVEL_SAMPLES

    val rmsRise: Float get() = if (levelCount == 0) 0f else maxRms - minRms
}

object AutoListenPolicy {
    /** 첫 후보 신뢰도 하한. Google 인식기는 또렷한 말에 0.7~0.95, 잡음에서 지어낸 말에 0.1~0.4 정도를 준다. */
    const val MIN_CONFIDENCE = 0.4f

    /** 말소리라면 바닥 소음보다 이만큼(dB) 이상 커진 순간이 있다. 낮게 잡아 작은 목소리를 놓치지 않게 한다. */
    const val MIN_RMS_RISE_DB = 3f

    /** 소리 크기 검사를 하려면 받아야 하는 최소 표본 수. */
    const val MIN_LEVEL_SAMPLES = 5

    /** 신뢰도 값이 실제 값인가(0 이하·1 초과는 "모름"으로 본다 — 일부 인식기는 다른 후보에 0을 준다). */
    fun isKnown(confidence: Float?): Boolean = confidence != null && confidence > 0f && confidence <= 1f

    /** 말소리였다고 볼 만한가. */
    fun heardSpeech(evidence: SpeechEvidence): Boolean {
        val anySign = evidence.speechStarted || evidence.partials > 0 || (evidence.hasLevels && evidence.rmsRise >= MIN_RMS_RISE_DB)
        if (!anySign) return false
        // 크기를 받았는데 오른 적이 없으면 일정한 소음(선풍기·웅웅거림)에서 나온 결과로 본다.
        return !(evidence.hasLevels && evidence.rmsRise < MIN_RMS_RISE_DB)
    }

    /**
     * 자동 듣기에서 화면에 넘길 후보. 비어 있으면 버린다(조용히 다시 듣기).
     * 첫 후보 신뢰도가 낮으면 전부 버리고, 다른 후보는 신뢰도를 모르거나 하한 이상인 것만 남긴다.
     */
    fun acceptedCandidates(result: RecognitionEvent.Final, evidence: SpeechEvidence): List<String> {
        if (!heardSpeech(evidence)) return emptyList()
        val top = result.confidences.firstOrNull()
        if (isKnown(top) && top!! < MIN_CONFIDENCE) return emptyList()
        return result.candidates.filterIndexed { i, text ->
            val c = result.confidences.getOrNull(i)
            text.isNotBlank() && (!isKnown(c) || c!! >= MIN_CONFIDENCE)
        }
    }

    /** 오류를 결과로 바꾼다: 무음·못 알아들음·바쁨·기타(클라이언트·오디오)는 조용히, 나머지는 자동 듣기 멈춤. */
    fun outcomeOf(error: RecognitionError): AutoListenOutcome = when (error) {
        RecognitionError.NO_MATCH, RecognitionError.SPEECH_TIMEOUT, RecognitionError.BUSY, RecognitionError.OTHER ->
            AutoListenOutcome.SILENT

        RecognitionError.NETWORK, RecognitionError.PERMISSION, RecognitionError.LANGUAGE_UNAVAILABLE, RecognitionError.UNAVAILABLE ->
            AutoListenOutcome.FATAL
    }

    /** 한 번 더 들어도 되는가. */
    fun mayListenAgain(silentCycles: Int, listenedMs: Long, budget: AutoListenBudget): Boolean =
        silentCycles < budget.maxSilentCycles && listenedMs < budget.maxListenMs

    /** 다시 듣기 전 쉼: 조용한 회차가 쌓일수록 늘린다. */
    fun backoffMs(silentCycles: Int, budget: AutoListenBudget): Long = budget.backoffMs * silentCycles.coerceAtLeast(1)
}
