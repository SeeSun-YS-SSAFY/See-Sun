package kr.seesun.feature.exercise

import kr.seesun.core.data.model.ExerciseCategory
import kr.seesun.core.data.model.ExerciseSummary
import kr.seesun.core.data.model.GuideSection
import kr.seesun.core.data.model.RoutineItem
import kr.seesun.core.designsystem.component.ButtonKind
import kr.seesun.feature.exercise.home.ExerciseOrder
import kr.seesun.feature.exercise.home.HomeDestination
import kr.seesun.feature.exercise.home.HomeUi
import kr.seesun.feature.exercise.home.destinationOf
import kr.seesun.feature.exercise.home.homeLabel
import kr.seesun.feature.exercise.home.homeMoreItems
import kr.seesun.feature.exercise.home.homeTiles
import kr.seesun.feature.exercise.play.ExitChoice
import kr.seesun.feature.exercise.play.ExitVoiceMatcher
import kr.seesun.feature.exercise.play.MenuFlow
import kr.seesun.feature.exercise.play.PlayerCommand
import kr.seesun.feature.exercise.play.PlayerListening
import kr.seesun.feature.exercise.play.PlayerMenu
import kr.seesun.feature.exercise.play.PlayerMode
import kr.seesun.feature.exercise.play.PlayerVoiceMatcher
import kr.seesun.feature.exercise.play.RoutinePosition
import kr.seesun.feature.exercise.play.STAGE_MIN
import kr.seesun.feature.exercise.play.playCaption
import kr.seesun.feature.exercise.play.playLabel
import kr.seesun.feature.exercise.play.positionAt
import kr.seesun.feature.exercise.play.progressText
import kr.seesun.feature.exercise.play.sliderSpec
import kr.seesun.feature.exercise.play.sliderTargetSection
import kr.seesun.feature.exercise.play.speedFor
import kr.seesun.feature.exercise.player.GuideState
import kr.seesun.feature.exercise.player.GuideStatus
import kr.seesun.feature.exercise.routine.RoutineEdits
import kr.seesun.feature.exercise.routine.RoutineNames
import kr.seesun.feature.exercise.routine.RoutineOrigin
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource

/** v1.9: 재생 화면 간소화(설계 v1 §6), 운동 방식(231:708), 루틴 편집(R-10~R-14). */
class ExerciseV19Test {

    // ── 재생 화면: [⋯ 더 보기] 목록 ─────────────────────────────────────

    @Test
    fun `더 보기 - 한 가지 운동은 말로 하기, 다시 듣기, 이전·다음 구간, 즐겨찾기, 끝내기(빨강), 설정, 도움말`() {
        val items = PlayerMenu.items(PlayerMode.SINGLE, favorite = false, settings = true, voice = true)
        assertEquals(
            listOf("말로 하기", "이 구간 다시 듣기", "이전 구간", "다음 구간", "즐겨찾기 넣기", "운동 끝내기", "설정", "도움말"),
            items.map { it.label }
        )
        assertEquals(ButtonKind.SECONDARY, items.first { it.label == "운동 끝내기" }.kind)
        assertEquals(ButtonKind.OUTLINE, items.first { it.label == "설정" }.kind)
        assertEquals("즐겨찾기에 없음", items.first { it.key == "FAVORITE" }.state)
        assertEquals("즐겨찾기 빼기", PlayerMenu.items(PlayerMode.SINGLE, true, true, true).first { it.key == "FAVORITE" }.label)
    }

    @Test
    fun `더 보기 - 루틴은 이 운동 건너뛰기와 루틴 끝내기, 휴식은 끝내기·설정·도움말만`() {
        val routine = PlayerMenu.items(PlayerMode.ROUTINE, favorite = true, settings = true, voice = true).map { it.label }
        assertEquals(
            listOf("말로 하기", "이 구간 다시 듣기", "이전 구간", "다음 구간", "이 운동 건너뛰기", "즐겨찾기 빼기", "루틴 끝내기", "설정", "도움말"),
            routine
        )
        assertEquals(
            listOf("말로 하기", "루틴 끝내기", "설정", "도움말"),
            PlayerMenu.items(PlayerMode.REST, favorite = null, settings = true, voice = true).map { it.label }
        )
        // 음성 모듈·설정 열기가 없으면 그 항목은 빠진다(버튼 없는 기능을 만들지 않음).
        val bare = PlayerMenu.items(PlayerMode.SINGLE, favorite = null, settings = false, voice = false).map { it.label }
        assertEquals(listOf("이 구간 다시 듣기", "이전 구간", "다음 구간", "운동 끝내기", "도움말"), bare)
    }

    @Test
    fun `더 보기 항목은 모두 명령으로 이어진다`() {
        PlayerMode.entries.forEach { mode ->
            PlayerMenu.items(mode, favorite = false, settings = true, voice = true).forEach { item ->
                val command = PlayerMenu.commandOf(item)
                assertTrue(command != null, "${item.label} → 명령 없음")
            }
        }
        val labels = PlayerMenu.moreLabel(PlayerMode.ROUTINE)
        listOf("더 보기", "다시 듣기", "이전·다음 구간", "건너뛰기", "끝내기", "설정").forEach { assertTrue(labels.contains(it), it) }
    }

    // ── TalkBack 사용자 지정 작업 ───────────────────────────────────────

    @Test
    fun `재생 버튼의 TalkBack 작업 = 더 보기와 같은 기능(말로 하기 포함)`() {
        PlayerMode.entries.forEach { mode ->
            assertEquals(
                PlayerMenu.items(mode, favorite = false, settings = true, voice = true),
                PlayerMenu.playActions(mode, favorite = false, settings = true, voice = true)
            )
        }
        val routine = PlayerMenu.playActions(PlayerMode.ROUTINE, false, true, true).map { it.label }
        assertTrue(routine.containsAll(listOf("이 구간 다시 듣기", "이전 구간", "다음 구간", "이 운동 건너뛰기", "루틴 끝내기")))
    }

    @Test
    fun `슬라이더 작업은 이전·다음 구간과 다시 듣기, 속도 버튼 작업은 느리게·보통·빠르게`() {
        assertEquals(listOf("이전 구간", "다음 구간", "이 구간 다시 듣기"), PlayerMenu.sliderActions().map { it.label })
        assertEquals(
            listOf(PlayerCommand.PREVIOUS, PlayerCommand.NEXT, PlayerCommand.REPLAY),
            PlayerMenu.sliderActions().map { PlayerMenu.commandOf(it) }
        )
        assertEquals(listOf("느리게", "보통", "빠르게"), PlayerMenu.speedActions().map { it.label })
    }

    // ── [⋯]를 열고 닫을 때 다시 틀기 ────────────────────────────────────

    @Test
    fun `더 보기를 닫으면 멈추기 전 상태로, 구간 이동은 스스로 재생, 끝내기·설정은 멈춘 채로`() {
        assertTrue(MenuFlow.resumeAfterClose(wasRunning = true))
        assertFalse(MenuFlow.resumeAfterClose(wasRunning = false))
        assertTrue(MenuFlow.resumeAfterPick(PlayerCommand.FAVORITE, wasRunning = true))
        assertFalse(MenuFlow.resumeAfterPick(PlayerCommand.FAVORITE, wasRunning = false))
        listOf(PlayerCommand.REPLAY, PlayerCommand.PREVIOUS, PlayerCommand.NEXT, PlayerCommand.SKIP_EXERCISE).forEach {
            assertFalse(MenuFlow.resumeAfterPick(it, wasRunning = true), "$it 은 그 동작이 재생한다")
        }
        listOf(PlayerCommand.END, PlayerCommand.SETTINGS, PlayerCommand.HELP, PlayerCommand.SPEAK).forEach {
            assertFalse(MenuFlow.resumeAfterPick(it, wasRunning = true), "$it 은 멈춘 채로")
        }
    }

    @Test
    fun `멈춘 동안에만 듣는다 - 재생 중·더 보기 열림·들어오자마자는 듣지 않음`() {
        assertTrue(PlayerListening.shouldListen(overlay = false, running = false, pauseSeq = 1))
        assertFalse(PlayerListening.shouldListen(overlay = false, running = true, pauseSeq = 3))
        assertFalse(PlayerListening.shouldListen(overlay = true, running = false, pauseSeq = 3))
        assertFalse(PlayerListening.shouldListen(overlay = false, running = false, pauseSeq = 0))
    }

    // ── 음성 명령 ─────────────────────────────────────────────────────

    @ParameterizedTest
    @CsvSource(
        "그만, ROUTINE, PAUSE",
        "그만할래, ROUTINE, END",
        "잠깐 멈춰, SINGLE, PAUSE",
        "계속, ROUTINE, PLAY",
        "다시 시작, ROUTINE, PLAY",
        "다시, ROUTINE, REPLAY",
        "다시 들려줘, SINGLE, REPLAY",
        "다음, ROUTINE, NEXT",
        "다음 구간, SINGLE, NEXT",
        "다음 운동, ROUTINE, SKIP_EXERCISE",
        "이거 건너뛰어, ROUTINE, SKIP_EXERCISE",
        "되돌려, ROUTINE, UNDO_SKIP",
        "이전 구간, ROUTINE, PREVIOUS",
        "뒤로, SINGLE, END",
        "끝내기, SINGLE, END",
        "즐겨찾기 넣어, SINGLE, FAVORITE",
        "빠르게, SINGLE, SPEED_FAST",
        "천천히, ROUTINE, SPEED_SLOW",
        "보통 속도, ROUTINE, SPEED_NORMAL",
        "대본 보여줘, SINGLE, SCRIPT_ON",
        "그림 보여줘, ROUTINE, SCRIPT_OFF",
        "설정, ROUTINE, SETTINGS",
        "뭐라고 말할 수 있어, SINGLE, HELP",
        "바로 시작, REST, SKIP_REST",
        "휴식 건너뛰기, REST, SKIP_REST",
        "계속, REST, PLAY",
        "그만할래, REST, END"
    )
    fun `재생 화면 음성 명령 - 충돌 말 정리(§6·4)`(text: String, mode: PlayerMode, expected: PlayerCommand) {
        assertEquals(expected, PlayerVoiceMatcher.match(text, mode))
    }

    @Test
    fun `한 가지 운동에는 건너뛰기가 없고 휴식에는 구간 이동이 없다, 모르는 말은 null`() {
        assertEquals(PlayerCommand.NEXT, PlayerVoiceMatcher.match("다음 운동", PlayerMode.SINGLE))
        assertNull(PlayerVoiceMatcher.match("건너뛰기", PlayerMode.SINGLE))
        assertNull(PlayerVoiceMatcher.match("이전 구간", PlayerMode.REST))
        assertNull(PlayerVoiceMatcher.match("오늘 날씨 어때", PlayerMode.ROUTINE))
        assertNull(PlayerVoiceMatcher.match("", PlayerMode.ROUTINE))
        assertEquals(PlayerCommand.END, PlayerVoiceMatcher.matchAny(listOf("날씨", "그만할래"), PlayerMode.ROUTINE))
    }

    @Test
    fun `끝내기 확인 창을 말로 답하기`() {
        assertEquals(ExitChoice.FINISH, ExitVoiceMatcher.match("기록하고 끝내기"))
        assertEquals(ExitChoice.CONTINUE, ExitVoiceMatcher.match("계속할게"))
        assertEquals(ExitChoice.QUIT, ExitVoiceMatcher.match("기록하지 않고 나가기"))
        assertEquals(ExitChoice.CONTINUE, ExitVoiceMatcher.match("아니요"))
        assertNull(ExitVoiceMatcher.match("음"))
    }

    @Test
    fun `속도 명령 - 느리게 0·75배, 보통 1배, 빠르게는 한 단계씩(최대 1·5배)`() {
        assertEquals(0.75f, speedFor(PlayerCommand.SPEED_SLOW, 1f))
        assertEquals(1f, speedFor(PlayerCommand.SPEED_NORMAL, 1.5f))
        assertEquals(1.25f, speedFor(PlayerCommand.SPEED_FAST, 1f))
        assertEquals(1.25f, speedFor(PlayerCommand.SPEED_FAST, 0.75f))
        assertEquals(1.5f, speedFor(PlayerCommand.SPEED_FAST, 1.25f))
        assertEquals(1.5f, speedFor(PlayerCommand.SPEED_FAST, 1.5f))
        assertNull(speedFor(PlayerCommand.NEXT, 1f))
    }

    // ── 진행 줄·슬라이더 ──────────────────────────────────────────────

    private fun section(key: String, title: String, text: String) = GuideSection(key, title, text, null)

    private val sections = listOf(
        section("a", "운동 설명", "하나. 둘."),
        section("b", "준비 자세", "하나. 둘. 셋."),
        section("c", "내려가기", "하나. 둘. 셋. 넷. 다섯.")
    )

    @Test
    fun `진행 한 줄 - 한 가지 운동은 구간만, 루틴은 운동·세트·구간(낭독은 풀어서)`() {
        val state = GuideState(sections = sections, sectionIndex = 1)
        assertEquals("구간 2/3", progressText(state).shown)
        assertEquals("구간 2/3, 준비 자세", progressText(state).spoken)
        val routine = progressText(state, RoutinePosition(itemIndex = 0, itemCount = 3, set = 1, totalSets = 1))
        assertEquals("1/3 운동 · 1세트 · 구간 2/3", routine.shown)
        assertEquals("운동 3개 중 1번째, 1세트째, 구간 2/3, 준비 자세", routine.spoken)
        assertEquals("2/3 운동 · 2/3세트 · 구간 2/3", progressText(state, RoutinePosition(1, 3, 2, 3)).shown)
    }

    @Test
    fun `슬라이더 - 한 단계 = 한 구간(steps = 구간 수 − 2), 상태 설명은 구간 이름과 퍼센트`() {
        val spec = sliderSpec(GuideState(sections = sections, sectionIndex = 1, sentenceIndex = 1, status = GuideStatus.PLAYING))
        assertEquals("운동 안내 진행", spec.label)
        assertEquals(1, spec.sectionIndex)
        assertEquals(2f, spec.rangeEnd)
        assertEquals(1, spec.steps)
        assertEquals(0.3f, spec.fraction, 1e-6f)
        assertEquals("구간 2/3 준비 자세, 30퍼센트", spec.state)
        // 구간이 둘이면 단계 없음(끝↔끝), 하나뿐이어도 범위가 비지 않는다.
        assertEquals(0, sliderSpec(GuideState(sections = sections.take(2))).steps)
        assertEquals(1f, sliderSpec(GuideState(sections = sections.take(1))).rangeEnd)
    }

    @Test
    fun `TalkBack이 슬라이더 값을 바꾸면 가장 가까운 구간으로(범위 밖은 끝으로)`() {
        assertEquals(2, sliderTargetSection(2f, 3))
        assertEquals(1, sliderTargetSection(1.4f, 3))
        assertEquals(2, sliderTargetSection(1.6f, 3))
        assertEquals(0, sliderTargetSection(-1f, 3))
        assertEquals(2, sliderTargetSection(9f, 3))
        assertEquals(0, sliderTargetSection(0f, 0))
    }

    @Test
    fun `막대 한 곳 누르기 - 그 자리의 구간과 문장(진행 바와 같은 문장 눈금)`() {
        val state = GuideState(sections = sections) // 문장 2 + 3 + 5 = 10
        assertEquals(0 to 0, positionAt(state, 0f))
        assertEquals(0 to 1, positionAt(state, 0.15f))
        assertEquals(1 to 0, positionAt(state, 0.2f))
        assertEquals(1 to 2, positionAt(state, 0.45f))
        assertEquals(2 to 4, positionAt(state, 1f))
        assertEquals(0 to 0, positionAt(GuideState(), 0.5f))
    }

    @Test
    fun `재생 버튼 - 보이는 글자는 짧게, TalkBack 이름은 지금 할 수 있는 동작`() {
        val playing = GuideState(sections = sections, status = GuideStatus.PLAYING)
        assertEquals("일시정지", playCaption(playing))
        assertEquals("일시정지", playLabel(playing))
        val finished = playing.copy(status = GuideStatus.FINISHED)
        assertEquals("처음부터", playCaption(finished))
        assertEquals("처음부터 다시 듣기", playLabel(finished))
        assertEquals("이어서 듣기", playCaption(playing.copy(status = GuideStatus.PAUSED)))
        assertEquals("재생", playLabel(GuideState(sections = sections)))
    }

    @Test
    fun `그림 최소 높이 240dp(R-03)`() {
        assertEquals(240f, STAGE_MIN.value)
    }

    // ── 운동 방식(Figma 231:708) ──────────────────────────────────────

    private val server = listOf(
        ExerciseCategory("1", "근력 운동"),
        ExerciseCategory("2", "유산소 운동"),
        ExerciseCategory("3", "유연성 운동"),
        ExerciseCategory("4", "균형 운동")
    )

    @Test
    fun `운동 방식 - Figma 이름 4개(목표 순서) + 추천 루틴, 나머지는 더 보기로`() {
        val gain = ExerciseOrder.sort(server, ExerciseOrder.localOrder("GAIN_MUSCLE"))
        assertEquals(listOf("근력", "유산소", "유연성 운동", "균형", "추천 루틴"), homeTiles(gain, gain.first()).map { it.text })
        val lose = ExerciseOrder.sort(server, ExerciseOrder.localOrder("LOSE_WEIGHT"))
        assertEquals(listOf("유산소", "근력", "유연성 운동", "균형", "추천 루틴"), homeTiles(lose, lose.first()).map { it.text })
        assertEquals("유연성 운동", homeLabel("유연성 운동"))
        assertEquals("근력", homeLabel("근력 운동"))
        val more = homeMoreItems(settings = true)
        assertEquals(listOf("루틴 운동", "이번 주 기록", "안전 안내", "전체 운동·즐겨찾기", "설정"), more.map { it.label })
        assertEquals(4, homeMoreItems(settings = false).size)
        val ui = HomeUi(loading = false, categories = gain, goal = "GAIN_MUSCLE")
        assertEquals(
            listOf(HomeDestination.Routine, HomeDestination.Weekly, HomeDestination.Safety, HomeDestination.Single),
            more.take(4).map { destinationOf(it.key, ui) }
        )
    }

    // ── 루틴 편집(R-10~R-14) ─────────────────────────────────────────

    private fun item(id: String?, ex: String, sets: Int = 2, reps: Int? = 10) = RoutineItem(id, ex, "운동$ex", sets, reps, 10)

    @Test
    fun `순서 바꾸기·세트 횟수 바꾸기·운동 바꾸기`() {
        val items = listOf(item("i1", "a"), item("i2", "b"), item("i3", "c"))
        assertEquals(listOf("i2", "i1", "i3"), RoutineEdits.move(items, 1, 0).map { it.itemId })
        assertEquals(items, RoutineEdits.move(items, 0, -1))
        assertEquals(3 to 15, RoutineEdits.updateCounts(items, 2, 3, 15)[2].let { it.setCount to it.repsCount })
        val replaced = RoutineEdits.replaceExercise(items, 1, ExerciseSummary("z", "런지"))
        assertNull(replaced[1].itemId) // 서버 항목은 지우고 새로 넣는다
        assertEquals("런지", replaced[1].exerciseName)
        assertEquals(2, replaced[1].setCount)
        // 같은 운동을 다시 고르면 그대로
        assertEquals("i2", RoutineEdits.replaceExercise(items, 1, ExerciseSummary("b", "운동b"))[1].itemId)
    }

    @Test
    fun `저장 계획 - 지우기, 넣기(순서대로), 세트·횟수 PATCH`() {
        val original = listOf(item("i1", "a"), item("i2", "b"), item("i3", "c"))
        val edited = listOf(item("i3", "c", sets = 4), item(null, "d"), item("i1", "a"))
        val plan = RoutineEdits.plan(original, edited)
        assertEquals(listOf("i2"), plan.deletes)
        assertEquals(listOf("d"), plan.adds.map { it.exerciseId })
        assertEquals(listOf(RoutineEdits.CountPatch("i3", 4, 10)), plan.patches)
        assertEquals(listOf("i3", "n1", "i1"), RoutineEdits.desiredOrder(edited, listOf("n1")))
    }

    @Test
    fun `순서 맞추기 - 같으면 없음, 다르면 임시 번호로 옮긴 뒤 1부터(순서 번호 겹침 방지)`() {
        assertTrue(RoutineEdits.reorder(listOf("a", "b"), listOf("a", "b")).isEmpty())
        val steps = RoutineEdits.reorder(listOf("a", "b", "c"), listOf("c", "a", "b"))
        val base = RoutineEdits.TEMP_BASE
        assertEquals(listOf("c" to base + 1, "a" to base + 2, "b" to base + 3, "c" to 1, "a" to 2, "b" to 3), steps)
        // 임시 번호는 실제 번호(1~n)와 겹치지 않는다.
        assertTrue(steps.take(3).all { it.second > base })
    }

    @Test
    fun `루틴 이름(말) - 끝말 떼고 20자까지`() {
        assertEquals("아침 운동", RoutineNames.parse("아침 운동이요"))
        assertEquals("아침 운동", RoutineNames.parse(" 아침 운동. "))
        assertEquals("하체", RoutineNames.parse("하체"))
        assertEquals(20, RoutineNames.parse("가".repeat(30))!!.length)
        assertNull(RoutineNames.parse("   "))
    }

    @Test
    fun `루틴을 마친 뒤 버튼 이름 = 들어온 곳(R-14)`() {
        assertEquals("운동 방식으로", RoutineOrigin.HOME.doneLabel)
        assertEquals("루틴 운동으로", RoutineOrigin.MENU.doneLabel)
        assertEquals("나만의 루틴으로", RoutineOrigin.CUSTOM.doneLabel)
        assertEquals(RoutineOrigin.CUSTOM, RoutineOrigin.of("CUSTOM"))
        assertEquals(RoutineOrigin.MENU, RoutineOrigin.of(null))
        assertEquals(RoutineOrigin.MENU, RoutineOrigin.of("???"))
        // 경로 기본값은 루틴 운동 메뉴
        assertEquals("MENU", RoutinePlayRoute("루틴").origin)
    }
}
