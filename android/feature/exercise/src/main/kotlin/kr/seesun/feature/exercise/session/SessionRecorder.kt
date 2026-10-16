package kr.seesun.feature.exercise.session

import android.content.Context
import androidx.core.content.edit
import dagger.hilt.android.qualifiers.ApplicationContext
import java.time.Clock
import java.time.Duration
import java.time.OffsetDateTime
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kr.seesun.core.a11y.TalkBackMonitor
import kr.seesun.core.data.repository.ExerciseEndStatus
import kr.seesun.core.data.repository.ExerciseLogItem
import kr.seesun.core.data.repository.ExerciseLogRepository
import kr.seesun.core.data.repository.ExerciseMode
import kr.seesun.core.feedback.ExerciseCue
import kr.seesun.core.feedback.ExerciseHaptics
import kr.seesun.core.feedback.Feedback
import kr.seesun.core.feedback.HapticPattern

/** 화면이 사라진 뒤에도 끝내야 하는 저장(뒤로 가기 없이 화면이 닫힐 때의 기록 확정)용. */
internal val ExerciseAppScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

/**
 * 운동 세션 한 번의 기록: 시작(DRAFT 저장 + 화면 꺼짐 대비 서비스) → 15초마다 생존 신호 → 끝(PENDING 확정 + 전송 예약).
 * 화면(ViewModel)마다 하나씩 쓴다. 끝내기를 여러 번 불러도(두 번 누르기) 한 번만 저장된다.
 */
class SessionRecorder @Inject constructor(
    private val logs: ExerciseLogRepository,
    private val keeper: SessionKeeper,
    private val clock: Clock
) {
    private val lock = Mutex()
    private var clientUuid: String? = null
    private var heartbeat: Job? = null

    var startedAt: OffsetDateTime? = null
        private set

    val isActive: Boolean get() = clientUuid != null

    private var title: String = ""

    /** 다른 앱으로 나가 안내를 멈춰 포그라운드 서비스를 잠시 내렸는가. */
    var keepAliveSuspended: Boolean = false
        private set

    fun now(): OffsetDateTime = OffsetDateTime.now(clock)

    /** 이미 시작했으면 아무것도 하지 않는다. */
    suspend fun begin(scope: CoroutineScope, title: String, playlistId: String?, mode: ExerciseMode) = lock.withLock {
        if (clientUuid != null) return@withLock
        val at = now()
        startedAt = at
        clientUuid = logs.begin(title, playlistId, mode, at)
        this.title = title
        keepAliveSuspended = false
        keeper.start(title)
        heartbeat = scope.launch {
            while (isActive) {
                delay(HEARTBEAT_MS)
                clientUuid?.let { logs.heartbeat(it, now()) }
            }
        }
    }

    /** 끝낸 세션을 큐에 확정한다. 확정했으면 true(이미 끝났거나 시작 안 했으면 false). */
    suspend fun finish(status: ExerciseEndStatus, items: (OffsetDateTime) -> List<ExerciseLogItem>): Boolean = lock.withLock {
        val uuid = clientUuid ?: return@withLock false
        clientUuid = null
        heartbeat?.cancel()
        keepAliveSuspended = false
        keeper.stop()
        val end = now()
        logs.finish(uuid, status, end, items(end))
    }

    /**
     * 다른 앱으로 나가 안내를 멈췄을 때: 소리를 내지 않으니 포그라운드 서비스(알림·wake lock)를 내린다. 기록은 그대로 이어진다.
     * 세션 중이 아니거나 이미 내렸으면 아무것도 하지 않는다.
     */
    fun suspendKeepAlive() {
        if (clientUuid == null || keepAliveSuspended) return
        keepAliveSuspended = true
        keeper.stop()
    }

    /** 「이어서 시작」·[재생]으로 다시 시작할 때(앱이 화면에 있을 때 부른다 — 백그라운드에서는 서비스를 띄울 수 없다). */
    fun resumeKeepAlive() {
        if (clientUuid == null || !keepAliveSuspended) return
        keepAliveSuspended = false
        keeper.start(title)
    }

    /** 화면이 닫히는데 끝내기를 못 한 경우: 그만둔 것으로 기록한다(앱 범위에서 저장). */
    fun abandon(items: (OffsetDateTime) -> List<ExerciseLogItem>) {
        if (clientUuid == null) return
        ExerciseAppScope.launch { finish(ExerciseEndStatus.ABORTED, items) }
    }

    fun elapsed(): Duration = startedAt?.let { Duration.between(it, now()) } ?: Duration.ZERO

    private companion object {
        const val HEARTBEAT_MS = 15_000L
    }
}

/** 운동 진동 신호(core:feedback): 단계 신호([ExerciseCue]) + 저장 완료(공통 SAVED 패턴). */
class SessionSignals @Inject constructor(private val haptics: ExerciseHaptics, private val feedback: Feedback) {
    fun cue(cue: ExerciseCue) = haptics.cue(cue)

    fun saved() {
        feedback.signal(HapticPattern.SAVED)
        haptics.cue(ExerciseCue.DONE)
    }
}

/** ViewModel에서 TalkBack 켜짐 여부를 읽는다(진입 자동 재생 여부, A9). */
class TalkBackState @Inject constructor(@ApplicationContext context: Context) {
    private val monitor = TalkBackMonitor(context)

    fun isOn(): Boolean = monitor.isEnabled()
}

/** "3분 20초" 같은 낭독용 시간. */
fun spokenDuration(d: Duration): String {
    val minutes = d.toMinutes()
    val seconds = d.seconds % SECONDS_PER_MINUTE
    return when {
        minutes == 0L -> "${seconds}초"
        seconds == 0L -> "${minutes}분"
        else -> "${minutes}분 ${seconds}초"
    }
}

private const val SECONDS_PER_MINUTE = 60

/** 재생 화면 첫 안내(「멈추려면 가운데 버튼…」, 간소화 설계 §6.4 ⓐ)를 이미 했는지(기기에만). */
@Singleton
class PlayerTipStore @Inject constructor(@ApplicationContext context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** 아직 안 했으면 true를 돌려주고 했다고 적는다(한 번만). */
    fun takeFirstTip(): Boolean {
        if (prefs.getBoolean(KEY, false)) return false
        prefs.edit { putBoolean(KEY, true) }
        return true
    }

    private companion object {
        const val PREFS = "seesun_player_tip"
        const val KEY = "first_tip_v19"
    }
}
