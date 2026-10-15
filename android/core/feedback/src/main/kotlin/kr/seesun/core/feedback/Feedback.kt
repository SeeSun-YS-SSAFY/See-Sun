package kr.seesun.core.feedback

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

/** 진동을 실제로 내는 쪽(기기·테스트 대역 교체용). */
interface Haptics {
    val hasVibrator: Boolean

    fun vibrate(pattern: HapticPattern)

    fun cancel()

    /** 버튼 누름 확인용 아주 짧은 진동 한 번(누름 알림, v6). 5가지 신호 패턴과 헷갈리지 않게 따로 둔다. */
    fun tick() = Unit
}

/** VibrationEffect.createWaveform으로 5패턴을 낸다. */
@Singleton
class AndroidHaptics @Inject constructor(@ApplicationContext context: Context) : Haptics {

    private val vibrator: Vibrator? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        context.getSystemService(VibratorManager::class.java)?.defaultVibrator
    } else {
        @Suppress("DEPRECATION")
        context.getSystemService(Vibrator::class.java)
    }

    override val hasVibrator: Boolean get() = vibrator?.hasVibrator() == true

    override fun vibrate(pattern: HapticPattern) {
        val v = vibrator ?: return
        if (!v.hasVibrator()) return
        v.vibrate(VibrationEffect.createWaveform(pattern.timingsMs, NO_REPEAT))
    }

    override fun cancel() {
        vibrator?.cancel()
    }

    override fun tick() {
        val v = vibrator ?: return
        if (!v.hasVibrator()) return
        val effect = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            VibrationEffect.createPredefined(VibrationEffect.EFFECT_CLICK)
        } else {
            VibrationEffect.createOneShot(TICK_MS, VibrationEffect.DEFAULT_AMPLITUDE)
        }
        v.vibrate(effect)
    }

    private companion object {
        const val NO_REPEAT = -1
        const val TICK_MS = 20L
    }
}

/**
 * 진동 + 음성 접두어를 한 번에 다루는 피드백 창구.
 * 음성은 호출한 화면이 TalkBack 여부(core:a11y GuidancePolicy)에 따라 liveRegion 또는 TTS로 낸다.
 */
@Singleton
class Feedback @Inject constructor(private val haptics: Haptics) {

    /** 진동을 내고, 함께 말할 접두어를 돌려준다. */
    fun signal(pattern: HapticPattern): String {
        haptics.vibrate(pattern)
        return pattern.spokenPrefix
    }

    /** 서버 경고 코드로 진동. 모르는 코드는 무시하고 null. */
    fun signalApiCode(code: String?): String? = HapticPattern.fromApiCode(code)?.let(::signal)

    /** 로딩 중 3초마다 짧게 진동한다. 코루틴이 취소되면 멈춘다. */
    suspend fun loadingTicks(intervalMs: Long = HapticPattern.LOADING_INTERVAL_MS) {
        while (coroutineContext.isActive) {
            haptics.vibrate(HapticPattern.LOADING)
            delay(intervalMs)
        }
    }

    fun stop() = haptics.cancel()
}
