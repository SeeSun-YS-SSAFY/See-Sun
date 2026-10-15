package kr.seesun.feature.update

import android.content.Intent
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * 백그라운드 업데이트 확인 예약 창구(가운영 자체 업데이트 빌드에서만 실제로 예약한다).
 * 구현: selfUpdateOn = WorkManager(6시간마다·네트워크 연결 시 + 앱 시작 때 한 번), selfUpdateOff = 아무것도 안 함.
 */
interface UpdateCheckScheduler {
    /** 앱 시작 때 부른다: 주기 확인을 (없으면) 예약하고 지금 한 번 확인한다. */
    fun schedule()
}

/**
 * 업데이트 알림 규칙(소유자 결정 msg 923/940). 순수 함수라 기기 없이 시험한다.
 * - 설치된 것보다 높은 versionCode가 있을 때만 알린다.
 * - 같은 versionCode는 하루에 한 번만 알린다.
 * - 앱이 화면에 보이는 중이면 알리지 않는다(앱 안 「새 버전이 있어요」 창이 맡는다).
 */
object UpdateNotifyPolicy {
    /** 주기 확인 간격(시간). WorkManager 최소 15분보다 길다. */
    const val CHECK_INTERVAL_HOURS = 6L

    /** 같은 versionCode를 다시 알리기까지 최소 간격. */
    const val RENOTIFY_INTERVAL_MS: Long = 24L * 60 * 60 * 1000

    /** 알림·앱 안 안내 창이 함께 쓰는 기록(같은 versionCode를 하루에 한 번만 알리기). */
    const val PREFS = "seesun_selfupdate"
    const val KEY_NOTIFIED_CODE = "notified_version_code"
    const val KEY_NOTIFIED_AT = "notified_at_millis"

    /**
     * @param lastNotifiedCode 마지막으로 알린 versionCode(없으면 null)
     * @param lastNotifiedAt 그때 시각(ms)
     * @param appVisible 앱 화면이 지금 보이는가
     */
    @Suppress("LongParameterList")
    fun shouldNotify(
        installedVersionCode: Long,
        manifest: UpdateManifest?,
        lastNotifiedCode: Long?,
        lastNotifiedAt: Long?,
        nowMillis: Long,
        appVisible: Boolean
    ): Boolean {
        if (manifest == null || appVisible) return false
        if (UpdateRules.need(installedVersionCode, manifest) == UpdateNeed.NONE) return false
        if (lastNotifiedCode != manifest.versionCode || lastNotifiedAt == null) return true
        // 시계가 뒤로 갔으면(nowMillis < lastNotifiedAt) 하루가 지난 것으로 보지 않고 기다린다 — 같은 날 두 번 알리지 않게.
        val elapsed = nowMillis - lastNotifiedAt
        return elapsed >= RENOTIFY_INTERVAL_MS
    }
}

/**
 * 알림을 눌러 앱을 열었을 때 업데이트 안내 창을 바로 띄우라는 요청, 앱 화면 보임 상태.
 * MainActivity가 [handleIntent]로 넘기고 [SelfUpdateGate]가 [openRequests]를 보고 매니페스트를 바로 확인한다.
 */
object UpdateRequests {
    /** 알림 → 앱 열기 인텐트에 붙이는 표시. */
    const val EXTRA_OPEN_UPDATE = "kr.seesun.update.OPEN"

    private val _openRequests = MutableStateFlow(0)

    /** 0이 아니면 바뀔 때마다 안내 창을 바로 확인·표시한다. */
    val openRequests: StateFlow<Int> = _openRequests.asStateFlow()

    /** 앱 화면이 보이는가(SelfUpdateGate가 재개·멈춤 때 바꾼다). 백그라운드 확인이 알림을 낼지 고를 때 본다. */
    @Volatile var appVisible: Boolean = false

    /** 인텐트가 업데이트 알림에서 왔으면 안내 창 요청을 올리고 true. */
    fun handleIntent(intent: Intent?): Boolean {
        if (intent?.getBooleanExtra(EXTRA_OPEN_UPDATE, false) != true) return false
        intent.removeExtra(EXTRA_OPEN_UPDATE)
        requestOpen()
        return true
    }

    fun requestOpen() = _openRequests.update { it + 1 }
}

/** 알림 기록 저장소(같은 versionCode를 하루에 한 번만 — 기기 SharedPreferences, 시험은 메모리). */
interface NotifyLedgerStore {
    fun read(): Pair<Long?, Long?>

    fun write(code: Long, at: Long)
}

/**
 * 업데이트 알림 관문(v5에서 넘어온 버그 수정): 백그라운드 확인 두 개(앱 시작 확인·6시간 주기)와 FCM 푸시가 **동시에** 와도
 * 알림은 한 번만 낸다. 「알려도 되는가 판정 → 기록」을 한 잠금 안에서 해서, 두 번째 호출은 첫 호출이 남긴 기록을 본다.
 * (v5는 두 작업이 기록을 쓰기 전에 둘 다 읽어 같은 알림을 두 번 냈다.)
 */
class UpdateNotifyGate(private val store: NotifyLedgerStore) {
    private val lock = Any()

    /** @return 이 호출이 알림을 낼 차례면 true(기록까지 마친 뒤 돌려준다) */
    fun claim(installedVersionCode: Long, manifest: UpdateManifest?, nowMillis: Long, appVisible: Boolean): Boolean = synchronized(lock) {
        val (lastCode, lastAt) = store.read()
        val notify = UpdateNotifyPolicy.shouldNotify(installedVersionCode, manifest, lastCode, lastAt, nowMillis, appVisible)
        if (notify && manifest != null) store.write(manifest.versionCode, nowMillis)
        notify
    }
}

/** 서버 FCM 푸시(데이터 전용, 토픽 `seesun-app-updates`) 한 건. 값은 모두 문자열로 온다. */
data class UpdatePush(val versionCode: Long, val versionName: String, val apkUrl: String, val sha256: String)

/** 푸시를 받았을 때 할 일. */
enum class PushAction {
    /** 모르는 메시지·같거나 낮은 버전: 아무것도 하지 않는다. */
    IGNORE,

    /** 앱이 화면에 보이는 중: 앱 안 「새 버전이 있어요」 창(최신 매니페스트를 다시 읽는다). */
    SHOW_DIALOG,

    /** 앱이 보이지 않음: 「새 버전이 있어요」 알림(같은 채널·같은 중복 막기). */
    NOTIFY
}

/**
 * 업데이트 푸시 규칙(순수 함수, 시험용, 서버 계약 ops v0.3).
 * - data: `type=app_update`, `versionCode`, `versionName`, `apkUrl`, `sha256`(모두 문자열). minVersionCode는 오지 않는다.
 * - 설치된 것보다 높은 versionCode일 때만 반응한다. 재전송이 허용되므로 중복은 [UpdateNotifyGate]가 막는다.
 * - 내려받기·검증은 늘 latest.json(서버 매니페스트)으로 한다. 푸시 값은 알림 문구와 판정에만 쓴다.
 */
object UpdatePushPolicy {
    const val TYPE = "app_update"
    const val TOPIC = "seesun-app-updates"

    fun parse(data: Map<String, String>): UpdatePush? {
        if (data["type"] != TYPE) return null
        val code = data["versionCode"]?.trim()?.toLongOrNull()?.takeIf { it > 0 } ?: return null
        return UpdatePush(
            versionCode = code,
            versionName = data["versionName"].orEmpty().ifBlank { code.toString() },
            apkUrl = data["apkUrl"].orEmpty(),
            sha256 = data["sha256"].orEmpty()
        )
    }

    fun decide(installedVersionCode: Long, push: UpdatePush?, appVisible: Boolean): PushAction = when {
        push == null || push.versionCode <= installedVersionCode -> PushAction.IGNORE
        appVisible -> PushAction.SHOW_DIALOG
        else -> PushAction.NOTIFY
    }

    /** 알림 판정용 매니페스트: 서버 latest.json을 읽었으면 그것, 못 읽었으면 푸시 값(minVersionCode 0). */
    fun manifestFor(push: UpdatePush, fetched: UpdateManifest?): UpdateManifest =
        fetched?.takeIf { it.versionCode >= push.versionCode } ?: UpdateManifest(
            versionCode = push.versionCode,
            versionName = push.versionName,
            apkUrl = push.apkUrl,
            sha256 = push.sha256,
            sizeBytes = 0
        )
}

/**
 * 업데이트 푸시 토픽 구독 창구. 가운영 자체 업데이트 빌드만 실제로 구독한다(selfUpdateOn), Play·일반 빌드는 아무것도 하지 않는다.
 * 처음 실행과 앱이 업데이트된 뒤 첫 실행 때(설치된 versionCode가 기록과 다르면) 구독하고, 실패하면 네트워크가 될 때 다시 한다.
 */
interface UpdatePushSubscriber {
    fun ensureSubscribed()
}
