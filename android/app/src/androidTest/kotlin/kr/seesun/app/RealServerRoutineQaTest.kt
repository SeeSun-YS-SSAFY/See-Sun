// 실서버 시나리오는 한 사용자의 순서 있는 흐름이라 메서드를 쪼개면 단계 순서가 흐려진다.
@file:Suppress("LongMethod")

package kr.seesun.app

import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import org.junit.Assert.assertTrue
import org.junit.Test

/** 실서버 QA: 나만의 루틴(playlists API). 실행: `scripts/e2e/run_qa.py --only q01,q08`(새 번호로 가입한 뒤). */
@RealServer
class RealServerRoutineQaTest : RealServerTestBase() {

    // ── q08 나만의 루틴(실서버 playlists): 빈 이름 검사 → 만들기(세트 늘리기) → 시작·끝내기(playlist_id 기록) → 삭제 ──
    // v1.9 편집 흐름: 루틴 이름(R-10, 말 또는 연필 키보드) → 루틴 만들기 → [운동 넣기] → 카테고리 선택(R-12) → 운동 고르기(R-13)
    // → 운동 추가(세트·횟수) → 루틴 만들기 목록(「1. 플랭크」, 누르면 [운동 수정] R-11).

    @Test
    fun q08_나만의루틴_만들기_재생() {
        fault("pass")
        launch()
        waitPane("메인")
        click("운동")
        waitPane(EXERCISE_HOME)
        more("루틴 운동") // v1.9: [루틴 운동]은 [⋯ 더 보기] 안
        waitPane("루틴 운동")
        click("나만의 루틴")
        waitPane("나만의 루틴")
        row("CR-01", "새사용자_나만의루틴_없음_안내") { waitText("만든 루틴이 없어요") }
        click("새 루틴 만들기")
        waitPane("루틴 이름") // v1.9 R-10: 이름 단계가 먼저
        row("CR-02", "이름없이_저장_검사오류") {
            // v1.9: 이름 없이는 [루틴 저장]까지 갈 수 없고 이름 단계의 [다음]에서 막힌다.
            click("다음")
            waitText("루틴 이름을 말씀하시거나 연필을 눌러 써 주세요")
            assertTrue(hasPane("루틴 이름"))
        }
        row("CR-03", "운동고르기_세트늘리기_버튼만으로") {
            type("아침 근력") // 연필([키보드로 직접 입력])로 쓴다
            click("다음")
            waitPane("루틴 만들기")
            waitText("아침 근력")
            click("운동 넣기")
            waitPane("카테고리 선택") // v1.9 R-12
            click("전체 운동")
            waitPane("운동 고르기")
            click("플랭크")
            waitPane("운동 추가") // 전 「세트와 횟수」
            click("세트 늘리기")
            waitText("세트: ")
            click("이 운동 넣기")
            waitPane("루틴 만들기")
            waitText("1. 플랭크") // 전 [1번 운동 빼기: 플랭크] — 빼기는 [운동 수정] 안·TalkBack 작업으로
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
            rule.waitUntil(TIMEOUT) { textExists("운동 순서") || inPlayer() }
            if (textExists("운동 순서") && has(hasText("루틴 시작") and hasClickAction())) {
                shot("qa/CR-05_재생소개_루틴시작_두번째")
                click("루틴 시작")
            }
            waitPlaying() // v1.9: 「상태: 재생 중」 줄 대신 재생 버튼의 상태 설명
            Thread.sleep(PLAY_MS)
            more("루틴 끝내기") // v1.9: [⋯ 더 보기] 안
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
        waitPane(EXERCISE_HOME)
        more("루틴 운동")
        waitPane("루틴 운동")
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
