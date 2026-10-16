package kr.seesun.feature.mypage

import kr.seesun.core.network.model.MeDto
import kr.seesun.core.network.model.TargetKcalDetailDto
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** v1.8 설정: 문의하기(전화·이메일), 물리 버튼 켜기/끄기, 건강 정보(2차 의뢰서 p7). */
class ContactAndHardwareButtonTest {

    @Test
    fun `전화는 번호만 채우는 tel 주소, 숫자가 없거나 너무 적으면 버튼을 만들지 않는다`() {
        assertEquals("tel:0212345678", ContactUris.dial("02-1234-5678"))
        assertEquals("tel:+82212345678", ContactUris.dial("+82 2 1234 5678"))
        assertNull(ContactUris.dial(""))
        assertNull(ContactUris.dial("준비 중"))
    }

    @Test
    fun `이메일은 받는 주소와 제목을 채운 mailto, 형식이 아니면 만들지 않는다`() {
        val subject = "%5B%EC%8B%9C%EC%84%A0%5D%20%EC%95%B1%20%EB%AC%B8%EC%9D%98" // "[시선] 앱 문의"
        assertEquals("mailto:help@example.com?subject=$subject", ContactUris.mailto("help@example.com", "[시선] 앱 문의"))
        assertEquals("mailto:help@example.com", ContactUris.mailto(" help@example.com ", ""))
        assertNull(ContactUris.mailto("", "제목"))
        assertNull(ContactUris.mailto("help@", "제목"))
    }

    @Test
    fun `연락처 설정이 비어 있으면 준비 중(버튼 없음)`() {
        val empty = ContactConfig(phone = "", email = "")
        assertFalse(empty.hasPhone)
        assertFalse(empty.hasEmail)
        val set = ContactConfig(phone = "1588-0000", email = "help@example.com")
        assertTrue(set.hasPhone && set.hasEmail)
    }

    @Test
    fun `설정 목록 순서 - 소리·말 다음 물리 버튼, 건강 정보, 문의하기는 접근성 안내 앞`() {
        val all = SettingsItem.entries.toList()
        assertEquals(all.indexOf(SettingsItem.PRESS_FEEDBACK) + 1, all.indexOf(SettingsItem.HW_BUTTON))
        assertEquals(all.indexOf(SettingsItem.HW_BUTTON) + 1, all.indexOf(SettingsItem.HEALTH))
        assertEquals(all.indexOf(SettingsItem.CONTACT) + 1, all.indexOf(SettingsItem.ACCESSIBILITY))
        assertEquals(HealthEditRoute, SettingsItem.HEALTH.route)
        assertEquals(ContactRoute, SettingsItem.CONTACT.route)
        assertEquals(HardwareButtonRoute, SettingsItem.HW_BUTTON.route)
    }

    @Test
    fun `문의·물리 버튼은 로그인 전에도, 건강 정보는 로그인 뒤에만`() {
        assertTrue(SettingsItem.allowsVoiceTarget(ContactRoute, signedIn = false))
        assertTrue(SettingsItem.allowsVoiceTarget(HardwareButtonRoute, signedIn = false))
        assertFalse(SettingsItem.allowsVoiceTarget(HealthEditRoute, signedIn = false))
        assertTrue(SettingsItem.allowsVoiceTarget(HealthEditRoute, signedIn = true))
    }

    @Test
    fun `말로 여는 새 설정 - 접근성 문의는 그대로 접근성 안내`() {
        assertEquals(ContactRoute, SettingsVoiceMatcher.match("문의하기"))
        assertEquals(ContactRoute, SettingsVoiceMatcher.match("전화 걸고 싶어요"))
        assertEquals(ContactRoute, SettingsVoiceMatcher.match("이메일 보내기"))
        assertEquals(AccessibilityRoute, SettingsVoiceMatcher.match("접근성 문의"))
        assertEquals(HardwareButtonRoute, SettingsVoiceMatcher.match("물리 버튼"))
        assertEquals(HealthEditRoute, SettingsVoiceMatcher.match("건강 정보"))
        assertEquals(HealthEditRoute, SettingsVoiceMatcher.match("알레르기 바꿀래"))
        // 앞 규칙이 이긴다: 동의 철회·건강정보 삭제는 그대로
        assertEquals(ConsentManageRoute, SettingsVoiceMatcher.match("건강정보 동의 철회"))
        assertEquals(DataResetRoute, SettingsVoiceMatcher.match("건강정보 지워 줘"))
    }

    @Test
    fun `미성년 연령별 기준 표시는 서버가 그 산식으로 계산했을 때만`() {
        fun me(minor: Boolean, formula: String?) =
            MeDto(id = "u", targetKcal = 2000, targetKcalDetail = TargetKcalDetailDto(targetKcal = 2000, formula = formula, minor = minor))
        assertTrue(usesMinorStandard(me(minor = true, formula = "kdri2025_eer_v1")))
        // v1.8 서버(2020 기준)도 그대로 인정한다(재배포 전후 모두 「청소년 연령별 섭취기준」)
        assertTrue(usesMinorStandard(me(minor = true, formula = "kdri2020_eer_v1")))
        // 서버 배포 전: minor=true여도 값은 성인 공식 → 「연령별 기준」이라고 하지 않는다
        assertFalse(usesMinorStandard(me(minor = true, formula = "mifflin_st_jeor_v1")))
        assertFalse(usesMinorStandard(me(minor = false, formula = "mifflin_st_jeor_v1")))
        assertFalse(usesMinorStandard(MeDto(id = "u")))
    }
}
