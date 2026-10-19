package kr.seesun.core.assistant

import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** 대화 흐름(처리 순서 1단계·확인 단계·되돌리기·도움말) 시험. */
class AssistantSessionTest {
    private var now = 0L
    private val facts = AssistantFixtures.FakeFacts()
    private val session = AssistantSession(AssistantFixtures.engine(), facts) { now }
    private val main = AssistantFixtures.main
    private val unknown = AssistantFixtures.unknown

    private suspend fun hear(text: String, screen: ScreenVoiceContext = unknown) = session.hear(listOf(text), screen)

    private fun AssistantReply?.dials() = this?.actions?.filterIsInstance<AssistantAction.Dial>().orEmpty()

    @Test
    fun 응급은_멈추고_묻기만_한다_네를_들어야_119를_채운다() = runTest {
        val first = hear("가슴이 아파", AssistantFixtures.playback)
        assertEquals(SafetyWording.EMERGENCY, first?.say)
        assertTrue(first!!.actions.contains(AssistantAction.PausePlayback))
        assertTrue(first.dials().isEmpty())
        assertEquals(listOf(SafetyWording.CALL_119, SafetyWording.NO_CALL), first.buttons.map { it.label })
        val yes = hear("네")
        assertEquals(listOf(AssistantAction.Dial("119")), yes.dials())
        assertEquals(SafetyWording.EMERGENCY_DIALED, yes?.say)
    }

    @Test
    fun 직접_119를_불러_달라고_해도_확인을_거친다_아니요면_전화하지_않는다() = runTest {
        assertTrue(hear("119 불러 줘").dials().isEmpty())
        val no = hear("아니요")
        assertTrue(no.dials().isEmpty())
        assertEquals(SafetyWording.EMERGENCY_DECLINED, no?.say)
        // 묻던 것이 끝났으니 뒤늦은 「네」에도 전화하지 않는다.
        assertTrue(hear("네").dials().isEmpty())
    }

    @Test
    fun 마음_위기는_109를_묻는다() = runTest {
        val first = hear("죽고 싶어")
        assertEquals(SafetyWording.CRISIS, first?.say)
        assertTrue(first.dials().isEmpty())
        assertEquals(listOf(AssistantAction.Dial("109")), hear("응 걸어 줘").dials())
    }

    @Test
    fun 묻는_중에_다른_말을_하면_묻던_것을_거두고_전화하지_않는다() = runTest {
        hear("숨이 차")
        val other = hear("운동 기록 보여 줘")
        assertTrue(other.dials().isEmpty())
        assertTrue(other!!.actions.any { it is AssistantAction.Navigate })
        assertTrue(hear("네").dials().isEmpty())
    }

    @Test
    fun 의료_판단은_거절만_한다() = runTest {
        val r = hear("이 약 두 알 먹어도 돼?")
        assertEquals(SafetyWording.MEDICAL, r?.say)
        assertTrue(r!!.actions.isEmpty())
    }

    @Test
    fun 말_속도는_바로_바꾸고_10초_안에_되돌릴_수_있다() = runTest {
        val r = hear("말 빠르게 해 줘")
        assertEquals(listOf(AssistantAction.SetSpeed("FAST")), r?.actions)
        now += 5_000
        assertEquals(listOf(AssistantAction.SetSpeed("NORMAL")), hear("되돌려")?.actions)
        hear("천천히 말해")
        now += AssistantSession.UNDO_MS + 1
        assertNull(hear("되돌려")?.actions?.firstOrNull { it is AssistantAction.SetSpeed })
    }

    @Test
    fun 자동_듣기_끄기는_되읽기_확인_뒤에만_바꾼다() = runTest {
        val ask = hear("자동으로 듣지 마")
        assertEquals(AssistantReplies.AUTO_OFF_ASK, ask?.say)
        assertTrue(ask!!.actions.isEmpty())
        assertEquals(listOf(AssistantAction.SetAutoListen(false)), hear("네")?.actions)
    }

    @Test
    fun 몸무게_값은_저장하지_않고_값이_채워진_확인_화면을_연다() = runTest {
        val r = hear("몸무게 65킬로로 바꿔 줘")
        val nav = r?.actions?.single() as AssistantAction.Navigate
        assertEquals(Destination.ProfileEdit("WEIGHT", "65"), nav.destination)
        assertTrue(nav.destination.confirmScreen)
    }

    @Test
    fun 도움말은_세_개씩_읽고_더_들을지_묻는다() = runTest {
        val first = hear("도움말", main)!!
        assertTrue(first.say!!.contains("「근력 운동 보여 줘」"))
        assertTrue(first.say!!.endsWith(AssistantReplies.HELP_MORE))
        val next = hear("네", main)!!
        assertTrue(next.say!!.contains("「내 목표가 뭐야」"))
    }

    @Test
    fun 여기_어디야는_화면_이름과_할_수_있는_일을_답한다() = runTest {
        val r = hear("여기 어디야", AssistantFixtures.exerciseHome)!!
        assertTrue(r.say!!.startsWith("운동 방식 화면이에요."))
        assertTrue(r.actions.isEmpty())
    }

    @Test
    fun 아까_화면은_기억이_있을_때만_돌아간다() = runTest {
        assertEquals(listOf(AssistantAction.ToOrigin), hear("아까 화면으로")?.actions)
        facts.origin = false
        assertEquals(AssistantReplies.NO_ORIGIN, hear("아까 화면으로")?.say)
    }

    @Test
    fun 이번_주_운동은_그_자리에서_답하고_네면_기록_화면으로() = runTest {
        val r = hear("이번 주에 운동 몇 번 했어")!!
        assertEquals("이번 주에는 운동을 3번, 모두 75분 했어요. 기록 화면을 열까요?", r.say)
        assertEquals(listOf(AssistantAction.Navigate(AssistantRoutes.WEEKLY)), hear("네")?.actions)
    }

    @Test
    fun 목표는_고정_틀에_값만_끼운다() = runTest {
        assertEquals("목표는 체중 감량, 하루 목표는 1,800킬로칼로리예요.", hear("내 목표가 뭐야")?.say)
    }

    @Test
    fun 받아쓰기가_틀린_운동_이름은_묻고_간다() = runTest {
        val ask = hear("스쿼드 할래")!!
        assertTrue(ask.actions.isEmpty())
        assertTrue(ask.say!!.endsWith("갈까요?"))
        val go = hear("네")?.actions?.single() as AssistantAction.Navigate
        assertEquals(ExerciseEntry.PLAY, (go.destination as Destination.Exercise).entry)
    }

    @Test
    fun 값_입력_칸에서는_말_전체가_값이고_취소는_묻고_나간다() = runTest {
        val value = AssistantFixtures.value
        assertNull(session.hear(listOf("김치찌개"), value))
        assertNull(session.hear(listOf("운동"), value))
        assertEquals(listOf(AssistantAction.Home), session.hear(listOf("시선아 처음으로"), value)?.actions)
        val ask = session.hear(listOf("취소"), value)!!
        assertTrue(ask.actions.isEmpty())
        assertEquals(listOf(AssistantAction.Back), session.hear(listOf("네"), value)?.actions)
    }

    @Test
    fun 화면_명령이_더_구체적이지_않으면_화면이_받는다() = runTest {
        assertEquals(listOf(AssistantAction.RunLocal(1)), hear("운동하러 가자", main)?.actions)
        val global = hear("근력 운동 보여 줘", main)?.actions?.single() as AssistantAction.Navigate
        assertEquals(Destination.Exercise(ExerciseEntry.CATEGORY, "근력 운동", "근력"), global.destination)
        // 「시선아」로 시작하면 화면 명령을 건너뛴다.
        assertTrue(hear("시선아 운동", main)!!.actions.single() is AssistantAction.Navigate)
    }

    @Test
    fun 후보_여럿이면_안전을_먼저_보고_다음은_맞는_첫_후보() = runTest {
        val r = session.hear(listOf("가슴이 아파트", "가슴이 아파"), unknown)
        assertEquals(SafetyWording.EMERGENCY, r?.say)
        val second = session.hear(listOf("음", "마이페이지"), unknown)
        assertEquals(listOf(AssistantAction.Navigate(Destination.MyPage)), second?.actions)
    }

    @Test
    fun 운동_재생_중에는_다른_화면으로_데려가지_않는다() = runTest {
        val playback = AssistantFixtures.playback
        val r = session.hear(listOf("마이페이지"), playback)!!
        assertEquals(AssistantReplies.PLAYBACK_BLOCKED, r.say)
        assertTrue(r.actions.isEmpty())
        assertEquals(listOf(AssistantAction.Back), session.hear(listOf("뒤로"), playback)?.actions)
    }

    @Test
    fun 알아듣지_못하면_null_화면이_안내한다() = runTest {
        assertNull(hear("음"))
        assertNotNull(hear("날씨 어때"))
        assertFalse(session.asking)
    }
}
