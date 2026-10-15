package kr.seesun.feature.mypage

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/** 동의 문구의 이름: "처리 동의 동의 철회"처럼 겹쳐 읽히지 않게(통합 E2E BUG-08). */
class ConsentNameTest {
    @Test
    fun `제목이 동의로 끝나면 그대로 아니면 동의를 붙인다`() {
        assertEquals("건강정보(민감정보) 처리 동의", consentName("건강정보(민감정보) 처리 동의"))
        assertEquals("See:Sun 이용약관 동의", consentName("See:Sun 이용약관"))
        assertEquals("개인정보 수집·이용 안내 동의", consentName("개인정보 수집·이용 안내"))
    }
}
