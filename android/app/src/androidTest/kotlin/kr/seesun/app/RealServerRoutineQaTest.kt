// 실서버 시나리오는 한 사용자의 순서 있는 흐름이라 메서드를 쪼개면 단계 순서가 흐려진다.
@file:Suppress("LongMethod")

package kr.seesun.app

import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.performTextReplacement
import org.junit.Test

/** 실서버 QA: 나만의 루틴(playlists API). 실행: `scripts/e2e/run_qa.py --only q01,q08`(새 번호로 가입한 뒤). */
@RealServer
class RealServerRoutineQaTest : RealServerTestBase() {

    // ── q08 나만의 루틴(실서버 playlists): 빈 이름 검사 → 만들기(세트 늘리기) → 시작·끝내기(playlist_id 기록) → 삭제 ──

    @Test
    fun q08_나만의루틴_만들기_재생() {
        fault("pass")
        launch()
        waitPane("메인")
        click("운동")
        waitPane("운동")
        click("루틴 운동")
        waitPane("루틴 운동")
        click("나만의 루틴")
        waitPane("나만의 루틴")
        row("CR-01", "새사용자_나만의루틴_없음_안내") { waitText("만든 루틴이 없어요") }
        click("새 루틴 만들기")
        waitPane("루틴 만들기")
        row("CR-02", "이름없이_저장_검사오류") {
            click("루틴 저장")
            waitText("루틴 이름을 입력해 주세요")
        }
        row("CR-03", "운동고르기_세트늘리기_버튼만으로") {
            rule.onNode(hasSetTextAction()).performTextReplacement("아침 근력")
            click("운동 넣기")
            waitPane("운동 고르기")
            click("플랭크")
            waitPane("세트와 횟수")
            click("세트 늘리기")
            waitText("세트: ")
            click("이 운동 넣기")
            waitPane("루틴 만들기")
            waitText("1번 운동 빼기: 플랭크")
        }
        row("CR-04", "루틴저장_서버_목록표시") {
            click("루틴 저장")
            waitPane("나만의 루틴")
            waitText("아침 근력")
        }
        row("CR-05", "나만의루틴_시작_끝내기_기록전송") {
            click("아침 근력")
            waitText("루틴 시작")
            click("루틴 시작") // 상세 화면
            // 첫 운동이면 안전 안내(SafetyGate) → 재생 소개 화면 → [루틴 시작]. 나만의 루틴은 상세와 소개에서
            // [루틴 시작]을 두 번 누르게 된다(관찰 O-03).
            passSafetyIfAsked()
            rule.waitUntil(TIMEOUT) { textExists("운동 순서") || textExists("상태: ") }
            if (textExists("운동 순서") && has(hasText("루틴 시작") and hasClickAction())) {
                shot("qa/CR-05_재생소개_루틴시작_두번째")
                click("루틴 시작")
            }
            waitText("상태: 재생 중", timeout = LONG_TIMEOUT)
            Thread.sleep(PLAY_MS)
            click("루틴 끝내기")
            waitText("루틴을 끝낼까요?")
            click("기록하고 끝내기")
            waitSynced(SYNC_TIMEOUT)
            fact("custom_routine_recorded", "true")
        }
    }

    /** 호스트가 서버에서 기록의 playlist_id를 확인한 뒤 돈다(지우면 기록의 playlist_id는 비워지고 기록은 남는다). */
    @Test
    fun q09_나만의루틴_삭제() {
        fault("pass")
        launch()
        waitPane("메인")
        click("운동")
        waitPane("운동")
        click("루틴 운동")
        click("나만의 루틴")
        waitPane("나만의 루틴")
        row("CR-06", "루틴삭제_확인후_목록에서_사라짐") {
            click("아침 근력")
            click("루틴 삭제")
            waitText("루틴을 지울까요?")
            shot("qa/CR-06_삭제확인")
            click("지우기")
            waitPane("나만의 루틴")
            waitText("만든 루틴이 없어요")
        }
    }

    private companion object {
        const val PLAY_MS = 11_000L
        const val SYNC_TIMEOUT = 90_000L
    }
}
