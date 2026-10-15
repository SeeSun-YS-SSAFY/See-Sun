package kr.seesun.feature.exercise

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.accessibility.enableAccessibilityChecks
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import javax.inject.Inject
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kr.seesun.core.data.db.ExerciseLogDao
import kr.seesun.core.data.db.ExerciseLogEntity
import kr.seesun.core.data.db.ExerciseLogState
import kr.seesun.feature.exercise.safety.SafetyContent
import kr.seesun.feature.exercise.safety.SafetyStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/**
 * 끝에서 끝까지(E2E): 실제 Hilt 그래프·암호화 Room 큐·내장 운동 정보로
 * 목록 → 운동 시작 → 마치기 → 기록이 오프라인 큐에 쌓이고 전송이 예약되는지.
 * 서버는 닿지 않는 주소라 기기 내장 운동 정보(29종)를 쓴다. 재생기만 소리 없는 가짜다.
 */
@HiltAndroidTest
class ExerciseFlowTest {

    @get:Rule(order = 0)
    val hilt = HiltAndroidRule(this)

    @get:Rule(order = 1)
    val rule = createAndroidComposeRule<HiltTestActivity>()

    @Inject lateinit var dao: ExerciseLogDao

    @Inject lateinit var safety: SafetyStore

    /** 이 테스트 전부터 있던 기록(기기 DB는 테스트끼리 공유된다). */
    private var before: Set<String> = emptySet()

    @Before
    fun setUp() {
        hilt.inject()
        before = runBlocking { dao.observeRecent(LIMIT).first().map { it.clientUuid }.toSet() }
        RecordingScheduler.count = 0
        FakeGuidePlayer.created.clear()
        rule.enableAccessibilityChecks()
        rule.setThemed { ExerciseNavHost(onExit = {}) }
    }

    private fun button(text: String) = hasText(text) and SemanticsMatcher.keyIsDefined(SemanticsProperties.Role)

    private fun waitForText(text: String, timeoutMs: Long = 10_000) = rule.waitUntil(timeoutMs) {
        rule.onAllNodes(hasText(text, substring = true)).fetchSemanticsNodes().isNotEmpty()
    }

    private fun newLogs(): List<ExerciseLogEntity> = runBlocking { dao.observeRecent(LIMIT).first().filter { it.clientUuid !in before } }

    private fun onlyLog(): ExerciseLogEntity = newLogs().also { assertEquals("새로 쌓인 기록 수", 1, it.size) }.single()

    private fun pressBack() = rule.activityRule.scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }

    private companion object {
        const val LIMIT = 1_000
    }

    @Test
    fun 목록에서_운동을_시작해_마치면_기록이_오프라인_큐에_쌓인다() {
        safety.clear() // 처음 운동하는 사용자
        rule.shot("01_운동_첫화면")
        rule.onNode(button("한 가지 운동")).performScrollTo().performClick()
        waitForText("전체 운동")
        rule.shot("02_한가지운동_메뉴")
        rule.onNode(button("전체 운동")).performScrollTo().performClick()
        waitForText("전체 29개")
        rule.onNodeWithText("1번째, 전체 29개. 맨몸 스쿼트").assertExists()
        rule.onNodeWithText("다음").performClick()
        rule.shot("03_운동목록_스크롤강조_2번째")
        rule.onNodeWithText("이전").performClick()
        rule.onNode(button("맨몸 스쿼트")).performClick()

        // 첫 운동: 안전 안내(T-08) → 건강 확인 7문항(기기에만 저장) → 결과 → 그다음 재생
        waitForText("건강 확인 시작")
        rule.shot("04_첫운동_안전안내")
        rule.onNode(button("건강 확인 시작")).performScrollTo().performClick()
        waitForText("건강 확인 1/7")
        rule.shot("05_첫운동_건강확인_문항1")
        rule.onNode(button("예")).performScrollTo().performClick()
        repeat(SafetyContent.QUESTIONS.size - 1) { rule.onNode(button("아니요")).performScrollTo().performClick() }
        waitForText("예라고 답한 문항이 있어요")
        rule.shot("06_첫운동_건강확인_결과_상담권유")
        rule.onNode(button("확인하고 운동하기")).performScrollTo().performClick()
        val record = safety.load()!!
        assertEquals(listOf(true) + List(6) { false }, record.answers)

        // TalkBack 꺼진 기기: 안전 안내를 마치면 안내를 바로 재생하고 세션을 시작한다
        waitForText("상태: 재생 중")
        rule.shot("07_운동재생_재생중")
        rule.waitUntil(5_000) { runBlocking { dao.drafts().any { it.clientUuid !in before } } }
        FakeGuidePlayer.created.last().finishNow()
        waitForText("상태: 안내 끝")
        rule.onNodeWithText("운동 마치기를 눌러 주세요", substring = true).assertExists()
        rule.shot("08_운동재생_안내끝")

        rule.onNode(button("운동 마치기")).performScrollTo().performClick()
        waitForText("운동을 기록했어요")
        rule.shot("09_운동기록_저장완료")
        val log = onlyLog()
        assertEquals(ExerciseLogState.PENDING, log.state)
        assertEquals("맨몸 스쿼트", log.exerciseName)
        assertEquals("MANUAL", log.mode)
        assertEquals("COMPLETED", log.status)
        val items = Json.parseToJsonElement(log.itemsJson).jsonArray
        assertEquals("72b12c60-d356-424c-a2f9-d111908992fe", items.single().jsonObject["exercise_id"]!!.jsonPrimitive.content)
        assertTrue("전송 예약", RecordingScheduler.count >= 1)

        // 두 번 누르기·다시 누르기로 기록이 늘지 않는다
        rule.onNodeWithText("목록으로").assertExists()
        assertEquals(1, newLogs().size)
    }

    @Test
    fun 루틴_중_뒤로_가기는_확인을_묻고_끝내면_하지_않은_운동은_건너뜀으로_기록된다() {
        safety.save(List(SafetyContent.QUESTIONS.size) { false }) // 안전 안내는 이미 마친 사용자
        rule.onNode(button("루틴 운동")).performScrollTo().performClick()
        waitForText("근력 운동 루틴")
        rule.shot("10_루틴_메뉴")
        rule.onNode(button("근력 운동 루틴")).performScrollTo().performClick()
        waitForText("루틴 시작")
        rule.shot("11_루틴_시작전_순서")
        rule.onNode(button("루틴 시작")).performScrollTo().performClick()
        waitForText("상태: 재생 중")

        // 실수로 뒤로 가기 → 확인 대화상자 → 계속하기 → 그대로 운동 중
        rule.shot("12_루틴_첫운동_재생중")
        pressBack()
        waitForText("루틴을 끝낼까요?")
        rule.shot("13_루틴_뒤로가기_확인", dialog = true)
        rule.onNodeWithText("계속하기").performClick()
        rule.onNodeWithText("상태: 재생 중").assertExists()

        // 첫 운동 안내가 끝나면 휴식과 다음 운동 안내
        FakeGuidePlayer.created.last().finishNow()
        waitForText("남은 휴식")
        rule.onNodeWithText("다음 운동: 플랭크").assertExists()
        rule.shot("14_루틴_휴식_다음운동안내")
        rule.onNode(button("휴식 건너뛰기")).performScrollTo().performClick()
        waitForText("운동 8개 중 2번째")
        rule.shot("15_루틴_두번째운동")

        rule.onNode(button("루틴 끝내기")).performScrollTo().performClick()
        waitForText("루틴을 끝낼까요?")
        rule.shot("16_루틴_끝내기_확인", dialog = true)
        rule.onNodeWithText("기록하고 끝내기").performClick()
        rule.waitUntil(10_000) { newLogs().any { it.state == ExerciseLogState.PENDING } }

        val log = onlyLog()
        assertEquals("CURRICULUM", log.mode)
        assertEquals("근력 운동 루틴", log.exerciseName)
        val items = Json.parseToJsonElement(log.itemsJson).jsonArray.map { it.jsonObject }
        assertEquals(8, items.size)
        assertFalse(items[0]["is_skipped"]!!.jsonPrimitive.content.toBoolean())
        assertFalse(items[1]["is_skipped"]!!.jsonPrimitive.content.toBoolean())
        assertTrue(
            items.drop(2).all {
                it["is_skipped"]!!.jsonPrimitive.content.toBoolean() &&
                    it["skip_reason"]!!.jsonPrimitive.content == "USER_SKIP"
            }
        )
    }
}
