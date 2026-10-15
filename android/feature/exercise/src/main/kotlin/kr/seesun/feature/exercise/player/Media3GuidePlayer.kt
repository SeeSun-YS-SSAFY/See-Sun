package kr.seesun.feature.exercise.player

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.session.MediaSession
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kr.seesun.core.data.model.GuideSection

/**
 * Media3 ExoPlayer로 구간 mp3를 재생하고, 음성 파일이 없거나 못 불러오면 기기 TTS로 대본을 문장별로 읽는다.
 *
 * - 오디오 포커스를 직접 다룬다(전화가 오면 멈춤, 일시적 손실이면 끝난 뒤 이어서). 이어폰이 빠지면 멈춘다.
 * - 영구적으로 포커스를 잃으면 [GuideStatus.INTERRUPTED] — 사용자가 [재생]을 눌러야 이어진다.
 * - 콘텐츠 종류를 "말"(SPEECH)이 아니라 일반 미디어로 둔다: SPEECH면 TalkBack이 말할 때마다(일시적 포커스 요청)
 *   재생이 멈추고, 상태 글자(liveRegion)가 바뀌어 TalkBack이 또 말하는 반복이 생겼다(실기기 QA에서 발견).
 *   일반 미디어는 TalkBack 낭독 때 소리를 줄였다(ducking) 이어 가고, 전화처럼 소리를 완전히 가져가면 멈춘다.
 */
@OptIn(UnstableApi::class)
class Media3GuidePlayer(context: Context, mediaSourceFactory: MediaSource.Factory) : GuidePlayer {

    private val scope = MainScope()
    private val _state = MutableStateFlow(GuideState())
    override val state: StateFlow<GuideState> = _state.asStateFlow()

    private val exo: ExoPlayer = ExoPlayer.Builder(context.applicationContext)
        .setMediaSourceFactory(mediaSourceFactory)
        .setAudioAttributes(
            AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build(),
            true
        )
        .setHandleAudioBecomingNoisy(true)
        .setWakeMode(C.WAKE_MODE_NETWORK)
        .build()

    /** 이어폰·블루투스 버튼(재생/일시정지)을 받는 미디어 세션. 화면을 보지 않고 바로 멈출 수 있게(T-08 즉시 중단). */
    private val session: MediaSession = MediaSession.Builder(context.applicationContext, exo)
        .setId("seesun-guide-${SESSION_IDS.incrementAndGet()}")
        .build()

    private val tts = SentenceSpeaker(context.applicationContext) {
        // 대본 읽기 중 전화 등으로 포커스를 잃으면 mp3와 같이 [재생]을 눌러야 이어진다.
        scope.launch { if (_state.value.status == GuideStatus.PLAYING) setStatus(GuideStatus.INTERRUPTED) }
    }
    private var timeline: ScriptTimeline = ScriptTimeline.of("")
    private var ticker: Job? = null
    private val pendingSeek = PendingSeek()

    /** 서버 음성을 못 불러온 구간(이후 TTS로 읽음). */
    private val failedSections = mutableSetOf<Int>()

    private val usingTts: Boolean get() = _state.value.usingDeviceVoice

    init {
        exo.addListener(
            object : Player.Listener {
                override fun onPlaybackStateChanged(playbackState: Int) {
                    when (playbackState) {
                        Player.STATE_BUFFERING -> if (exo.playWhenReady) setStatus(GuideStatus.LOADING)
                        Player.STATE_READY -> pendingSeek.take(timeline, exo.duration.takeIf { it != C.TIME_UNSET } ?: 0L)?.let(exo::seekTo)
                        Player.STATE_ENDED -> onSectionEnded()
                        else -> Unit
                    }
                }

                override fun onIsPlayingChanged(isPlaying: Boolean) {
                    if (usingTts) return
                    if (isPlaying) {
                        setStatus(GuideStatus.PLAYING)
                        startTicker()
                    } else {
                        stopTicker()
                        if (exo.playbackState == Player.STATE_READY && _state.value.status == GuideStatus.PLAYING) {
                            setStatus(
                                if (exo.playbackSuppressionReason !=
                                    Player.PLAYBACK_SUPPRESSION_REASON_NONE
                                ) {
                                    GuideStatus.INTERRUPTED
                                } else {
                                    GuideStatus.PAUSED
                                }
                            )
                        }
                    }
                }

                override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                    val interrupted = reason == Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_FOCUS_LOSS ||
                        reason == Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_BECOMING_NOISY
                    if (!playWhenReady && interrupted && !usingTts) setStatus(GuideStatus.INTERRUPTED)
                }

                override fun onPlayerError(error: PlaybackException) {
                    // 음성 파일을 못 불러오면(비행기 모드·서버 꺼짐) 이 구간은 기기 음성으로 읽는다.
                    val index = _state.value.sectionIndex
                    failedSections += index
                    val resume = exo.playWhenReady
                    _state.update { it.copy(error = "안내 음성 파일을 불러오지 못해 기기 음성으로 읽어요.") }
                    startSection(index, autoplay = resume, sentence = _state.value.sentenceIndex)
                }
            }
        )
    }

    override fun load(sections: List<GuideSection>, startSection: Int) {
        stopEverything()
        failedSections.clear()
        _state.value = GuideState(sections = sections, speed = _state.value.speed)
        if (sections.isNotEmpty()) startSection(startSection.coerceIn(0, sections.lastIndex), autoplay = false)
    }

    override fun play() {
        val s = _state.value
        if (s.sections.isEmpty()) return
        if (s.status == GuideStatus.FINISHED) {
            startSection(0, autoplay = true)
            return
        }
        if (usingTts) {
            speakFrom(s.sentenceIndex)
        } else {
            if (exo.playbackState == Player.STATE_IDLE) exo.prepare()
            if (exo.playbackState == Player.STATE_ENDED) exo.seekTo(0)
            exo.play()
            if (exo.playbackState != Player.STATE_READY) setStatus(GuideStatus.LOADING)
        }
    }

    override fun pause() {
        if (usingTts) {
            tts.stop()
            setStatus(GuideStatus.PAUSED)
        } else {
            exo.pause()
            stopTicker()
            setStatus(GuideStatus.PAUSED)
        }
    }

    override fun replaySection() = startSection(_state.value.sectionIndex, autoplay = true)

    override fun nextSection() {
        val s = _state.value
        if (s.sectionIndex >= s.sections.lastIndex) {
            stopEverything()
            setStatus(GuideStatus.FINISHED)
        } else {
            startSection(s.sectionIndex + 1, autoplay = true)
        }
    }

    override fun previousSection() = startSection((_state.value.sectionIndex - 1).coerceAtLeast(0), autoplay = true)

    override fun seekTo(section: Int, sentence: Int) {
        val s = _state.value
        if (s.sections.isEmpty()) return
        val target = section.coerceIn(0, s.sections.lastIndex)
        val count = ScriptTimeline.split(s.sections[target].text).size
        val index = sentence.coerceIn(0, (count - 1).coerceAtLeast(0))
        val sameSection = target == s.sectionIndex && s.status != GuideStatus.FINISHED
        val duration = exo.duration
        when {
            sameSection && usingTts -> {
                _state.update { it.copy(sentenceIndex = index) }
                speakFrom(index)
            }

            sameSection && exo.playbackState == Player.STATE_READY && duration != C.TIME_UNSET && duration > 0 -> {
                pendingSeek.clear()
                exo.seekTo(timeline.startOf(index, duration))
                _state.update { it.copy(sentenceIndex = index) }
                if (!exo.isPlaying) play()
            }

            else -> startSection(target, autoplay = true, sentence = index)
        }
    }

    override fun setSpeed(speed: Float) {
        _state.update { it.copy(speed = speed) }
        exo.setPlaybackSpeed(speed)
        tts.rate = speed
        if (usingTts && _state.value.status == GuideStatus.PLAYING) speakFrom(_state.value.sentenceIndex)
    }

    override fun stop() {
        stopEverything()
        _state.value = GuideState(speed = _state.value.speed)
    }

    override fun release() {
        stopEverything()
        session.release()
        exo.release()
        tts.shutdown()
        scope.cancel()
    }

    private fun startSection(index: Int, autoplay: Boolean, sentence: Int = 0) {
        val sections = _state.value.sections
        val section = sections.getOrNull(index) ?: return
        stopEverything()
        timeline = ScriptTimeline.of(section.text)
        val deviceVoice = section.audioUrl == null || index in failedSections
        _state.update {
            it.copy(
                sectionIndex = index,
                sentenceIndex = sentence,
                usingDeviceVoice = deviceVoice,
                status = if (autoplay) GuideStatus.LOADING else GuideStatus.IDLE,
                error = if (deviceVoice) it.error else null
            )
        }
        if (deviceVoice) {
            if (autoplay) speakFrom(sentence)
        } else {
            pendingSeek.set(sentence)
            exo.setMediaItem(MediaItem.fromUri(section.audioUrl!!))
            exo.setPlaybackSpeed(_state.value.speed)
            exo.prepare()
            exo.playWhenReady = autoplay
        }
    }

    private fun speakFrom(sentence: Int) {
        val sentences = timeline.sentences
        if (sentences.isEmpty()) {
            onSectionEnded()
            return
        }
        setStatus(GuideStatus.PLAYING)
        tts.rate = _state.value.speed
        val ok = tts.speak(
            sentences = sentences,
            from = sentence.coerceIn(0, sentences.lastIndex),
            onSentence = { i -> scope.launch { _state.update { it.copy(sentenceIndex = i) } } },
            onDone = { scope.launch { if (_state.value.status == GuideStatus.PLAYING) onSectionEnded() } }
        )
        if (!ok) {
            _state.update { it.copy(status = GuideStatus.PAUSED, error = "기기 음성을 쓸 수 없어요. 화면의 대본 글자를 읽어 주세요.") }
        }
    }

    private fun onSectionEnded() {
        stopTicker()
        val s = _state.value
        if (s.sectionIndex < s.sections.lastIndex) {
            startSection(s.sectionIndex + 1, autoplay = true)
        } else {
            _state.update { it.copy(status = GuideStatus.FINISHED, sentenceIndex = timeline.sentences.lastIndex.coerceAtLeast(0)) }
        }
    }

    private fun startTicker() {
        if (ticker?.isActive == true) return
        ticker = scope.launch {
            while (isActive) {
                val duration = exo.duration
                if (duration != C.TIME_UNSET && duration > 0) {
                    val index = timeline.sentenceAt(exo.currentPosition, duration)
                    if (index != _state.value.sentenceIndex) _state.update { it.copy(sentenceIndex = index) }
                }
                delay(TICK_MS)
            }
        }
    }

    private fun stopTicker() {
        ticker?.cancel()
        ticker = null
    }

    private fun stopEverything() {
        stopTicker()
        pendingSeek.clear()
        tts.stop()
        exo.stop()
        exo.clearMediaItems()
    }

    private fun setStatus(status: GuideStatus) = _state.update { it.copy(status = status) }

    private companion object {
        const val TICK_MS = 200L
        val SESSION_IDS = AtomicInteger()
    }
}

/**
 * 기기 TTS로 문장을 하나씩 읽는다(문장마다 발화 ID를 달아 지금 문장을 정확히 안다).
 * 사용자가 [재생]을 눌러 시작하는 콘텐츠 재생이라 TalkBack이 켜져 있어도 읽는다(자동 낭독 아님, A9와 무관).
 */
internal class SentenceSpeaker(context: Context, private val onFocusLost: () -> Unit = {}) {
    private val audio = context.getSystemService(android.media.AudioManager::class.java)

    /** 운동 mp3와 같은 미디어 속성(연구 문서 §6 P0-2: TalkBack 덕킹 대상이 되게, CONTENT_TYPE_SPEECH는 덕킹 대신 멈춤을 부른다). */
    private val attributes = android.media.AudioAttributes.Builder()
        .setUsage(android.media.AudioAttributes.USAGE_MEDIA)
        .setContentType(android.media.AudioAttributes.CONTENT_TYPE_MUSIC)
        .build()

    /** 읽는 동안 미디어 포커스를 잡는다. 전화·다른 앱 재생으로 잃으면 멈추고 [onFocusLost](재생기는 INTERRUPTED). */
    private val focusRequest = android.media.AudioFocusRequest.Builder(android.media.AudioManager.AUDIOFOCUS_GAIN)
        .setAudioAttributes(attributes)
        .setOnAudioFocusChangeListener { change ->
            if (change == android.media.AudioManager.AUDIOFOCUS_LOSS || change == android.media.AudioManager.AUDIOFOCUS_LOSS_TRANSIENT) {
                stop()
                onFocusLost()
            }
        }
        .build()

    private var ready = false
    private var pending: (() -> Boolean)? = null
    private var generation = 0
    var rate: Float = 1f
        set(value) {
            field = value
            if (ready) tts.setSpeechRate(value)
        }

    private val tts: TextToSpeech = TextToSpeech(context) { status ->
        ready = status == TextToSpeech.SUCCESS && tts.setLanguage(Locale.KOREAN) >= TextToSpeech.LANG_AVAILABLE
        if (ready) {
            tts.setAudioAttributes(attributes)
            tts.setSpeechRate(rate)
            pending?.invoke()
        }
        pending = null
    }

    fun speak(sentences: List<String>, from: Int, onSentence: (Int) -> Unit, onDone: () -> Unit): Boolean {
        val run = speakAll@{
            val myGeneration = ++generation
            tts.setOnUtteranceProgressListener(
                object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String) {
                        parse(utteranceId, myGeneration)?.let(onSentence)
                    }

                    override fun onDone(utteranceId: String) {
                        if (parse(utteranceId, myGeneration) == sentences.lastIndex) {
                            audio?.abandonAudioFocusRequest(focusRequest)
                            onDone()
                        }
                    }

                    @Deprecated("API 21 미만 호환용", ReplaceWith(""))
                    override fun onError(utteranceId: String) = Unit
                }
            )
            tts.stop()
            if (audio != null && audio.requestAudioFocus(focusRequest) == android.media.AudioManager.AUDIOFOCUS_REQUEST_FAILED) {
                // 통화 중 등: 지금은 읽지 않는다(재생기가 멈춤 상태로 둔다).
                return@speakAll false
            }
            var ok = true
            for (i in from..sentences.lastIndex) {
                val mode = if (i == from) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD
                ok = ok && tts.speak(sentences[i], mode, null, "g$myGeneration-$i") == TextToSpeech.SUCCESS
            }
            ok
        }
        if (!ready) {
            pending = run
            return true
        }
        return run()
    }

    private fun parse(id: String, myGeneration: Int): Int? {
        val (g, i) = id.removePrefix("g").split('-').let { it.getOrNull(0)?.toIntOrNull() to it.getOrNull(1)?.toIntOrNull() }
        return if (g == myGeneration && g == generation) i else null
    }

    fun stop() {
        generation++
        pending = null
        if (ready) tts.stop()
        audio?.abandonAudioFocusRequest(focusRequest)
    }

    fun shutdown() {
        stop()
        tts.shutdown()
    }
}

/** 화면마다 새 재생기를 만든다(기기 테스트는 가짜 공장으로 바꾼다). */
fun interface GuidePlayerFactory {
    fun create(): GuidePlayer
}
