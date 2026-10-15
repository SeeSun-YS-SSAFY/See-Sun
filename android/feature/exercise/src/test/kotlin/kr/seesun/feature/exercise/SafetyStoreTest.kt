package kr.seesun.feature.exercise

import kr.seesun.core.auth.security.InMemorySecretStore
import kr.seesun.core.network.SeeSunJson
import kr.seesun.feature.exercise.safety.SafetyContent
import kr.seesun.feature.exercise.safety.SafetyStore
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** T-08: 첫 운동 안전 문답은 기기(암호화 저장소)에만 두고, 문항·안내 문구가 요구 범위를 덮는다. */
class SafetyStoreTest {

    @Test
    fun `답은 기기 저장소에만 저장되고 다시 읽힌다`() {
        val secrets = InMemorySecretStore()
        val store = SafetyStore(secrets, SeeSunJson)
        assertFalse(store.isDone())
        store.save(listOf(false, true, false, false, false, false, false))
        assertTrue(store.isDone())
        assertEquals(true, store.load()!!.answers[1])
        store.clear()
        assertFalse(store.isDone())
    }

    @Test
    fun `안내는 공간 안전과 즉시 멈추는 방법을 말하고 문항은 7개`() {
        listOf("부딪힐", "미끄럽지", "바로 멈추", "일시정지", "이어폰", "뒤로 가기").forEach {
            assertTrue(SafetyContent.NOTICE.contains(it), it)
        }
        assertEquals(7, SafetyContent.QUESTIONS.size)
        assertTrue(SafetyContent.SOME_YES.contains("상담"))
    }
}
