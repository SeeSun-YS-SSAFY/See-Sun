package kr.seesun.feature.exercise

import kr.seesun.core.voice.lifecycle.AppStop
import kr.seesun.core.voice.lifecycle.AppStopPolicy
import kr.seesun.feature.exercise.play.ResumeCommand
import kr.seesun.feature.exercise.play.ResumeVoiceMatcher
import kr.seesun.feature.exercise.session.ExerciseForegroundPolicy
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource

/** 소유자 결정 2026-10-15: 운동 중 화면 켜짐 유지, 다른 앱으로 나가면 멈춤, 화면만 끄면 계속. */
class ExerciseForegroundPolicyTest {

    @ParameterizedTest(name = "interactive={0}, locked={1}, sinceOff={2} -> {3}")
    @CsvSource(
        // 홈·다른 앱·최근 앱: 화면이 켜져 있고 잠금 화면이 없다 → 멈춤
        "true,  false, ,      LEFT_APP",
        // 전원 버튼: ON_STOP(약 0.7초 뒤) 때 이미 화면이 꺼져 있다 → 계속
        "false, false, ,      SCREEN_OFF",
        "false, true,  700,   SCREEN_OFF",
        // 화면 꺼짐 처리 순서가 늦은 기기: 아직 켜짐으로 보이지만 방금 화면 꺼짐 방송을 받았다 → 계속
        "true,  false, 700,   SCREEN_OFF",
        // v5(msg 943): 화면 꺼짐 방송 없이 잠금으로만 보이는 경우(홈만 눌렀는데 잠금으로 알리는 기기) → 멈춤
        "true,  true,  ,      LEFT_APP",
        // 앱이 보이는 동안 화면이 꺼졌고 잠금 화면이 떠 있다 → 계속
        "true,  true,  60000, SCREEN_OFF",
        // 시계가 거꾸로(방송 시각이 미래) → 받지 않은 것으로 본다
        "true,  false, -5,    LEFT_APP",
        // 화면 꺼짐이 오래전 일(그 뒤 다시 켜서 쓰다가 홈) → 멈춤
        "true,  false, 60000, LEFT_APP"
    )
    fun `ON_STOP 때 화면 상태로 나감과 화면 꺼짐을 가른다`(interactive: Boolean, locked: Boolean, sinceOff: Long?, expected: AppStop) {
        assertEquals(expected, AppStopPolicy.classifyStop(interactive, locked, sinceOff))
    }

    @Test
    fun `한 가지 운동은 세션 중에만 화면을 켜 두고 결과·안내 끝에서는 놓아 준다`() {
        assertTrue(ExerciseForegroundPolicy.keepScreenOnSingle(sessionActive = true, showingResult = false, guideFinished = false))
        assertFalse(ExerciseForegroundPolicy.keepScreenOnSingle(sessionActive = false, showingResult = false, guideFinished = false))
        assertFalse(ExerciseForegroundPolicy.keepScreenOnSingle(sessionActive = true, showingResult = true, guideFinished = false))
        assertFalse(ExerciseForegroundPolicy.keepScreenOnSingle(sessionActive = true, showingResult = false, guideFinished = true))
    }

    @Test
    fun `루틴은 운동·휴식 단계에서만 화면을 켜 둔다`() {
        assertTrue(ExerciseForegroundPolicy.keepScreenOnRoutine(sessionActive = true, exerciseOrRest = true))
        assertFalse(ExerciseForegroundPolicy.keepScreenOnRoutine(sessionActive = true, exerciseOrRest = false))
        assertFalse(ExerciseForegroundPolicy.keepScreenOnRoutine(sessionActive = false, exerciseOrRest = true))
    }

    @Test
    fun `이어서 시작 화면의 말 해석`() {
        assertEquals(ResumeCommand.RESUME, ResumeVoiceMatcher.match("이어서"))
        assertEquals(ResumeCommand.RESUME, ResumeVoiceMatcher.match("계속 할게요"))
        assertEquals(ResumeCommand.RESUME, ResumeVoiceMatcher.match("다시 시작"))
        assertEquals(ResumeCommand.END, ResumeVoiceMatcher.match("그만할래"))
        assertEquals(ResumeCommand.END, ResumeVoiceMatcher.match("운동 끝내기"))
        assertNull(ResumeVoiceMatcher.match("음"))
    }
}
