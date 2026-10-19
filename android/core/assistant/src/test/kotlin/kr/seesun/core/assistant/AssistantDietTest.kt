package kr.seesun.core.assistant

import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** v2.0 식단 의도(설계 §3.4·§7 v2.0): A-02·D-02는 확인 화면이 있는 기록 흐름만 열고, Q-03·Q-04·Q-06·Q-07은 고정 문구로 그 자리에서 답한다. */
class AssistantDietTest {
    private val engine = AssistantFixtures.engine()
    private val screen = AssistantFixtures.unknown

    private suspend fun hear(session: AssistantSession, text: String) = requireNotNull(session.hear(listOf(text), screen)) { text }

    private fun AssistantReply.destination(): Destination? =
        actions.filterIsInstance<AssistantAction.Navigate>().singleOrNull()?.destination

    @Test
    fun `A-02 한 문장 기록은 저장하지 않고 들은 말을 채운 기록 흐름(확인 화면)을 연다`() = runTest {
        val reply = hear(AssistantSession(engine, AssistantFixtures.FakeFacts()), "점심에 김치찌개 한 그릇이랑 밥 반 공기 먹었어")
        val dest = reply.destination() as Destination.DietRecord
        assertEquals("점심에 김치찌개 한 그릇이랑 밥 반 공기 먹었어", dest.prefill)
        assertTrue(dest.confirmScreen, "저장은 확인 화면에서만")
        assertEquals(IntentId.DIET_RECORD, reply.intent)
        assertEquals(1, reply.actions.size, "이동 하나뿐(저장 없음)")
    }

    @Test
    fun `D-02 식사 기록 시작은 빈 흐름(날짜부터)`() = runTest {
        val dest = hear(AssistantSession(engine, AssistantFixtures.FakeFacts()), "식사 기록할래").destination()
        assertEquals(Destination.DietRecord(null), dest)
    }

    @Test
    fun `Q-03 오늘 칼로리는 목표 대비와 남은 양을 고정 문구로`() = runTest {
        val session = AssistantSession(engine, AssistantFixtures.FakeFacts())
        assertEquals("오늘 1,240킬로칼로리 드셨어요. 목표 2,000킬로칼로리의 62%예요.", hear(session, "오늘 몇 칼로리 먹었어").say)
        assertEquals("오늘 1,240킬로칼로리 드셨어요. 목표까지 760킬로칼로리 남았어요.", hear(session, "칼로리 얼마나 남았어").say)
    }

    @Test
    fun `Q-03 기록이 없으면 식사를 기록할지 묻고 네면 기록 흐름`() = runTest {
        val session = AssistantSession(engine, AssistantFixtures.FakeFacts(kcal = KcalFact(0, 2000, meals = 0)))
        assertEquals(DietWording.KCAL_NONE, hear(session, "오늘 몇 칼로리 먹었어").say)
        assertEquals(Destination.DietRecord(), hear(session, "네").destination())
    }

    @Test
    fun `Q-04 오늘 먹은 것은 끼니별로 읽고 네면 오늘 기록 목록`() = runTest {
        val facts = AssistantFixtures.FakeFacts()
        val session = AssistantSession(engine, facts)
        val reply = hear(session, "점심에 뭐 먹었어")
        assertEquals("오늘 기록: 점심 김치찌개 한 그릇, 밥 반 공기. 기록 목록을 열까요?", reply.say)
        assertEquals("LUNCH" to false, facts.mealQueries.single())
        assertEquals(Destination.DietToday, hear(session, "응").destination())
    }

    @Test
    fun `Q-04 어제 기록이 없으면 그렇게 말한다`() = runTest {
        val facts = AssistantFixtures.FakeFacts(mealFacts = emptyList())
        val reply = hear(AssistantSession(engine, facts), "어제 뭐 먹었지")
        assertEquals("어제는 기록한 식사가 없어요. 식사를 기록할까요?", reply.say)
        assertEquals(null to true, facts.mealQueries.single())
    }

    @Test
    fun `Q-06 음식 정보는 영양 DB 값 + 참고 정보 고지`() = runTest {
        val session = AssistantSession(engine, AssistantFixtures.FakeFacts())
        assertEquals(
            "떡볶이 1인분(180그램)은 259킬로칼로리예요. 식약처 자료로 계산한 참고 정보예요.",
            hear(session, "떡볶이 칼로리 얼마야").say
        )
        assertEquals(
            "바나나 1인분(180그램)은 259킬로칼로리, 당류 9그램이에요. 식약처 자료로 계산한 참고 정보예요.",
            hear(session, "바나나 당 많아?").say
        )
    }

    @Test
    fun `Q-06 찾지 못하거나 끊기면 그대로 말한다`() = runTest {
        val notFound = AssistantSession(engine, AssistantFixtures.FakeFacts(foodFact = { FoodFact.NotFound(it) }))
        assertEquals("「마라탕」은 영양 정보에서 찾지 못했어요.", hear(notFound, "마라탕 칼로리 얼마야").say)
        val offline = AssistantSession(engine, AssistantFixtures.FakeFacts(foodFact = { FoodFact.Unavailable(it) }))
        assertEquals("인터넷이 연결되지 않아 마라탕 영양 정보를 찾지 못했어요.", hear(offline, "마라탕 칼로리 얼마야").say)
    }

    @Test
    fun `Q-07 먹어도 돼는 판단하지 않고 알레르기 대조와 지표만 읽는다`() = runTest {
        val facts = AssistantFixtures.FakeFacts(checkFact = {
            FoodCheckFact(it, found = true, allergyHits = listOf("메밀"), sugarLabel = "보통")
        })
        val reply = hear(AssistantSession(engine, facts), "메밀국수 먹어도 돼?")
        assertEquals(
            "메밀국수: 등록하신 알레르기 중 메밀이 들어 있을 수 있어요. 당류·탄수화물 지표는 보통이에요. " + DietWording.CHECK_CLOSE,
            reply.say
        )
        assertTrue(reply.actions.isEmpty(), "이동·저장 없음")
        assertEquals(IntentId.FOOD_CHECK, reply.intent)
    }

    @Test
    fun `Q-07 약·질환이 섞이면 안전 X-01이 먼저`() = runTest {
        val reply = hear(AssistantSession(engine, AssistantFixtures.FakeFacts()), "당뇨인데 떡볶이 먹어도 돼?")
        assertEquals(IntentId.SAFETY_MEDICAL, reply.intent)
        assertEquals(SafetyWording.MEDICAL, reply.say)
    }

    @Test
    fun `v2_1 의도(식단 점수)는 아직 준비 중`() = runTest {
        val reply = hear(AssistantSession(engine, AssistantFixtures.FakeFacts()), "오늘 식단 점수 알려 줘")
        assertEquals(AssistantReplies.DIET_SOON, reply.say)
        assertFalse(IntentId.DIET_SCORE.ready)
        assertTrue(IntentId.DIET_RECORD.ready && IntentId.FOOD_CHECK.ready)
    }

    @Test
    fun `음식 이름 뽑기는 영양소·질문·때 낱말을 버린다`() {
        assertEquals("떡볶이", IntentRules.foodPhrase("떡볶이 칼로리 얼마야"))
        assertEquals("메밀국수", IntentRules.foodPhrase("메밀국수는 먹어도 돼?"))
        assertEquals(null, IntentRules.foodPhrase("오늘 몇 칼로리 먹었어"))
        assertEquals(null, IntentRules.foodPhrase("지금까지 몇 칼로리 먹었지"))
        assertNotNull(IntentRules.foodPhrase("당근 칼로리"))
    }

    /** 법적 검토 T-02~T-06·계획 §3.3: 진단·치료·예방·효능·위험 등 의료 표현 금지(서버 test_legal_wording과 같은 기준 + 처방·완치). */
    @Test
    fun `식단 답 문구에 의료 표현이 없다`() {
        val texts = DietWording.ALL + AssistantReplies.DIET_SOON
        val hits = texts.filter { BANNED.containsMatchIn(it.replace("알레르기 주의", "")) }
        assertTrue(hits.isEmpty(), hits.joinToString("\n"))
    }

    companion object {
        val BANNED = Regex("""위험|혈당|당뇨에\s*좋|좋은\s*음식|낮추|예방|치료|효능|효과|안전|경고|주의|처방|완치|진단해|진단합""")
    }
}
