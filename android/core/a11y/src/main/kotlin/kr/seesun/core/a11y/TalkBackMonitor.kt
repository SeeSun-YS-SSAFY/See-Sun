package kr.seesun.core.a11y

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Context
import android.os.Build
import android.util.Log
import android.view.accessibility.AccessibilityManager
import androidx.annotation.RequiresApi
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalInspectionMode
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged

/**
 * 화면 낭독기(TalkBack) 켜짐 여부를 감지한다.
 *
 * v6(연구 문서 §6 P0-3, G3): **터치 탐색(touch exploration)이 켜져 있을 때만** "화면 낭독기 켜짐"으로 본다.
 * 구글 TalkBack, 삼성 One UI 3.0 이상의 통합 TalkBack, 구 보이스 어시스턴트는 모두 터치 탐색을 켠다.
 * Select to Speak처럼 화면 낭독기가 아닌 음성 서비스(TalkBack 패키지 안에 함께 있음)는 터치 탐색을 켜지 않으므로
 * 이제 TalkBack으로 오판하지 않는다. 음성 피드백 서비스 목록은 진단 로그로만 남긴다.
 */
class TalkBackMonitor(context: Context) {

    private val manager = context.applicationContext.getSystemService(AccessibilityManager::class.java)

    /** 지금 화면 낭독기가 켜져 있는가(터치 탐색만으로 판정). */
    fun isEnabled(): Boolean {
        val m = manager ?: return false
        return ScreenReaderDetection.isScreenReader(enabled = m.isEnabled, touchExploration = m.isTouchExplorationEnabled)
    }

    /** 진단용: 켜져 있는 음성 피드백 서비스 이름(판정에는 쓰지 않는다). */
    fun spokenServices(): List<String> = manager?.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_SPOKEN)
        ?.mapNotNull { it.resolveInfo?.serviceInfo?.name }
        .orEmpty()

    /** 상태가 바뀌면 [onChange]를 부르는 리스너를 모두 붙이고, 떼는 함수를 돌려준다. */
    fun listen(onChange: (Boolean) -> Unit): () -> Unit {
        val m = manager ?: return {}
        var last: Boolean? = null
        val notify = {
            val on = isEnabled()
            // 바뀔 때만 남긴다(uiautomator 같은 접근성 도구가 붙고 떨어질 때마다 알림이 온다).
            if (on != last) Log.i(TAG, "screen reader=$on (touch exploration) spoken services=${spokenServices()}")
            last = on
            onChange(on)
        }
        val touchListener = AccessibilityManager.TouchExplorationStateChangeListener { notify() }
        val stateListener = AccessibilityManager.AccessibilityStateChangeListener { notify() }
        m.addTouchExplorationStateChangeListener(touchListener)
        m.addAccessibilityStateChangeListener(stateListener)
        // API 33+: 서비스 하나가 켜지고 꺼지는 변화도 받는다(다른 서비스가 켜진 채 TalkBack만 바뀌는 경우, G4).
        val servicesListener = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) addServicesListener(m) { notify() } else null
        return {
            m.removeTouchExplorationStateChangeListener(touchListener)
            m.removeAccessibilityStateChangeListener(stateListener)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) servicesListener?.let { removeServicesListener(m, it) }
        }
    }

    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    private fun addServicesListener(m: AccessibilityManager, onChange: () -> Unit): Any {
        val listener = AccessibilityManager.AccessibilityServicesStateChangeListener { onChange() }
        m.addAccessibilityServicesStateChangeListener(listener)
        return listener
    }

    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    private fun removeServicesListener(m: AccessibilityManager, listener: Any) {
        m.removeAccessibilityServicesStateChangeListener(listener as AccessibilityManager.AccessibilityServicesStateChangeListener)
    }

    /** 화면 낭독기 켜짐 상태 변화를 흐름으로 내보낸다(구독 즉시 현재 값 1회). */
    fun changes(): Flow<Boolean> = callbackFlow {
        val remove = listen { trySend(it) }
        trySend(isEnabled())
        awaitClose { remove() }
    }.distinctUntilChanged()

    private companion object {
        const val TAG = "SeesunAutoListen"
    }
}

/** 화면 낭독기 판정 규칙(순수 함수, 시험용). */
object ScreenReaderDetection {
    /**
     * 접근성이 켜져 있고 터치 탐색이 켜져 있으면 화면 낭독기다. 음성 피드백 서비스가 켜져 있어도
     * 터치 탐색이 꺼져 있으면(Select to Speak 등) 화면 낭독기가 아니다.
     */
    fun isScreenReader(enabled: Boolean, touchExploration: Boolean): Boolean = enabled && touchExploration
}

/** Compose에서 화면 낭독기 켜짐 여부를 상태로 읽는다. 미리보기에서는 항상 false. */
@Composable
fun rememberTalkBackEnabled(): Boolean {
    if (LocalInspectionMode.current) return false
    val context = LocalContext.current
    val monitor = remember(context) { TalkBackMonitor(context) }
    var enabled by remember { mutableStateOf(monitor.isEnabled()) }
    DisposableEffect(monitor) {
        val remove = monitor.listen { enabled = it }
        enabled = monitor.isEnabled()
        onDispose { remove() }
    }
    return enabled
}
