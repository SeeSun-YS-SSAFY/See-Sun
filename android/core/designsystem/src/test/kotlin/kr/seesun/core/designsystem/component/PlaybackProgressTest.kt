package kr.seesun.core.designsystem.component

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/** 진행 바의 TalkBack 값은 10% 단위로만 바뀐다(문장마다 값이 바뀌어 초점 낭독이 잦아지지 않게). */
class PlaybackProgressTest {

    @Test
    fun `10퍼센트 단위로 내리고 끝은 끝일 때만`() {
        assertEquals(0f, progressStep(0f))
        assertEquals(0f, progressStep(0.09f))
        assertEquals(0.1f, progressStep(0.1f), 1e-6f)
        assertEquals(0.4f, progressStep(0.49f), 1e-6f)
        assertEquals(0.9f, progressStep(0.999f), 1e-6f)
        assertEquals(1f, progressStep(1f))
        assertEquals(1f, progressStep(1.5f))
        assertEquals(0f, progressStep(-0.2f))
    }

    @Test
    fun `낭독 백분율`() {
        assertEquals(0, progressPercent(0.05f))
        assertEquals(30, progressPercent(1f / 3))
        assertEquals(70, progressPercent(0.7f))
        assertEquals(100, progressPercent(1f))
    }
}
