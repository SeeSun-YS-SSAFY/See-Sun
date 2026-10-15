package kr.seesun.core.ui

import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.KeyEvent
import kr.seesun.core.voice.recognition.VOICE_LOG_TAG

/**
 * 이어폰·블루투스 미디어 버튼과 TalkBack 두 손가락 두 번 탭(매직 탭 = 헤드셋 훅 키)으로 「바로 듣기」(레퍼런스 확인 §4.2 T4·T5).
 *
 * - 음성 화면이 앞에 있는 동안만([AutoListenEnvironment.directStart]에 화면이 있을 때) 세션을 켠다. 운동 재생 화면은 등록하지 않으므로
 *   그 화면의 이어폰 버튼은 운동 재생기(Media3 세션)의 재생/일시정지 그대로다.
 * - 키를 받으면 마이크 버튼을 누른 것과 같다(듣는 중이면 멈춤, 아니면 듣기 시작 — 화면 낭독기가 켜져 있으면 신호음 뒤 지연 시작).
 *   쉬던 「말하면 듣기」도 깨운다.
 * - 안드로이드는 미디어 키를 「최근에 소리를 낸 앱」의 세션에만 보낸다. 앱 안내 음성(TTS)은 TTS 엔진 앱이 내므로 우리 앱이 소리를 낸
 *   것으로 치지 않는다(에뮬레이터 실측: Media button session = null). 그래서 세션을 켤 때 아주 짧은 무음을 우리 앱에서 한 번 재생해
 *   미디어 키를 받을 앱이 되게 한다(포커스를 잡지 않아 다른 앱 음악에 영향 없음). 다른 앱이 그 뒤에 음악을 틀면 키는 그 앱으로 간다
 *   (실기기 확인 필요, 레퍼런스 확인 §5 R4·R5).
 */
class VoiceMediaButtons(private val context: Context, private val env: AutoListenEnvironment) {
    private var session: MediaSession? = null

    /** 키 하나를 처리했으면 true(시험용으로 따로 둔다). */
    fun handleKey(event: KeyEvent): Boolean {
        if (event.action != KeyEvent.ACTION_DOWN || event.repeatCount > 0) return event.keyCode in KEYS
        if (event.keyCode !in KEYS) return false
        trigger("key ${KeyEvent.keyCodeToString(event.keyCode)}")
        return true
    }

    private fun trigger(source: String) {
        env.wake("media-button")
        val handled = env.directStart.trigger()
        Log.i(TAG, "media button ($source) -> direct start handled=$handled")
    }

    /** 음성 화면이 앞에 있으면 true로 부른다. */
    fun setActive(active: Boolean) {
        if (active) {
            val s = session ?: create().also { session = it }
            s.isActive = true
            playSilence()
            Log.i(TAG, "voice media session active")
        } else {
            session?.let {
                it.isActive = false
                it.release()
                Log.i(TAG, "voice media session released")
            }
            session = null
        }
    }

    /** 무음 0.2초(미디어 키 받을 앱 되기). 실패해도 조용히 넘어간다. */
    private fun playSilence() {
        runCatching {
            val bytes = SILENCE_SAMPLES * 2
            val track = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder().setUsage(
                        AudioAttributes.USAGE_MEDIA
                    ).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setSampleRate(SILENCE_RATE)
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build()
                )
                .setTransferMode(AudioTrack.MODE_STATIC)
                .setBufferSizeInBytes(bytes)
                .build()
            track.write(ByteArray(bytes), 0, bytes)
            track.play()
            Handler(Looper.getMainLooper()).postDelayed({ runCatching { track.release() } }, SILENCE_RELEASE_MS)
        }.onFailure { Log.w(TAG, "silence prime failed: ${it.javaClass.simpleName}") }
    }

    private fun create(): MediaSession = MediaSession(context, "seesun-voice").apply {
        setPlaybackState(
            PlaybackState.Builder()
                .setActions(PlaybackState.ACTION_PLAY_PAUSE or PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE)
                .setState(PlaybackState.STATE_PAUSED, 0L, 1f)
                .build()
        )
        setCallback(
            object : MediaSession.Callback() {
                override fun onMediaButtonEvent(mediaButtonIntent: Intent): Boolean {
                    val event = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        mediaButtonIntent.getParcelableExtra(Intent.EXTRA_KEY_EVENT, KeyEvent::class.java)
                    } else {
                        @Suppress("DEPRECATION")
                        mediaButtonIntent.getParcelableExtra(Intent.EXTRA_KEY_EVENT)
                    }
                    return event?.let(::handleKey) ?: super.onMediaButtonEvent(mediaButtonIntent)
                }

                // 미디어 컨트롤러(알림·워치·`cmd media_session dispatch`)에서 온 재생/일시정지.
                override fun onPlay() = trigger("onPlay")

                override fun onPause() = trigger("onPause")
            }
        )
    }

    private companion object {
        const val TAG = VOICE_LOG_TAG
        const val SILENCE_RATE = 16_000
        const val SILENCE_SAMPLES = 3_200 // 0.2초
        const val SILENCE_RELEASE_MS = 600L
        val KEYS = setOf(
            KeyEvent.KEYCODE_HEADSETHOOK,
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
            KeyEvent.KEYCODE_MEDIA_PLAY,
            KeyEvent.KEYCODE_MEDIA_PAUSE
        )
    }
}
