package kr.seesun.core.designsystem.component

import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf

/**
 * 앱이 설정 화면에 덧붙이는 것(v7 관리자 모드, 가운영 자체 업데이트 빌드만). Play·일반 빌드는 기본값(아무것도 없음) 그대로다.
 *
 * @param gearOnSettingsList 설정 목록 머리에도 톱니바퀴를 둔다. 어느 화면에서든 톱니를 연달아 누르면 첫 번째로 설정이 열리고,
 *   이어지는 누름이 같은 자리의 톱니에 닿아야 관리자 모드 횟수를 셀 수 있다. 설정 목록에서 누르면 화면은 그대로다
 *   (설정 하위 화면의 톱니가 설정 목록으로 돌아가는 것과 같은 뜻).
 * @param settingsFooter 설정 목록 맨 아래 묶음. 인자는 설정 목록의 화면 열기 동작(경로 객체)이다.
 * @param voiceAutoNote 「음성 자동 인식」 화면 설명 아래 작은 알림(「조정값 사용 중」).
 */
class SettingsExtras(
    val gearOnSettingsList: Boolean = false,
    val settingsFooter: (@Composable ColumnScope.(onOpen: (Any) -> Unit) -> Unit)? = null,
    val voiceAutoNote: (@Composable ColumnScope.() -> Unit)? = null
)

val LocalSettingsExtras = staticCompositionLocalOf { SettingsExtras() }
