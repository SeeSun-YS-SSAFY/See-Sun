package kr.seesun.feature.exercise

import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasStateDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.accessibility.enableAccessibilityChecks
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.printToLog
import androidx.compose.ui.unit.dp
import java.time.LocalDate
import kr.seesun.core.data.model.ExerciseCategory
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
import kr.seesun.feature.exercise.play.ASSISTANT_SPOKEN
import kr.seesun.feature.exercise.play.ExercisePlayerScreen
import kr.seesun.feature.exercise.play.PLAY_TAG
import kr.seesun.feature.exercise.play.PlayerCommand
import kr.seesun.feature.exercise.play.PlayerHandlers
import kr.seesun.feature.exercise.play.PlayerMenu
import kr.seesun.feature.exercise.play.PlayerMode
import kr.seesun.feature.exercise.play.PlayerUi
import kr.seesun.feature.exercise.play.SCRIPT_TAG
import kr.seesun.feature.exercise.play.SLIDER_TAG
import kr.seesun.feature.exercise.play.SPEED_TAG
import kr.seesun.feature.exercise.play.STAGE_TAG
import kr.seesun.feature.exercise.player.GuideState
import kr.seesun.feature.exercise.player.GuideStatus
import kr.seesun.feature.exercise.routine.REST_PAUSE_TAG
import kr.seesun.feature.exercise.routine.REST_SKIP_TAG
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

    private fun handlers(
        onSeek: (Int, Int) -> Unit = { _, _ -> },
        onCommand: (PlayerCommand) -> Unit = {},
        onPlayPause: () -> Unit = {},
        onToggleScript: () -> Unit = {}
    ) = PlayerHandlers(
        onBack = {},
        onPlayPause = onPlayPause,
        onSpeed = {},
        onToggleScript = onToggleScript,
        onSeek = onSeek,
        onCommand = onCommand,
        onOpenMenu = {},
        onCloseMenu = {},
        onCloseHelp = {},
        onPick = onCommand,
        hasSettings = true
    )

    private val noActions = RoutineActions({}, {}, {}, {}, {}, {}, "루틴 운동으로", handlers())

    @Test
    fun 운동_첫_화면은_방식_버튼과_로그인_안내를_글자로_보이고_나머지는_더_보기에_있다() {
        var opened: HomeDestination? = null
        rule.setThemed {
            ExerciseHomeScreen(
                HomeUi(
                    signedIn = false,
                    pending = 2,
                    loading = false,
                    categories = listOf(ExerciseCategory("2", "유산소 운동")),
                    goal = "LOSE_WEIGHT"
                ),
                {},
                { opened = it }
            )
        }
        rule.onNode(pane("운동 방식")).assertExists()
        // 화면에는 한 줄로 줄이고, TalkBack에는 전체 문장을 읽힌다.
        rule.onNodeWithText("보내지 못한 운동 기록 2건", substring = true).assertExists()
        rule.onNode(hasContentDescription("아직 서버에 보내지 못한 운동 기록이 2건", substring = true)).assertExists()
        rule.onNodeWithText("유산소").assertExists()
        rule.onNodeWithText("추천 루틴").assertExists()
        rule.onNodeWithText("루틴 운동").assertDoesNotExist()
        rule.onNode(hasContentDescription("더 보기", substring = true)).performClick()
        rule.onNodeWithText("루틴 운동").performClick()
        assertEquals(HomeDestination.Routine, opened)
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
    fun 재생_화면은_속도_재생_대본_세_버튼과_진행_슬라이더만_보이고_나머지는_TalkBack_작업으로_된다() {
        val commands = mutableListOf<PlayerCommand>()
        rule.setThemed {
            ExercisePlayerScreen(
                ui = PlayerUi(loading = false, detail = detail, sessionActive = true),
                guide = GuideState(sections = sections, sectionIndex = 0, sentenceIndex = 1, status = GuideStatus.PLAYING),
                handlers = handlers(onCommand = { commands += it }),
                onFinish = {},
                onRetry = {},
                onAgain = {},
                onDone = {}
            )
        }
        rule.onNode(pane("맨몸 스쿼트")).assertExists()
        rule.onNode(hasContentDescription("구간 1/2, 준비 자세")).assertExists()
        // 글자 버튼 7개는 화면에서 빠졌다(기능은 [⋯]·TalkBack 작업·음성으로 남음).
        listOf("이전 구간", "다음 구간", "이 구간 다시 듣기", "운동 마치기", "즐겨찾기 넣기").forEach { rule.onNodeWithText(it).assertDoesNotExist() }
        val play = rule.onNodeWithTag(PLAY_TAG)
        play.assert(hasContentDescription("일시정지") and hasStateDescription("재생 중"))
        val actions = play.fetchSemanticsNode().config[SemanticsActions.CustomActions]
        assertEquals(PlayerMenu.playActions(PlayerMode.SINGLE, false, true, false).map { it.label }, actions.map { it.label })
        actions.first { it.label == "다음 구간" }.action()
        actions.first { it.label == "운동 끝내기" }.action()
        assertEquals(listOf(PlayerCommand.NEXT, PlayerCommand.END), commands)
        rule.onNodeWithTag(SPEED_TAG).assert(hasContentDescription("재생 속도, 1배", substring = true))
        // 그림이 없는 운동은 대본이 그림 자리를 대신하므로 [대본] 전환 버튼이 없다(그림이 있는 운동은 아래 대본 보기 시험).
        rule.onNodeWithTag(SCRIPT_TAG).assertDoesNotExist()
        rule.onNode(hasContentDescription("더 보기. 다시 듣기", substring = true)).assertExists()
        rule.onRoot(useUnmergedTree = true).printToLog("SEESUN_A11Y_PLAYER")
        rule.assertNoLigatureNames()
    }

    @Test
    fun 진행_슬라이더는_조절형이고_한_단계가_한_구간이며_누르면_그_자리로_간다() {
        val seeks = mutableListOf<Pair<Int, Int>>()
        val commands = mutableListOf<PlayerCommand>()
        val three = sections + GuideSection("exercise_guide_text", "따라 하기", "하나. 둘. 셋.", null)
        rule.setThemed {
            ExercisePlayerScreen(
                ui = PlayerUi(loading = false, detail = detail.copy(sections = three), sessionActive = true),
                guide = GuideState(sections = three, sectionIndex = 1, sentenceIndex = 0, status = GuideStatus.PAUSED),
                handlers = handlers(onSeek = { s, i -> seeks += s to i }, onCommand = { commands += it }),
                onFinish = {},
                onRetry = {},
                onAgain = {},
                onDone = {}
            )
        }
        val slider = rule.onNodeWithTag(SLIDER_TAG)
        slider.assert(hasContentDescription("운동 안내 진행") and hasStateDescription("구간 2/3 동작 시작, 30퍼센트"))
        val config = slider.fetchSemanticsNode().config
        val range = config[SemanticsProperties.ProgressBarRangeInfo]
        assertEquals(1f, range.current, 0.001f)
        assertEquals(0f..2f, range.range)
        assertEquals(1, range.steps) // 구간 3개 → 한 단계 = 한 구간
        // TalkBack 위아래 쓸기 = setProgress(다음 값) → 그 구간 처음부터
        slider.performSemanticsAction(SemanticsActions.SetProgress) { it(2f) }
        assertEquals(2 to 0, seeks.last())
        assertEquals(listOf("이전 구간", "다음 구간", "이 구간 다시 듣기"), config[SemanticsActions.CustomActions].map { it.label })
        // 막대 한 곳을 한 번 누르면 그 자리로(끌기 대안, WCAG 2.5.7)
        slider.performTouchInput { click(centerLeft) }
        assertEquals(0 to 0, seeks.last())
        rule.onRoot(useUnmergedTree = true).printToLog("SEESUN_A11Y_SLIDER")
    }

    /**
     * TalkBack이 실제로 받는 노드 정보(AccessibilityNodeInfo, UiAutomation으로 읽음): 재생 버튼의 사용자 지정 작업 이름과
     * 진행 슬라이더의 조절형 정보(SeekBar · 범위 · 앞뒤로 넘기기 · 값 정하기). 로그 태그 SEESUN_A11Y_NODE로 남긴다(v1.9 증빙).
     */
    @Test
    fun TalkBack이_받는_노드에_재생_버튼_작업과_조절형_슬라이더가_있다() {
        val three = sections + GuideSection("exercise_guide_text", "따라 하기", "하나. 둘. 셋.", null)
        rule.setThemed {
            ExercisePlayerScreen(
                ui = PlayerUi(loading = false, detail = detail.copy(sections = three), sessionActive = true),
                guide = GuideState(sections = three, sectionIndex = 1, sentenceIndex = 0, status = GuideStatus.PLAYING),
                handlers = handlers(),
                onFinish = {},
                onRetry = {},
                onAgain = {},
                onDone = {}
            )
        }
        rule.waitForIdle()
        // Compose가 TalkBack에 넘기는 것과 같은 경로(AccessibilityNodeProvider)로 노드 정보를 만든다.
        fun info(tag: String): AccessibilityNodeInfo {
            val id = rule.onNodeWithTag(tag).fetchSemanticsNode().id
            var result: AccessibilityNodeInfo? = null
            rule.runOnUiThread {
                val provider = generateSequence(listOf<View>(rule.activity.window.decorView)) { level ->
                    level.flatMap { v -> if (v is ViewGroup) (0 until v.childCount).map(v::getChildAt) else emptyList() }.ifEmpty { null }
                }.flatten().firstNotNullOf { it.accessibilityNodeProvider }
                result = provider.createAccessibilityNodeInfo(id)
            }
            return result!!
        }
        val play = info(PLAY_TAG)
        val labels = play.actionList.mapNotNull { it.label?.toString() }
        Log.i(NODE_TAG, "play: desc=${play.contentDescription} class=${play.className} actions=$labels")
        val expected = PlayerMenu.playActions(PlayerMode.SINGLE, false, true, false).map { it.label }
        assertTrue("재생 버튼 작업 $labels", labels.containsAll(expected))
        val slider = info(SLIDER_TAG)
        val sliderRange = slider.rangeInfo
        val sliderActions = slider.actionList.map { it.id }
        Log.i(
            NODE_TAG,
            "slider: desc=${slider.contentDescription} class=${slider.className} " +
                "range=${sliderRange?.min}..${sliderRange?.max} current=${sliderRange?.current} actions=${slider.actionList.map {
                    it.label ?: it.id
                }}"
        )
        assertEquals("android.widget.SeekBar", slider.className?.toString())
        assertEquals(1f, sliderRange!!.current, 0.001f)
        assertTrue(sliderActions.contains(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_FORWARD.id))
        assertTrue(sliderActions.contains(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_BACKWARD.id))
        assertTrue(sliderActions.contains(AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_PROGRESS.id))
        val speed = info(SPEED_TAG)
        Log.i(NODE_TAG, "speed: desc=${speed.contentDescription} actions=${speed.actionList.mapNotNull { it.label?.toString() }}")
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
        var script by remember { mutableStateOf(false) }
        CompositionLocalProvider(LocalPictogramLoader provides { ImageBitmap(8, 6) }) {
            ExercisePlayerScreen(
                ui = PlayerUi(loading = false, detail = mapped, sessionActive = true, showScript = script),
                guide = guide,
                handlers = handlers(onSeek = onSeek, onToggleScript = { script = !script }),
                onFinish = {},
                onRetry = {},
                onAgain = {},
                onDone = {}
            )
        }
    }

    @Test
    fun 그림은_대본_위치를_따라가고_최소_240dp를_지킨다() {
        var guide by mutableStateOf(GuideState(sections = sections, sectionIndex = 0, sentenceIndex = 0, status = GuideStatus.PLAYING))
        rule.setThemed {
            CompositionLocalProvider(LocalPictogramLoader provides { ImageBitmap(8, 6) }) {
                ExercisePlayerScreen(
                    ui = PlayerUi(loading = false, detail = mapped.copy(needsAssistant = true), sessionActive = true),
                    guide = guide,
                    handlers = handlers(),
                    onFinish = {},
                    onRetry = {},
                    onAgain = {},
                    onDone = {}
                )
            }
        }
        rule.waitUntil(2_000) { frameShown(0) }
        rule.shot("07b_운동재생_그림_대본위치")
        guide = guide.copy(sentenceIndex = 1)
        rule.waitUntil(2_000) { frameShown(1) }
        guide = guide.copy(sectionIndex = 1, sentenceIndex = 0)
        rule.waitUntil(2_000) { frameShown(2) }
        // 「보조자 필요」 노랑 표가 있어도 그림은 240dp 아래로 줄지 않는다(R-03).
        rule.onNode(hasContentDescription(ASSISTANT_SPOKEN)).assertExists()
        rule.onNodeWithTag(STAGE_TAG).assertHeightIsAtLeast(240.dp)
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
        rule.onNodeWithTag(SCRIPT_TAG).performClick()
        rule.onNodeWithTag(SCRIPT_TAG).assert(hasStateDescription("켜짐"))
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
        rule.onNodeWithTag(SCRIPT_TAG).performClick()
        rule.onNode(hasContentDescription("그림 크게 보기 — 팔을 앞으로")).assertExists()
    }

    @Test
    fun TalkBack_켜짐_진입_상태는_재생_대기이고_200퍼센트에서도_잘리지_않는다() {
        var played = 0
        rule.setThemed(fontScale = 2f) {
            ExercisePlayerScreen(
                ui = PlayerUi(loading = false, detail = detail.copy(needsAssistant = true)),
                guide = GuideState(sections = sections, status = GuideStatus.IDLE),
                handlers = handlers(onPlayPause = { played++ }),
                onFinish = {},
                onRetry = {},
                onAgain = {},
                onDone = {}
            )
        }
        rule.onNodeWithTag(PLAY_TAG).assert(hasContentDescription("재생") and hasStateDescription("준비됨")).performScrollTo().performClick()
        assertEquals(1, played)
        rule.onNode(hasContentDescription(ASSISTANT_SPOKEN, substring = true)).assertExists()
        rule.assertNoTextOverflow()
    }

    @Test
    fun 더_보기는_대화상자이고_숨긴_기능을_모두_버튼으로_가진다() {
        val picked = mutableListOf<PlayerCommand>()
        rule.setThemed {
            ExercisePlayerScreen(
                ui = PlayerUi(loading = false, detail = detail, sessionActive = true, menuOpen = true),
                guide = GuideState(sections = sections, status = GuideStatus.PAUSED),
                handlers = handlers(onCommand = { picked += it }),
                onFinish = {},
                onRetry = {},
                onAgain = {},
                onDone = {}
            )
        }
        rule.onNode(isDialog()).assertExists()
        rule.onNode(pane("더 보기")).assertExists()
        listOf("이 구간 다시 듣기", "이전 구간", "다음 구간", "즐겨찾기 넣기", "운동 끝내기", "설정", "도움말", "닫기").forEach {
            rule.onNodeWithText(it).performScrollTo().assertExists()
        }
        rule.onNodeWithText("다음 구간").performScrollTo().performClick()
        assertEquals(listOf(PlayerCommand.NEXT), picked)
    }

    @Test
    fun 루틴_휴식_화면은_남은_시간과_다음_운동과_둥근_버튼_둘을_글자로_보인다() {
        val routine = Routine(
            null,
            "근력 운동 루틴",
            listOf(RoutineItem(null, "a", "맨몸 스쿼트", 1, null, 10), RoutineItem(null, "b", "플랭크", 1, null, 10))
        )
        var skipped = 0
        var toggled = 0
        rule.setThemed(fontScale = 2f) {
            RoutinePlayerScreen(
                "근력 운동 루틴",
                RoutineUi(phase = RoutinePhase.REST, routine = routine, stepIndex = 1, restRemaining = 7, sessionActive = true),
                GuideState(),
                noActions.copy(onSkipRest = { skipped++ }, onToggleRest = { toggled++ })
            )
        }
        rule.onNode(hasContentDescription("남은 휴식 7초")).assertExists()
        rule.onNodeWithText("다음 운동: 플랭크").assertExists()
        rule.onNodeWithTag(REST_SKIP_TAG).performScrollTo().performClick()
        rule.onNodeWithTag(REST_PAUSE_TAG).performScrollTo().performClick()
        assertEquals(1 to 1, skipped to toggled)
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
        const val NODE_TAG = "SEESUN_A11Y_NODE"
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
