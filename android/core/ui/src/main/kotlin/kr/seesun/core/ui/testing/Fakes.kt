package kr.seesun.core.ui.testing

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kr.seesun.core.voice.recognition.ListenOptions
import kr.seesun.core.voice.recognition.RecognitionError
import kr.seesun.core.voice.recognition.RecognitionEvent
import kr.seesun.core.voice.recognition.SpeechInput
import kr.seesun.core.voice.tts.Speaker

/**
 * 시험·미리보기용 음성 입력. [answers]에 넣은 말을 차례로 "인식"한다. 비어 있으면 무음 시간 초과로 실패한다.
 * (실기기 음성 인식은 core:voice SpeechInputDeviceTest가 따로 검사한다.)
 */
class ScriptedSpeechInput(vararg answers: String) : SpeechInput {
    val answers: ArrayDeque<List<String>> = ArrayDeque(answers.map { listOf(it) })
    var listenCount: Int = 0
        private set

    /** 후보 여러 개(인식기 n-best)를 한 번에 넣는다. */
    fun enqueue(vararg candidates: String) = answers.addLast(candidates.toList())

    override fun isAvailable(): Boolean = true

    override fun listen(options: ListenOptions): Flow<RecognitionEvent> = flow {
        listenCount++
        emit(RecognitionEvent.Ready(onDevice = true))
        emit(RecognitionEvent.SpeechStarted)
        val next = answers.removeFirstOrNull()
        emit(RecognitionEvent.SpeechEnded)
        if (next == null) emit(RecognitionEvent.Failed(RecognitionError.SPEECH_TIMEOUT)) else emit(RecognitionEvent.Final(next))
    }
}

/** 말한 내용을 기록만 하는 안내 음성. TalkBack 흉내([talkBack])가 켜져 있으면 말하지 않는다. */
class RecordingSpeaker(var talkBack: Boolean = false) : Speaker {
    val spoken: MutableList<String> = mutableListOf()
    var stopCount: Int = 0
        private set

    override fun speak(text: String, flush: Boolean): Boolean {
        if (talkBack) return false
        spoken += text
        return true
    }

    override fun stop() {
        stopCount++
    }

    override fun isSpeaking(): Boolean = false
}
