package kr.seesun.core.voice.lifecycle

import android.app.Activity
import android.app.Application
import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import androidx.core.content.ContextCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.CopyOnWriteArrayList
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kr.seesun.core.voice.recognition.SpeechInput
import kr.seesun.core.voice.tts.Speaker

/** 앱이 화면에서 사라진 까닭. */
enum class AppStop {
    /** 홈·다른 앱·최근 앱으로 나갔다 → 모든 안내 음성·듣기를 멈춘다(운동은 「이어서 시작」). */
    LEFT_APP,

    /** 앱을 띄운 채 전원 버튼으로 화면을 끄거나 잠갔다 → 운동 안내는 계속한다(포그라운드 서비스). */
    SCREEN_OFF
}

/** 앱 화면 보임 변화(앱 전체: 우리 앱 액티비티가 하나라도 보이는가). */
sealed interface AppVisibilityEvent {
    data object Started : AppVisibilityEvent

    data class Stopped(val why: AppStop) : AppVisibilityEvent
}

/** 앱 보임 변화 창구(기기 테스트는 가짜로 바꾼다). */
interface AppVisibility {
    val events: SharedFlow<AppVisibilityEvent>
}

/**
 * 나감/화면 꺼짐 판정(소유자 결정 2026-10-15 msg 920, v5에서 앱 전체로 넓힘 msg 943).
 *
 * 마지막 액티비티가 멈춘(onStop) 그 순간의 화면 상태로 가른다. 전원 버튼으로 끄면 이때 이미 [PowerManager.isInteractive]가 false다.
 * 기기마다 화면 꺼짐 처리 순서가 달라, 앱이 보이는 동안 [SCREEN_OFF_GRACE_MS] 안에 화면 꺼짐 방송을 받았으면 화면 꺼짐으로 본다.
 * 잠금 화면 여부는 화면 꺼짐 방송을 받은 경우에만 본다 — 홈 버튼만 눌렀는데(화면 꺼짐 없음) 잠금 상태로 잘못 알리는 기기에서
 * 안내가 계속 나오지 않게(v4 실기기: 홈으로 나가도 소리가 계속 남).
 */
object AppStopPolicy {
    const val SCREEN_OFF_GRACE_MS = 3_000L

    /**
     * @param interactive 지금 화면이 켜져 상호작용 중인가
     * @param keyguardLocked 잠금 화면이 떠 있는가
     * @param msSinceScreenOff 앱이 보이는 동안 받은 마지막 화면 꺼짐 방송 뒤 지난 시간(받은 적 없으면 null)
     */
    fun classifyStop(interactive: Boolean, keyguardLocked: Boolean, msSinceScreenOff: Long?): AppStop {
        val since = msSinceScreenOff?.takeIf { it >= 0 }
        return when {
            !interactive -> AppStop.SCREEN_OFF
            since != null && since <= SCREEN_OFF_GRACE_MS -> AppStop.SCREEN_OFF
            since != null && keyguardLocked -> AppStop.SCREEN_OFF
            else -> AppStop.LEFT_APP
        }
    }
}

/**
 * 액티비티 수명 주기로 앱 보임을 본다(앱 전체 하나).
 *
 * ProcessLifecycleOwner는 마지막 액티비티가 멈추고 약 0.7초 뒤에 알리고, 액티비티 onStop도 홈 전환 애니메이션이 끝난 뒤(에뮬레이터 실측
 * 약 1~2초)에 온다 — 그동안 안내가 이어졌다. 그래서 **마지막으로 보이던 화면이 onPause될 때** 바로 판정한다:
 * 화면이 켜져 있으면(홈·다른 앱·최근 앱) 그 자리에서 [AppStop.LEFT_APP]을 알린다(0.5초 안에 조용해지게).
 * 화면이 꺼지는 중이면(전원 버튼: onPause 때 이미 isInteractive=false) onStop까지 기다려 [AppStop.SCREEN_OFF]로 알린다.
 * 설정 변경(회전)으로 다시 만들어질 때는 알리지 않는다. 나감을 알린 뒤 다시 보이면 [AppVisibilityEvent.Started].
 * 앱 시작(Application.onCreate) 때 만들어져야 첫 화면부터 센다([AppAudioGuard.start]).
 */
@Singleton
class ActivityAppVisibility @Inject constructor(@ApplicationContext private val context: Context) : AppVisibility {
    private val _events = MutableSharedFlow<AppVisibilityEvent>(extraBufferCapacity = EVENT_BUFFER)
    override val events: SharedFlow<AppVisibilityEvent> = _events.asSharedFlow()

    private val listeners = CopyOnWriteArrayList<(AppVisibilityEvent) -> Unit>()
    private var startedCount = 0
    private var resumedCount = 0

    /** 나감(또는 화면 꺼짐)을 알렸고 아직 다시 보이지 않았다. */
    private var stopDispatched = false
    private var everStarted = false

    @Volatile private var lastScreenOffAt: Long? = null

    private val screenOff = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == Intent.ACTION_SCREEN_OFF) lastScreenOffAt = SystemClock.elapsedRealtime()
        }
    }

    private val callbacks = object : Application.ActivityLifecycleCallbacks {
        override fun onActivityStarted(activity: Activity) {
            startedCount++
            if (startedCount == 1) becameVisible()
        }

        override fun onActivityResumed(activity: Activity) {
            resumedCount++
            if (resumedCount == 1) becameVisible()
        }

        override fun onActivityPaused(activity: Activity) {
            if (resumedCount > 0) resumedCount--
            if (resumedCount != 0 || activity.isChangingConfigurations || stopDispatched) return
            // 빠른 길: 화면이 켜진 채 가려졌으면 나감. 화면 꺼짐으로 보이면 onStop에서 다시 본다.
            val why = classifyNow()
            if (why == AppStop.LEFT_APP) dispatchStop(why)
        }

        override fun onActivityStopped(activity: Activity) {
            if (startedCount > 0) startedCount--
            if (startedCount != 0 || activity.isChangingConfigurations || stopDispatched) return
            dispatchStop(classifyNow())
        }

        override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit

        override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit

        override fun onActivityDestroyed(activity: Activity) = Unit
    }

    private fun becameVisible() {
        if (everStarted && !stopDispatched) return
        everStarted = true
        stopDispatched = false
        // 다시 보이게 됐으면 앞선 화면 꺼짐은 끝난 일이다(잠금 해제 직후 홈으로 나가는 것을 화면 꺼짐으로 보지 않게).
        lastScreenOffAt = null
        dispatch(AppVisibilityEvent.Started)
    }

    private fun dispatchStop(why: AppStop) {
        stopDispatched = true
        dispatch(AppVisibilityEvent.Stopped(why))
    }

    init {
        ContextCompat.registerReceiver(context, screenOff, IntentFilter(Intent.ACTION_SCREEN_OFF), ContextCompat.RECEIVER_NOT_EXPORTED)
        (context.applicationContext as? Application)?.registerActivityLifecycleCallbacks(callbacks)
    }

    /** 메인 스레드에서 바로 부른다(코루틴 전달을 기다리지 않음 — 홈을 누르면 곧바로 조용해지게). */
    fun addListener(listener: (AppVisibilityEvent) -> Unit) {
        listeners += listener
    }

    private fun dispatch(event: AppVisibilityEvent) {
        Log.i(TAG, "app visibility: $event")
        listeners.forEach { it(event) }
        _events.tryEmit(event)
    }

    private fun classifyNow(): AppStop {
        val power = context.getSystemService(PowerManager::class.java)
        val keyguard = context.getSystemService(KeyguardManager::class.java)
        val since = lastScreenOffAt?.let { SystemClock.elapsedRealtime() - it }
        return AppStopPolicy.classifyStop(
            interactive = power?.isInteractive ?: true,
            keyguardLocked = keyguard?.isKeyguardLocked ?: false,
            msSinceScreenOff = since
        )
    }

    private companion object {
        const val TAG = "SeesunAppAudio"
        const val EVENT_BUFFER = 8
    }
}

/**
 * 앱 전체 소리 지킴이(v4 실기기 버그 msg 943: 홈으로 나가도 온보딩 안내가 계속 나왔다).
 * 다른 앱·홈으로 나가면 어느 화면이든 안내 음성(TTS)과 듣기(마이크)를 바로 멈춘다. 화면만 끈 경우(운동 중 계속 재생)는 그대로 둔다.
 * 운동 화면은 같은 [AppVisibility] 알림으로 운동 안내·휴식 타이머를 멈추고 「이어서 시작」을 띄운다.
 */
@Singleton
class AppAudioGuard @Inject constructor(
    private val visibility: ActivityAppVisibility,
    private val speaker: Speaker,
    private val speech: SpeechInput
) {
    @Volatile private var started = false

    fun start() {
        if (started) return
        started = true
        visibility.addListener { event ->
            if (event is AppVisibilityEvent.Stopped && event.why == AppStop.LEFT_APP) {
                speaker.stop()
                speech.cancelAll()
                Log.i(TAG, "left app: guidance and listening stopped")
            }
        }
    }

    private companion object {
        const val TAG = "SeesunAppAudio"
    }
}
