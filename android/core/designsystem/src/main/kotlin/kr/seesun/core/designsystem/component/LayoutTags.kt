package kr.seesun.core.designsystem.component

/**
 * 화면 틀 영역 표시(testTag). 배치 기준 판정(docs/design/11_레이아웃_기준.md, 기기 시험 LayoutAudit)이
 * 헤더·본문·하단 행동 영역의 높이와 주요 버튼 위치를 잰다. 낭독에는 영향이 없다.
 */
object LayoutTags {
    const val HEADER = "ss_header"
    const val BODY = "ss_body"
    const val ACTIONS = "ss_actions"
    const val PRIMARY = "ss_primary"
    const val SETTINGS = "ss_settings"
    const val REPEAT = "ss_repeat"
}
