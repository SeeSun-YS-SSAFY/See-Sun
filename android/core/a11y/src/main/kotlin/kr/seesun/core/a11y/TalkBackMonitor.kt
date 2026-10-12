package kr.seesun.core.a11y

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Context
import android.view.accessibility.AccessibilityManager
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
 * TalkBack(화면 낭독) 켜짐 여부를 감지한다.
 *
 * 터치 탐색(touch exploration)이 켜져 있거나 음성 피드백 접근성 서비스가 하나라도 켜져 있으면
 * "TalkBack 켜짐"으로 본다. 삼성 보이스 어시스턴트 등 다른 화면 낭독기도 같은 기준으로 잡힌다.
 */
class TalkBackMonitor(context: Context) {

    private val manager = context.applicationContext.getSystemService(AccessibilityManager::class.java)

    /** 지금 TalkBack이 켜져 있는가. */
    fun isEnabled(): Boolean {
        val m = manager ?: return false
        if (!m.isEnabled) return false
        if (m.isTouchExplorationEnabled) return true
        return m.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_SPOKEN).isNotEmpty()
    }

    /** TalkBack 켜짐 상태 변화를 흐름으로 내보낸다(구독 즉시 현재 값 1회). */
    fun changes(): Flow<Boolean> = callbackFlow {
        val m = manager
        if (m == null) {
            trySend(false)
            awaitClose { }
            return@callbackFlow
        }
        val touchListener = AccessibilityManager.TouchExplorationStateChangeListener { trySend(isEnabled()) }
        val stateListener = AccessibilityManager.AccessibilityStateChangeListener { trySend(isEnabled()) }
        m.addTouchExplorationStateChangeListener(touchListener)
        m.addAccessibilityStateChangeListener(stateListener)
        trySend(isEnabled())
        awaitClose {
            m.removeTouchExplorationStateChangeListener(touchListener)
            m.removeAccessibilityStateChangeListener(stateListener)
        }
    }.distinctUntilChanged()
}

/** Compose에서 TalkBack 켜짐 여부를 상태로 읽는다. 미리보기에서는 항상 false. */
@Composable
fun rememberTalkBackEnabled(): Boolean {
    if (LocalInspectionMode.current) return false
    val context = LocalContext.current
    val monitor = remember(context) { TalkBackMonitor(context) }
    var enabled by remember { mutableStateOf(monitor.isEnabled()) }
    DisposableEffect(monitor) {
        val manager = context.applicationContext.getSystemService(AccessibilityManager::class.java)
        val listener = AccessibilityManager.TouchExplorationStateChangeListener { enabled = monitor.isEnabled() }
        val stateListener = AccessibilityManager.AccessibilityStateChangeListener { enabled = monitor.isEnabled() }
        manager?.addTouchExplorationStateChangeListener(listener)
        manager?.addAccessibilityStateChangeListener(stateListener)
        onDispose {
            manager?.removeTouchExplorationStateChangeListener(listener)
            manager?.removeAccessibilityStateChangeListener(stateListener)
        }
    }
    return enabled
}
