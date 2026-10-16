package kr.seesun.core.designsystem.component

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.state.ToggleableState
import kr.seesun.core.a11y.PressKind

/**
 * 앱이 모든 화면 틀에 내려 주는 공통 동작(v6). 디자인 시스템은 음성 모듈을 모르므로 app이 구현해 넘긴다.
 */
interface ScreenChrome {
    /** 화면 틀이 화면에 들어왔다(제목을 되울림 필터·[화면 다시 듣기]에 알린다). */
    fun onScreen(title: String)

    /** 헤더 [화면 다시 듣기] 기본 동작: [enteredAt] 뒤 이 화면의 마지막 안내를, 없으면 제목을 다시 읽는다. */
    fun repeat(title: String, enteredAt: Long)

    /** 화면 빈 곳 두 번 탭(TalkBack 꺼짐만): 쉬던 「말하면 듣기」를 다시 켠다. */
    fun onDoubleTap()

    /** 지금 시각(ms, 화면 들어온 때 비교용). */
    fun now(): Long
}

val LocalScreenChrome = staticCompositionLocalOf<ScreenChrome?> { null }

/** 헤더 [화면 다시 듣기]를 보일까(앱 음성이 말할 수 있을 때만 — 화면 낭독기 + 「자동」이면 숨기고 톱니만 모서리에). */
val LocalShowRepeat = compositionLocalOf { false }

/**
 * 누름 피드백(연구 문서 §1·§6 P0-4). 규칙은 [kr.seesun.core.a11y.PressFeedbackPolicy]에 있고, app이 진동·앱 음성으로 낸다.
 * 화면 낭독기가 켜져 있으면 아무것도 하지 않는다(버튼 이름을 앱이 말하지 않는다). 어느 버튼이든 누르면 앱 안내 음성을 멈춘다.
 */
interface PressFeedback {
    /** @param toggledOn 그 자리 토글이면 누른 뒤 상태(결과 읽기용), 아니면 null */
    fun onPress(kind: PressKind, label: String, toggledOn: Boolean? = null)

    /** 길게 누르면 이름만 읽는다(실행하지 않음). */
    fun onLongPress(label: String)

    /** 길게 누르기를 붙일까(화면 낭독기 꺼짐 + 「끄기」 아님 — TalkBack 동작 목록을 늘리지 않게). */
    val longPressEnabled: Boolean
}

val LocalPressFeedback = compositionLocalOf<PressFeedback?> { null }

/**
 * 공통 누르기: 누름 피드백(진동·안내 멈춤)을 먼저 내고 [onClick]을 부른다(누른 결과로 화면이 말하면 그 말이 피드백을 덮는다).
 * 길게 누르기는 [PressFeedback.longPressEnabled]일 때만 붙인다.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun Modifier.pressable(
    label: String,
    onClick: () -> Unit,
    enabled: Boolean = true,
    kind: PressKind = PressKind.NAVIGATE,
    role: Role = Role.Button,
    onClickLabel: String? = null
): Modifier {
    val feedback = LocalPressFeedback.current
    val click = {
        feedback?.onPress(kind, label)
        onClick()
    }
    return if (feedback?.longPressEnabled == true) {
        combinedClickable(
            enabled = enabled,
            role = role,
            onClickLabel = onClickLabel,
            onLongClick = { feedback.onLongPress(label) },
            onClick = click
        )
    } else {
        clickable(enabled = enabled, role = role, onClickLabel = onClickLabel, onClick = click)
    }
}

/** 그 자리 토글(선택·켜고 끄기): 누른 뒤 상태를 짧게 말한다("고혈압 선택됨", 결과 읽기 설정일 때). */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun Modifier.togglePressable(label: String, value: Boolean, onToggle: () -> Unit, enabled: Boolean = true): Modifier {
    val feedback = LocalPressFeedback.current
    val toggle = {
        feedback?.onPress(PressKind.TOGGLE, label, toggledOn = !value)
        onToggle()
    }
    return if (feedback?.longPressEnabled == true) {
        combinedClickable(enabled = enabled, role = Role.Checkbox, onLongClick = { feedback.onLongPress(label) }, onClick = toggle)
            .semantics { toggleableState = ToggleableState(value) }
    } else {
        toggleable(value = value, enabled = enabled, role = Role.Checkbox, onValueChange = { toggle() })
    }
}

/** 헤더 한 줄의 칸 순서(순수 함수, 시험용). */
enum class HeaderSlot { BACK, SIDE_SPACER, TITLE, FILL, REPEAT, GAP, SETTINGS, MORE, END_SPACER }

object HeaderLayout {
    /**
     * 왼쪽부터 [뒤로](없으면 같은 폭 빈 칸) · 제목(숨기면 빈 칸) · [화면 다시 듣기] · 8dp · [설정].
     * 톱니는 늘 오른쪽 맨 끝이다(화면 낭독기가 켜져 [화면 다시 듣기]가 숨어도 자리가 바뀌지 않게, UI/UX v2 §5.2).
     * 오른쪽에 버튼이 하나도 없으면 왼쪽과 같은 폭을 비워 제목이 가운데 오게 한다.
     *
     * [hasMore]면 톱니 자리(오른쪽 맨 끝)에 [⋯ 더 보기]가 오고 [설정]은 그 목록 안으로 들어간다
     * (화면 간소화 설계 v1 §6.1·§7.1: 「나머지는 늘 같은 자리의 [⋯ 더 보기] 하나로 — 지금 ⚙ 자리」). [화면 다시 듣기]는 그대로 남는다.
     */
    fun slots(hasBack: Boolean, showTitle: Boolean, hasRepeat: Boolean, hasSettings: Boolean, hasMore: Boolean = false): List<HeaderSlot> =
        buildList {
            val end = when {
                hasMore -> HeaderSlot.MORE
                hasSettings -> HeaderSlot.SETTINGS
                else -> null
            }
            add(if (hasBack) HeaderSlot.BACK else HeaderSlot.SIDE_SPACER)
            add(if (showTitle) HeaderSlot.TITLE else HeaderSlot.FILL)
            if (hasRepeat) add(HeaderSlot.REPEAT)
            if (hasRepeat && end != null) add(HeaderSlot.GAP)
            if (end != null) add(end)
            if (!hasRepeat && end == null) add(HeaderSlot.END_SPACER)
        }

    const val REPEAT_LABEL = "화면 다시 듣기"
    const val SETTINGS_LABEL = "설정"
    const val MORE_LABEL = "더 보기"
}
