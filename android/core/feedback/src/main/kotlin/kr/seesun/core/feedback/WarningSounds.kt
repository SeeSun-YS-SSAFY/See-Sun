package kr.seesun.core.feedback

import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Handler
import android.os.Looper
import android.util.Log
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 식단 경고 소리(v2.0, UIUX 레퍼런스 §2.9 「첫 낱말·소리 높이·진동 리듬·확인 강도」를 모두 다르게).
 * - 알레르기: 높은 두 음(1200Hz 짧게 두 번) — 진동 [HapticPattern.ALLERGY_WARNING](짧게 3회)과 함께.
 * - 당류·탄수화물 지표: 낮은 한 음(길게 한 번) — 진동 [HapticPattern.SUGAR_HIGH]·[HapticPattern.SUGAR_VERY_HIGH]와 함께.
 * 소리는 보조다. 꺼지거나 실패해도 경고 글자·낭독은 그대로 나간다(Apple Playing haptics 원칙).
 */
enum class WarningSound(val toneType: Int, val durationMs: Int) {
    ALLERGY(ToneGenerator.TONE_PROP_BEEP2, ALLERGY_MS),
    SUGAR(ToneGenerator.TONE_CDMA_LOW_L, SUGAR_MS)
}

private const val ALLERGY_MS = 400
private const val SUGAR_MS = 500

/** 경고 소리를 내는 쪽(기기·시험 대역 교체용). */
interface WarningSounds {
    fun play(sound: WarningSound)
}

@Singleton
class ToneWarningSounds @Inject constructor() : WarningSounds {
    private val handler = Handler(Looper.getMainLooper())

    override fun play(sound: WarningSound) {
        runCatching {
            val tone = ToneGenerator(AudioManager.STREAM_NOTIFICATION, VOLUME)
            tone.startTone(sound.toneType, sound.durationMs)
            handler.postDelayed({ tone.release() }, sound.durationMs + RELEASE_GRACE_MS)
        }.onFailure { Log.w(TAG, "warning tone failed: ${it.javaClass.simpleName}") }
    }

    private companion object {
        const val TAG = "SeesunWarningSound"
        const val VOLUME = 80
        const val RELEASE_GRACE_MS = 100L
    }
}

@Module
@InstallIn(SingletonComponent::class)
internal abstract class WarningSoundsModule {
    @Binds
    abstract fun bind(impl: ToneWarningSounds): WarningSounds
}
