package kr.seesun.feature.exercise

import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasStateDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.accessibility.enableAccessibilityChecks
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.dp
import java.time.LocalDate
import kr.seesun.core.data.model.ExerciseDetail
import kr.seesun.core.data.model.ExerciseSummary
import kr.seesun.core.data.model.GuideSection
import kr.seesun.core.data.model.PictogramFrame
import kr.seesun.core.data.model.Routine
import kr.seesun.core.data.model.RoutineItem
import kr.seesun.core.network.model.ExerciseWeekDayDto
import kr.seesun.core.network.model.ExerciseWeekDto
import kr.seesun.core.uitest.pageUntil
import kr.seesun.feature.exercise.home.ExerciseHomeScreen
import kr.seesun.feature.exercise.home.HomeDestination
import kr.seesun.feature.exercise.home.HomeUi
import kr.seesun.feature.exercise.list.ExerciseListScreen
import kr.seesun.feature.exercise.list.ListUi
import kr.seesun.feature.exercise.play.ExercisePlayerScreen
import kr.seesun.feature.exercise.play.PlayerUi
import kr.seesun.feature.exercise.player.GuideState
import kr.seesun.feature.exercise.player.GuideStatus
import kr.seesun.feature.exercise.routine.RoutineActions
import kr.seesun.feature.exercise.routine.RoutinePhase
import kr.seesun.feature.exercise.routine.RoutinePlayerScreen
import kr.seesun.feature.exercise.routine.RoutineUi
import kr.seesun.feature.exercise.ui.ExerciseDialog
import kr.seesun.feature.exercise.ui.LocalPictogramLoader
import kr.seesun.feature.exercise.ui.PICTOGRAM_FRAME_MS
import kr.seesun.feature.exercise.ui.PictogramPlayer
import kr.seesun.feature.exercise.weekly.WeeklyScreen
import kr.seesun.feature.exercise.weekly.WeeklyUi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/**
 * 운동 화면 접근성 검사(Accessibility Test Framework: 레이블·터치 크기·대비 등은 동작마다 검사) +
 * 1차 결함(A1·A2 아이콘 이름 낭독, A3 색만으로 상태, A4 스와이프 전용 목록) 재발 방지 + 200% 글꼴.
 */
class ExerciseScreensA11yTest {

    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    @Before
    fun setUp() {
        rule.enableAccessibilityChecks()
    }

    private fun pane(title: String) = SemanticsMatcher.expectValue(SemanticsProperties.PaneTitle, title)

    private val sections = listOf(
        GuideSection("first_description", "준비 자세", "발을 어깨 너비로 벌립니다. 팔을 앞으로 뻗습니다.", "http://h/a.mp3"),
        GuideSection("main_form", "동작 시작", "천천히 앉습니다.", null)
    )
    private val detail = ExerciseDetail("e1", "맨몸 스쿼트", "근력 운동", sections, emptyList(), needsAssistant = false)
    private val noActions = RoutineActions({}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {})

    @Test
    fun 운동_첫_화면은_방식_버튼과_로그인_안내를_글자로_보인다() {
        var opened: HomeDestination? = null
        rule.setThemed {
            ExerciseHomeScreen(HomeUi(signedIn = false, pending = 2), {}, { opened = it })
        }
        rule.onNode(pane("운동")).assertExists()
        // 화면에는 한 줄로 줄이고, TalkBack에는 전체 문장을 읽힌다.
        rule.onNodeWithText("보내지 못한 운동 기록 2건", substring = true).assertExists()
        rule.onNode(hasContentDescription("아직 서버에 보내지 못한 운동 기록이 2건", substring = true)).assertExists()
        rule.onNodeWithText("루틴 운동").performScrollTo().performClick()
        assertEquals(HomeDestination.ROUTINE, opened)
        rule.assertNoLigatureNames()
    }

    @Test
    fun 운동_목록은_스와이프_없이_이전_다음_버튼으로_모두_탐색되고_200퍼센트에서도_잘리지_않는다() {
        val items = listOf("맨몸 스쿼트", "플랭크", "밴드 풀 어파트").mapIndexed { i, n -> ExerciseSummary("e$i", n) }
        var opened: ExerciseSummary? = null
        rule.setThemed(fontScale = 2f) {
            ExerciseListScreen("근력 운동", ListUi(loading = false, items = items), {}, { opened = it }, {})
        }
        rule.onNodeWithText("1번째, 전체 3개. 맨몸 스쿼트").assertExists()
        rule.onNodeWithText("다음").performClick()
        rule.onNodeWithText("2번째, 전체 3개. 플랭크").assertExists()
        rule.onNodeWithText("다음").performClick()
        rule.onNodeWithText("3번째, 전체 3개. 밴드 풀 어파트").assertExists()
        rule.onNodeWithText("다음").performClick() // 끝: 말로 알림
        rule.onNodeWithText("이전").performClick()
        rule.onNodeWithText("2번째, 전체 3개. 플랭크").assertExists()
        // 각 운동은 TalkBack으로 하나씩 갈 수 있는 버튼이고, 누르면 그 운동을 연다
        rule.onNode(hasText("플랭크") and SemanticsMatcher.keyIsDefined(SemanticsProperties.Role)).performClick()
        assertEquals("플랭크", opened?.name)
        rule.assertNoTextOverflow()
    }

    @Test
    fun 재생_화면은_명시_버튼과_글자_상태와_지금_문장_표시를_가진다() {
        rule.setThemed {
            ExercisePlayerScreen(
                ui = PlayerUi(loading = false, detail = detail, sessionActive = true),
                guide = GuideState(sections = sections, sectionIndex = 0, sentenceIndex = 1, status = GuideStatus.PLAYING),
                onBack = {}, onPlayPause = {}, onReplay = {}, onPrevious = {}, onNext = {}, onSpeed = {},
                onFavorite = {}, onFinish = {}, onRetry = {}, onAgain = {}, onDone = {}
            )
        }
        rule.onNode(pane("맨몸 스쿼트")).assertExists()
        rule.onNodeWithText("상태: 재생 중").assertExists()
        rule.onNodeWithText("구간 1/2: 준비 자세").assertExists()
        listOf("일시정지", "이 구간 다시 듣기", "이전 구간", "다음 구간", "재생 속도 1배", "즐겨찾기 넣기").forEach {
            rule.onNodeWithText(it).performScrollTo().assertIsEnabled().performClick()
        }
        rule.onNodeWithText("운동 마치기").performScrollTo().assertIsEnabled()
        // 그림이 없으면 그림 자리에 대본(가사)을 보인다. 지금 읽는 문장은 색만이 아니라 글자·상태로도 알린다(A3)
        rule.onNode(hasContentDescription("2번째 문장으로 이동: 팔을 앞으로 뻗습니다.") and hasStateDescription("지금 읽는 문장"))
            .assertExists()
        rule.assertNoLigatureNames()
    }

    // 맨몸 스쿼트 연결표 모양(육안 검토): 준비 자세 0번 → 준비 자세 1번 → 동작 시작 0번, 따라 하기는 없음
    private val mapped = detail.copy(
        pictograms = listOf("p1", "p2", "p3"),
        pictogramFrames = listOf(
            PictogramFrame("p1", 1, "first_description", 0, "선 자세"),
            PictogramFrame("p2", 2, "first_description", 1, "팔을 앞으로"),
            PictogramFrame("p3", 3, "main_form", 0, "앉기")
        )
    )

    private fun setPlayer(guide: GuideState, onSeek: (Int, Int) -> Unit, fontScale: Float = 1f) = rule.setThemed(fontScale) {
        CompositionLocalProvider(LocalPictogramLoader provides { ImageBitmap(8, 6) }) {
            ExercisePlayerScreen(
                ui = PlayerUi(loading = false, detail = mapped, sessionActive = true),
                guide = guide,
                onBack = {}, onPlayPause = {}, onReplay = {}, onPrevious = {}, onNext = {}, onSpeed = {},
                onFavorite = {}, onFinish = {}, onRetry = {}, onAgain = {}, onDone = {}, onSeek = onSeek
            )
        }
    }

    @Test
    fun 그림은_대본_위치를_따라가고_그림_띠를_누르면_그_부분으로_간다() {
        val seeks = mutableListOf<Pair<Int, Int>>()
        var guide by mutableStateOf(GuideState(sections = sections, sectionIndex = 0, sentenceIndex = 0, status = GuideStatus.PLAYING))
        rule.setThemed {
            CompositionLocalProvider(LocalPictogramLoader provides { ImageBitmap(8, 6) }) {
                ExercisePlayerScreen(
                    ui = PlayerUi(loading = false, detail = mapped, sessionActive = true),
                    guide = guide,
                    onBack = {}, onPlayPause = {}, onReplay = {}, onPrevious = {}, onNext = {}, onSpeed = {},
                    onFavorite = {}, onFinish = {}, onRetry = {}, onAgain = {}, onDone = {}, onSeek = { s, i -> seeks += s to i }
                )
            }
        }
        rule.waitUntil(2_000) { frameShown(0) }
        rule.shot("07b_운동재생_그림_대본위치")
        guide = guide.copy(sentenceIndex = 1)
        rule.waitUntil(2_000) { frameShown(1) }
        guide = guide.copy(sectionIndex = 1, sentenceIndex = 0)
        rule.waitUntil(2_000) { frameShown(2) }
        // 그림 띠: 버튼마다 그림 이름 + 이동 안내, 지금 그림은 상태로 알림
        rule.onNode(hasContentDescription("동작 그림 3, 앉기. 해당 구간으로 이동") and hasStateDescription("지금 보이는 그림")).assertExists()
        rule.onNode(hasContentDescription("동작 그림 2, 팔을 앞으로. 해당 구간으로 이동")).performClick()
        assertEquals(listOf(0 to 1), seeks)
        rule.assertNoLigatureNames()
    }

    @Test
    fun 그림을_누르면_크게_보기가_열리고_그림_이름과_이전_다음_닫기가_있다() {
        setPlayer(GuideState(sections = sections, sectionIndex = 1, sentenceIndex = 0, status = GuideStatus.PAUSED), { _, _ -> })
        rule.onNode(hasContentDescription("그림 크게 보기 — 앉기")).performClick()
        rule.onNode(pane("그림 크게 보기")).assertExists()
        rule.onNodeWithText("3/3 앉기").assertExists()
        rule.onNodeWithText("다음 그림").assertIsNotEnabled()
        rule.onNodeWithText("이전 그림").performClick()
        rule.onNodeWithText("2/3 팔을 앞으로").assertExists()
        rule.onNodeWithText("닫기").performClick()
        rule.onNode(pane("그림 크게 보기")).assertDoesNotExist()
    }

    @Test
    fun 대본_보기에서_문장을_누르면_그_문장부터_재생하고_지금_문장을_따라간다() {
        val seeks = mutableListOf<Pair<Int, Int>>()
        setPlayer(GuideState(sections = sections, sectionIndex = 0, sentenceIndex = 1, status = GuideStatus.PLAYING), { s, i ->
            seeks +=
                s to i
        })
        rule.onNodeWithText("대본 보기").performClick()
        rule.onNode(hasContentDescription("2번째 문장으로 이동: 팔을 앞으로 뻗습니다.") and hasStateDescription("지금 읽는 문장"))
            .assertExists()
        rule.shot("07c_운동재생_대본보기")
        // 작은 화면에서는 대본이 여러 쪽이라 그 문장이 있는 쪽으로 넘긴다(스크롤 없음).
        assertTrue(rule.pageUntil("1번째 문장으로 이동: 천천히 앉습니다."))
        rule.onNode(hasContentDescription("1번째 문장으로 이동: 천천히 앉습니다.")).performClick()
        rule.onNode(hasContentDescription("이전 쪽")).let { prev ->
            repeat(PAGE_BACK) {
                if (rule.onAllNodes(
                        hasContentDescription("1번째 문장으로 이동: 발을", substring = true)
                    ).fetchSemanticsNodes().isEmpty()
                ) {
                    prev.performClick()
                }
            }
        }
        rule.onNode(hasContentDescription("1번째 문장으로 이동: 발을 어깨 너비로 벌립니다.")).performClick()
        assertEquals(listOf(1 to 0, 0 to 0), seeks)
        rule.onNodeWithText("그림 보기").performClick()
        rule.onNode(hasContentDescription("그림 크게 보기 — 팔을 앞으로")).assertExists()
    }

    @Test
    fun TalkBack_켜짐_진입_상태는_재생_대기이고_운동_마치기는_사유와_함께_비활성() {
        rule.setThemed(fontScale = 2f) {
            ExercisePlayerScreen(
                ui = PlayerUi(loading = false, detail = detail.copy(needsAssistant = true)),
                guide = GuideState(sections = sections, status = GuideStatus.IDLE),
                onBack = {}, onPlayPause = {}, onReplay = {}, onPrevious = {}, onNext = {}, onSpeed = {},
                onFavorite = {}, onFinish = {}, onRetry = {}, onAgain = {}, onDone = {}
            )
        }
        rule.onNodeWithText("상태: 준비됨").assertExists()
        rule.onNodeWithText("재생을 누르면 안내 음성을 시작해요", substring = true).assertExists()
        rule.onNodeWithText("보조자 필요: 보조자와 함께 하세요", substring = true).assertExists()
        rule.onNodeWithText("재생").performScrollTo().performClick()
        rule.onNode(hasText("운동 마치기")).performScrollTo().assertIsNotEnabled()
        rule.onNodeWithText("재생을 시작하면 누를 수 있어요").assertExists()
        rule.assertNoTextOverflow()
    }

    @Test
    fun 루틴_휴식_화면은_남은_시간과_다음_운동과_건너뛰기를_글자로_보인다() {
        val routine = Routine(
            null,
            "근력 운동 루틴",
            listOf(RoutineItem(null, "a", "맨몸 스쿼트", 1, null, 10), RoutineItem(null, "b", "플랭크", 1, null, 10))
        )
        rule.setThemed(fontScale = 2f) {
            RoutinePlayerScreen(
                "근력 운동 루틴",
                RoutineUi(phase = RoutinePhase.REST, routine = routine, stepIndex = 1, restRemaining = 7, sessionActive = true),
                GuideState(),
                noActions
            )
        }
        rule.onNodeWithText("남은 휴식 7초").assertExists()
        rule.onNodeWithText("다음 운동: 플랭크").assertExists()
        rule.onNodeWithText("휴식 건너뛰기").performScrollTo().performClick()
        rule.onNodeWithText("휴식 멈추기").performScrollTo().performClick()
        rule.assertNoTextOverflow()
    }

    @Test
    fun 주간_레포트는_요약과_요일별_글자_목록을_읽는다() {
        val week = ExerciseWeekDto(
            weekStart = "2026-10-12",
            weekEnd = "2026-10-18",
            sessionCount = 1,
            totalSeconds = 900,
            totalMinutes = 15,
            days = listOf(ExerciseWeekDayDto("2026-10-12", "월요일", 1, 900), ExerciseWeekDayDto("2026-10-13", "화요일", 0, 0)),
            ttsMessage = "이번 주 운동 1회, 총 15분이에요."
        )
        rule.setThemed {
            WeeklyScreen(WeeklyUi(LocalDate.parse("2026-10-12"), loading = false, week = week, pending = 1), {}, {}, {}, {})
        }
        rule.onNode(pane("이번 주 운동")).assertExists()
        rule.onNodeWithText("이번 주 운동 1회, 총 15분이에요.").assertExists()
        rule.onNode(hasContentDescription("월요일 10월 12일: 1회, 15분")).assertExists()
        rule.onNode(hasContentDescription("화요일 10월 13일: 운동 안 함")).assertExists()
        rule.shot("17_주간기록")
        rule.onNodeWithText("지난주 보기").performScrollTo().performClick()
    }

    private val frames = listOf("f0", "f1", "f2", "f3")

    private companion object {
        const val PAGE_BACK = 10
    }
    private val frameSlackMs = 100L

    private fun frameShown(i: Int) = rule.onAllNodes(hasTestTag("pictogram-frame-$i")).fetchSemanticsNodes().isNotEmpty()

    @Test
    fun 픽토그램은_재생_중_1초마다_다음_프레임으로_돌고_멈추면_멈춘다() {
        val loaded = mutableListOf<String>()
        var playing by mutableStateOf(true)
        rule.mainClock.autoAdvance = false
        rule.setThemed {
            PictogramPlayer(
                urls = frames,
                playing = playing,
                description = "맨몸 스쿼트 동작 그림",
                height = 160.dp,
                animate = true,
                loader = { url ->
                    loaded += url
                    ImageBitmap(8, 8)
                }
            )
        }
        rule.mainClock.advanceTimeByFrame()
        rule.waitUntil(2_000) { frameShown(0) }
        assertEquals(frames, loaded) // 모든 프레임을 미리 받아 둔다(다음 프레임 깜빡임 방지)
        listOf(1, 2, 3, 0).forEach { expected ->
            rule.mainClock.advanceTimeBy(PICTOGRAM_FRAME_MS)
            rule.waitUntil(2_000) { frameShown(expected) }
        }
        // 설명은 운동 단위로 고정이고 liveRegion이 없다(프레임마다 다시 읽지 않음).
        rule.onNode(hasContentDescription("맨몸 스쿼트 동작 그림"))
            .assert(SemanticsMatcher.keyNotDefined(SemanticsProperties.LiveRegion))
        // 일시정지·휴식·정지: 멈춘다.
        rule.mainClock.advanceTimeBy(PICTOGRAM_FRAME_MS)
        rule.waitUntil(2_000) { frameShown(1) }
        playing = false
        rule.mainClock.advanceTimeBy(PICTOGRAM_FRAME_MS * 3)
        assertTrue(frameShown(1))
        // 다시 재생하면 이어서 돈다.
        playing = true
        rule.mainClock.advanceTimeByFrame()
        rule.mainClock.advanceTimeByFrame()
        rule.mainClock.advanceTimeBy(PICTOGRAM_FRAME_MS + frameSlackMs)
        rule.waitUntil(2_000) { frameShown(2) }
        rule.onNode(hasContentDescription("맨몸 스쿼트 동작 그림")).assertExists()
    }

    @Test
    fun 재생_전_대기_상태에서도_첫_그림을_보이고_나머지_그림을_미리_받는다() {
        val loaded = mutableListOf<String>()
        rule.mainClock.autoAdvance = false
        rule.setThemed {
            PictogramPlayer(
                urls = frames,
                playing = false,
                description = "맨몸 스쿼트 동작 그림",
                height = 160.dp,
                animate = true,
                loader = { url ->
                    loaded += url
                    ImageBitmap(8, 8)
                }
            )
        }
        rule.mainClock.advanceTimeByFrame()
        rule.waitUntil(2_000) { frameShown(0) }
        assertEquals(frames, loaded) // 재생 전(TalkBack 켜짐 진입 등)에도 2~4번 그림을 미리 받는다
        rule.mainClock.advanceTimeBy(PICTOGRAM_FRAME_MS * 3)
        assertTrue(frameShown(0)) // 재생 전에는 돌지 않는다
    }

    @Test
    fun 애니메이션_제거_설정이면_재생_중에도_픽토그램이_움직이지_않는다() {
        rule.mainClock.autoAdvance = false
        rule.setThemed {
            PictogramPlayer(
                urls = frames,
                playing = true,
                description = "플랭크 동작 그림",
                height = 160.dp,
                animate = false,
                loader = { ImageBitmap(8, 8) }
            )
        }
        rule.mainClock.advanceTimeByFrame()
        rule.waitUntil(2_000) { frameShown(0) }
        rule.mainClock.advanceTimeBy(PICTOGRAM_FRAME_MS * 3)
        assertTrue(frameShown(0))
    }

    @Test
    fun 나가기_확인은_대화상자라_포커스가_갇히고_제목부터_읽는다() {
        var chosen = ""
        rule.setThemed {
            ExerciseDialog("운동을 끝낼까요?", "지금 나가면 운동이 멈춰요.", listOf("기록하고 끝내기" to { chosen = "end" }, "계속하기" to {}), onDismiss = {})
        }
        rule.onNode(isDialog()).assertExists()
        rule.onNode(pane("운동을 끝낼까요?")).assertExists()
        rule.onNodeWithText("기록하고 끝내기").performClick()
        assertEquals("end", chosen)
    }
}
