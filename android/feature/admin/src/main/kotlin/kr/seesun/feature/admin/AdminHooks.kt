package kr.seesun.feature.admin

import kr.seesun.core.designsystem.component.SettingsExtras

/**
 * 관리자 모드 창구(v7). 가운영 자체 업데이트 빌드(src/selfUpdateOn)만 실제로 동작하고, Play·일반 빌드(src/selfUpdateOff)는
 * 톱니를 눌러도 아무것도 하지 않으며 설정 화면에 아무것도 덧붙이지 않는다.
 */
interface AdminHooks {
    /** 이 빌드에 관리자 모드가 들어 있는가. */
    val available: Boolean

    /** 머리의 [설정](톱니바퀴)을 눌렀다(첫 화면·권한 안내·설정 목록 포함, 설정 열기와 별개로 센다). 메인 스레드에서 부른다. */
    fun onGearTap()

    /** 설정 화면에 덧붙일 것(관리자 모드 묶음·「조정값 사용 중」). */
    val settingsExtras: SettingsExtras
}
