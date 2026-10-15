package kr.seesun.core.feedback

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 운동 진행 신호(EX-09 "공통 모듈 통일: 소리·진동"). 화면을 보지 않고 운동할 때 단계가 바뀐 것을 손으로 알 수 있게 한다.
 *
 * 경고·저장용 5패턴([HapticPattern], 03 §6)과 겹치지 않도록 횟수·길이를 달리 잡았다
 * (5패턴: 1회 30·50·600ms, 2회 600ms, 3회 100ms). 검수⑤ 사용자 테스트에서 함께 조정한다.
 *
 * @param timingsMs VibrationEffect.createWaveform 형식 [대기, 진동, 대기, 진동, …]
 */
enum class ExerciseCue(val timingsMs: LongArray, val spokenPrefix: String) {
    /** 운동(안내) 시작: 짧게 2회. */
    START(longArrayOf(0, 80, 120, 80), "운동 시작"),

    /** 휴식 시작: 중간 길이 1회(300ms). */
    REST(longArrayOf(0, 300), "휴식"),

    /** 휴식 끝나기 3·2·1초: 아주 짧게 1회. */
    COUNTDOWN(longArrayOf(0, 40), "곧 시작"),

    /** 다음 운동으로 넘어감: 짧게 4회. */
    NEXT(longArrayOf(0, 60, 80, 60, 80, 60, 80, 60), "다음 운동"),

    /** 운동·루틴 완료: 짧게-짧게-길게. */
    DONE(longArrayOf(0, 150, 100, 150, 100, 450), "운동 완료");

    val pulseCount: Int get() = timingsMs.size / 2
}

/** 운동 신호 진동 창구(테스트에서 기록용으로 바꿔 끼운다). */
interface ExerciseHaptics {
    fun cue(cue: ExerciseCue)
}

@Singleton
class AndroidExerciseHaptics @Inject constructor(@ApplicationContext context: Context) : ExerciseHaptics {
    private val vibrator: Vibrator? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        context.getSystemService(VibratorManager::class.java)?.defaultVibrator
    } else {
        @Suppress("DEPRECATION")
        context.getSystemService(Vibrator::class.java)
    }

    override fun cue(cue: ExerciseCue) {
        val v = vibrator ?: return
        if (v.hasVibrator()) v.vibrate(VibrationEffect.createWaveform(cue.timingsMs, -1))
    }
}

@Module
@InstallIn(SingletonComponent::class)
internal abstract class ExerciseHapticsModule {
    @Binds
    abstract fun bind(impl: AndroidExerciseHaptics): ExerciseHaptics
}
