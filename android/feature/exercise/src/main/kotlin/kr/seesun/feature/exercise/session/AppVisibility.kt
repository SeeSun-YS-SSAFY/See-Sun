package kr.seesun.feature.exercise.session

import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import androidx.core.content.ContextCompat
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/** 앱이 화면에서 사라진 까닭. */
enum class AppStop {
    /** 홈·다른 앱·최근 앱으로 나갔다 → 안내를 멈추고, 돌아오면 「이어서 시작」을 보여 준다. */
    LEFT_APP,

    /** 앱을 띄운 채 전원 버튼으로 화면을 끄거나 잠갔다 → 안내를 계속한다(포그라운드 서비스). */
    SCREEN_OFF
}

/** 앱 화면 보임 변화(ProcessLifecycleOwner 기준). */
sealed interface AppVisibilityEvent {
    data object Started : AppVisibilityEvent

    data class Stopped(val why: AppStop) : AppVisibilityEvent
}

/** 운동 화면이 듣는 앱 보임 변화(기기 테스트는 가짜로 바꾼다). */
interface AppVisibility {
    val events: SharedFlow<AppVisibilityEvent>
}

/**
 * 운동 중 앱 상태 규칙(소유자 결정 2026-10-15 msg 920, 백그라운드 재생 (a)).
 *
 * 화면을 끄면 액티비티도 멈추므로(ON_STOP) 그 순간의 화면 상태로 둘을 가른다. ProcessLifecycleOwner는 마지막 액티비티가 멈추고
 * 약 0.7초 뒤에 ON_STOP을 알리므로, 전원 버튼으로 끈 경우에는 이미 [PowerManager.isInteractive]가 false다. 그래도 기기마다
 * 화면 꺼짐 처리 순서가 달라 최근 [SCREEN_OFF_GRACE_MS] 안에 화면 꺼짐 방송을 받았거나 잠금 화면이 떠 있으면 화면 꺼짐으로 본다.
 */
object ExerciseForegroundPolicy {
    const val SCREEN_OFF_GRACE_MS = 3_000L

    /**
     * @param interactive 지금 화면이 켜져 상호작용 중인가([PowerManager.isInteractive])
     * @param keyguardLocked 잠금 화면이 떠 있는가([KeyguardManager.isKeyguardLocked])
     * @param msSinceScreenOff 마지막 화면 꺼짐 방송 뒤 지난 시간(받은 적 없으면 null)
     */
    fun classifyStop(interactive: Boolean, keyguardLocked: Boolean, msSinceScreenOff: Long?): AppStop = when {
        !interactive -> AppStop.SCREEN_OFF
        keyguardLocked -> AppStop.SCREEN_OFF
        msSinceScreenOff != null && msSinceScreenOff in 0..SCREEN_OFF_GRACE_MS -> AppStop.SCREEN_OFF
        else -> AppStop.LEFT_APP
    }

    /** 한 가지 운동 화면: 세션 중이고 결과 화면·안내 끝이 아니면 화면을 켜 둔다. */
    fun keepScreenOnSingle(sessionActive: Boolean, showingResult: Boolean, guideFinished: Boolean): Boolean =
        sessionActive && !showingResult && !guideFinished

    /** 루틴 화면: 운동·휴식 단계 동안 화면을 켜 둔다(준비·완료 단계는 놓아 준다). */
    fun keepScreenOnRoutine(sessionActive: Boolean, exerciseOrRest: Boolean): Boolean = sessionActive && exerciseOrRest
}

@Singleton
class ProcessAppVisibility @Inject constructor(@ApplicationContext private val context: Context) : AppVisibility {
    private val _events = MutableSharedFlow<AppVisibilityEvent>(extraBufferCapacity = EVENT_BUFFER)
    override val events: SharedFlow<AppVisibilityEvent> = _events.asSharedFlow()

    @Volatile private var lastScreenOffAt: Long? = null

    private val screenOff = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == Intent.ACTION_SCREEN_OFF) lastScreenOffAt = SystemClock.elapsedRealtime()
        }
    }

    private val observer = object : DefaultLifecycleObserver {
        override fun onStart(owner: LifecycleOwner) {
            // 다시 보이게 됐으면 앞선 화면 꺼짐은 끝난 일이다(잠금 해제 직후 홈으로 나가는 것을 화면 꺼짐으로 보지 않게).
            lastScreenOffAt = null
            _events.tryEmit(AppVisibilityEvent.Started)
        }

        override fun onStop(owner: LifecycleOwner) {
            _events.tryEmit(AppVisibilityEvent.Stopped(classifyNow()))
        }
    }

    init {
        ContextCompat.registerReceiver(context, screenOff, IntentFilter(Intent.ACTION_SCREEN_OFF), ContextCompat.RECEIVER_NOT_EXPORTED)
        val register = { ProcessLifecycleOwner.get().lifecycle.addObserver(observer) }
        if (Looper.myLooper() == Looper.getMainLooper()) register() else Handler(Looper.getMainLooper()).post(register)
    }

    private fun classifyNow(): AppStop {
        val power = context.getSystemService(PowerManager::class.java)
        val keyguard = context.getSystemService(KeyguardManager::class.java)
        val since = lastScreenOffAt?.let { SystemClock.elapsedRealtime() - it }
        return ExerciseForegroundPolicy.classifyStop(
            interactive = power?.isInteractive ?: true,
            keyguardLocked = keyguard?.isKeyguardLocked ?: false,
            msSinceScreenOff = since
        )
    }

    private companion object {
        const val EVENT_BUFFER = 8
    }
}

@Module
@InstallIn(SingletonComponent::class)
abstract class AppVisibilityModule {
    @Binds
    abstract fun appVisibility(impl: ProcessAppVisibility): AppVisibility
}
