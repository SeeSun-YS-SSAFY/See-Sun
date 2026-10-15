// 실서버 시나리오는 한 사용자의 순서 있는 흐름이라 메서드를 쪼개면 단계 순서가 흐려진다.
@file:Suppress("LongMethod")

package kr.seesun.app

import android.util.Log
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isOn
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.junit4.AndroidComposeTestRule
import androidx.compose.ui.test.junit4.accessibility.enableAccessibilityChecks
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.printToString
import androidx.compose.ui.test.tryPerformAccessibilityChecks
import kr.seesun.core.auth.FakeAuthProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 실서버 QA 매트릭스(docs/qa/QA_MATRIX_app.md) 실행: 입력 방식 × 네트워크(정상·오프라인·5xx·시간 초과) × 수명 주기
 * (회전·백그라운드) × 데이터 상태(신규·기존·동의 철회·문서 변경·탈퇴·토큰 만료). 행마다 logcat `ROW <id> PASS|FAIL`.
 *
 * 장애는 장애 주입 프록시로 건다: `fault_proxy.py --listen 8020 --upstream 8010`, `adb reverse tcp:8000 tcp:8020`.
 * 실행 순서·호스트 쪽 준비(동의 문서 버전 올리기, TalkBack 켜기, 프로세스 종료 행)는 scripts/e2e/run_qa.py.
 * 메서드는 이름 순서대로 한 사용자(-e phone)를 이어 쓴다.
 */
@RealServer
class RealServerQaTest : RealServerTestBase() {

    // ── q01 신규 가입: 입력 검증·음성 대체·동의 거부·인증 실패·서버 오류·시간 초과·회전·백그라운드 ──

    @Test
    fun q01_온보딩_입력검증_네트워크() {
        fault("pass")
        launch()
        waitPane("시선 시작하기")
        row("ONB-01", "시작화면_TalkBack꺼짐_안내") { assertTrue(textExists("TalkBack이 꺼져 있어요")) }
        click("처음 시작하기")
        waitPane("연령 확인")

        row("AGE-01", "만14세미만_차단_값삭제") {
            type("20150101")
            click("다음")
            waitPane("가입할 수 없어요")
            waitText("저장하지 않고 지웠어요")
        }
        if (hasPane("가입할 수 없어요")) click("생년월일 다시 입력")
        waitPane("연령 확인")
        row("AGE-02", "없는_날짜_키보드_검사오류") {
            type("19991340")
            click("다음")
            Thread.sleep(SHORT_MS)
            assertTrue("연령 확인에 머묾", hasPane("연령 확인"))
            assertTrue("검사 오류 안내", textExists("맞지 않아요") || textExists("다시"))
        }
        row("AGE-03", "음성입력_인식기없음_키보드권유") {
            rule.onNodeWithContentDescription("음성 입력 시작", substring = true).performClick()
            waitText("음성 인식을 쓸 수 없어요")
        }
        type("19700101")
        click("다음")
        // 약관 동의 한 화면(2026-10-13): 행마다 체크, 필수·고지가 빠지면 [다음]이 사용 중지됨 + 사유.
        waitPane("약관 동의")
        row("CON-01", "필수동의_빠지면_다음_비활성_머묾") {
            click("필수, See:Sun 이용약관")
            waitText("필수와 고지를 체크해 주세요")
            assertTrue(hasPane("약관 동의"))
        }
        row("CON-02", "동의화면_가로회전_체크유지") {
            rotate(landscape = true)
            assertTrue(hasPane("약관 동의"))
            shot("qa/CON-02_가로")
            rotate(landscape = false)
            assertTrue(hasPane("약관 동의"))
            assertTrue(has(hasContentDescription("필수, See:Sun 이용약관", substring = true) and isOn()))
        }
        row("VOI-01", "동의화면_말로고르기_인식기없음_버튼안내_BUG09") {
            click("말로 고르기")
            waitText("아래 버튼으로 골라 주세요")
        }
        row("CON-03", "선택동의_민감정보_빼고_계속") {
            click("필수, 개인정보 수집·이용 안내")
            click("고지, 개인정보 국외이전 고지")
            click("다음")
            waitPane("전화번호 인증")
        }

        waitPane("전화번호 인증")
        row("PHN-01", "잘못된_전화번호_진행안됨") {
            type("0101234")
            if (has(hasText("인증번호 받기") and hasClickAction())) runCatching { click("인증번호 받기") }
            Thread.sleep(SHORT_MS)
            assertFalse("인증번호 화면으로 가지 않음", hasPane("인증번호 입력"))
        }
        // SMS 자동 입력(가짜 인증의 자동 인증)은 통합 E2E가 다룬다. 여기서는 끄고 직접 입력·오류 경로를 본다.
        (entry.authProvider() as FakeAuthProvider).autoRetrieve = false
        type(phone)
        click("인증번호 받기")
        waitPane("인증번호 입력")
        row("OTP-01", "틀린_인증번호_안내") {
            type("000000")
            click("인증하기")
            waitText("인증번호가 맞지 않아요")
        }
        row("NET-01", "로그인_서버500_다시시도") {
            fault("500", "path=/auth/firebase/")
            type("123456")
            click("인증하기")
            waitText("잠시 후 다시 시도해 주세요")
            assertTrue(has(hasText("다시 시도") and hasClickAction()))
        }
        row("NET-02", "동의제출_시간초과_실패한요청만_다시") {
            // 로그인은 통과시키고 동의 제출만 25초 붙잡는다(앱 제한 시간 20초).
            fault("hang", "path=/me/consents/&seconds=25")
            click("다시 시도")
            waitText("인터넷 연결을 확인해 주세요", timeout = HANG_TIMEOUT)
            shot("qa/NET-02_시간초과_안내")
            fault("pass")
            click("다시 시도")
            waitPane("이름")
        }
        fault("pass")
        waitPane("이름")
        row("ONB-02", "로그인후_뒤로가기_잠김") {
            scenario!!.onActivity { it.onBackPressedDispatcher.onBackPressed() }
            waitText("이전 단계로 돌아갈 수 없어요")
            assertTrue(hasPane("이름"))
        }
        row("LIF-01", "이름입력후_회전_값유지") {
            type("QA")
            rotate(landscape = true)
            rotate(landscape = false)
            assertTrue(hasPane("이름"))
            assertTrue(textExists("QA"))
        }
        click("다음")
        waitPane("생년월일")
        click("다음")
        waitPane("성별")
        click("남성")
        click("다음")
        waitPane("키")
        row("PRF-01", "키_범위밖_검사오류") {
            type("90")
            click("다음")
            waitText("입력하신 값이 맞지 않아요")
            assertTrue(hasPane("키"))
        }
        type("175")
        click("다음")
        waitPane("몸무게")
        row("LIF-02", "몸무게입력후_백그라운드_복귀_값유지") {
            type("70")
            backgroundAndReturn()
            assertTrue(hasPane("몸무게"))
            assertTrue(textExists("70"))
        }
        click("다음")
        waitPane("입력 확인")
        row("NET-03", "프로필저장_서버500_다시시도") {
            fault("500", "path=/me/&method=PATCH")
            click("저장")
            waitText("잠시 후 다시 시도해 주세요")
            shot("qa/NET-03_오류")
            fault("pass")
            click(if (has(hasText("다시 시도") and hasClickAction())) "다시 시도" else "저장")
            rule.waitUntil(TIMEOUT) { hasPane("목표") || hasPane("질환") }
        }
        fault("pass")
        row("HLT-01", "민감정보_미동의_질환알레르기_건너뜀_목표만") {
            rule.waitUntil(TIMEOUT) { hasPane("목표") || hasPane("질환") }
            assertTrue("질환 단계 없이 목표", hasPane("목표"))
        }
        click("근육 증진")
        click("다음")
        waitPane("건강 정보 확인")
        click("저장")
        waitPane("가입 완료")
        row("DONE-01", "가입완료_거부항목_요약") {
            waitText("동의하지 않은 항목: 건강정보(민감정보) 처리 동의")
        }
        click("시작하기")
        waitPane("메인")
        row("MAIN-01", "가입직후_메인") { waitText("QA님, 안녕하세요.") }
    }

    // ── q02 메인·운동: 서버 오류·오프라인 기록·재연결 전송·회전·백그라운드·뒤로 가기·두 번 누르기 ──

    @Test
    fun q02_메인_운동_네트워크_수명주기() {
        row("MAIN-02", "메인_서버500_다시불러오기") {
            fault("500", "path=/me/&method=GET")
            launch()
            waitPane("메인")
            waitText("정보를 불러오지 못했어요")
            shot("qa/MAIN-02_오류")
            fault("pass")
            click("다시 불러오기")
            waitText("QA님, 안녕하세요.")
        }
        fault("pass")
        if (scenario == null) launch()
        waitPane("메인")

        row("EX-01", "오프라인_기기내장_운동정보") {
            fault("reset")
            deviceNetwork(on = false)
            click("운동")
            waitPane("운동")
            click("한 가지 운동")
            waitPane("한 가지 운동")
            waitText("기기에 저장된")
        }
        row("EX-02", "오프라인_운동_TTS대본_기록_대기1건") {
            click("근력 운동")
            waitText("맨몸 스쿼트")
            click("맨몸 스쿼트")
            waitText("건강 확인 시작")
            click("건강 확인 시작")
            repeat(SAFETY_QUESTIONS) { click("아니요") }
            click("확인하고 운동하기")
            waitText("기기 음성으로 읽어요", timeout = LONG_TIMEOUT)
            shot("qa/EX-02_오프라인_재생")
            Thread.sleep(PLAY_MS)
            click("운동 마치기")
            waitText("운동을 기록했어요")
            assertEquals(1, unsent())
        }
        row("EX-03", "운동첫화면_보내지못한_기록1건") {
            click("목록으로")
            back()
            back()
            waitPane("운동")
            waitText("보내지 못한 운동 기록이 1건")
        }
        row("EX-04", "메인요약_보내지못한_기록1건_BUG03") {
            back()
            waitPane("메인")
            waitText("보내지 못한 기록 1건")
        }
        row("EX-05", "로그아웃확인_보내지못한_기록경고_BUG03") {
            click("설정")
            waitPane("설정")
            click("로그아웃")
            waitPane("로그아웃 확인")
            waitText("보내지 못한 기록 1건도 함께 지워져요")
            click("취소")
            waitPane("설정")
            back()
            waitPane("메인")
        }
        row("EX-06", "재연결_자동전송") {
            fault("pass")
            deviceNetwork(on = true)
            waitSynced(SYNC_TIMEOUT)
            waitPane("메인")
            rule.waitUntil(TIMEOUT) { !textExists("보내지 못한 기록") }
        }
        fault("pass")
        deviceNetwork(on = true)

        click("운동")
        waitPane("운동")
        click("한 가지 운동")
        click("전체 운동")
        waitText("전체 29개")
        click("플랭크")
        row("EX-07", "재생중_가로회전_재생유지") {
            waitText("상태: 재생 중", timeout = LONG_TIMEOUT)
            rotate(landscape = true)
            assertTrue(textExists("상태: 재생 중"))
            shot("qa/EX-07_가로")
            rotate(landscape = false)
            assertTrue(textExists("상태: 재생 중"))
        }
        row("EX-08", "재생중_백그라운드_복귀_재생유지") {
            backgroundAndReturn()
            assertTrue(textExists("상태: 재생 중") || textExists("구간"))
        }
        row("EX-09", "재생중_뒤로가기_확인대화상자_계속") {
            scenario!!.onActivity { it.onBackPressedDispatcher.onBackPressed() }
            waitText("운동을 끝낼까요?")
            shot("qa/EX-09_대화상자")
            click("계속하기")
            assertFalse(textExists("운동을 끝낼까요?"))
        }
        row("EX-10", "운동마치기_두번눌러도_기록1건") {
            Thread.sleep(PLAY_MS)
            val node = rule.onAllNodes(hasText("운동 마치기") and hasClickAction())[0]
            node.performClick()
            runCatching { node.performClick() }
            waitText("운동을 기록했어요")
            assertTrue("새 기록은 1건 이하", unsent() <= 1)
            waitSynced(SYNC_TIMEOUT)
            fact("qa_plank_finished", "true")
        }
        click("목록으로")
        back()
        back()
        waitPane("운동")
        row("EX-11", "주간기록_서버500_다시시도") {
            fault("500", "path=/exercise/weekly/")
            click("이번 주 운동 기록")
            waitText("잠시 후 다시 시도해 주세요")
            shot("qa/EX-11_오류")
            fault("pass")
            click("다시 시도")
            waitText("이번 주 운동")
        }
        fault("pass")
        back()
        waitPane("운동")
        row("EX-12", "기록전송_서버500_나중에_재전송") {
            fault("500", "path=/exercise-sessions/bulk/")
            click("한 가지 운동")
            click("전체 운동")
            waitText("전체 29개")
            click("맨몸 스쿼트")
            waitText("상태: 재생 중", timeout = LONG_TIMEOUT)
            Thread.sleep(PLAY_MS)
            click("운동 마치기")
            waitText("운동을 기록했어요")
            Thread.sleep(SYNC_GRACE_MS)
            assertEquals("500 동안 큐에 남음", 1, unsent())
            fault("pass")
            waitSynced(RETRY_TIMEOUT)
        }
        fault("pass")
    }

    // ── q03 마이페이지·설정: 서버 거절·서버 오류·시간 초과·오프라인·토큰 만료·기존 회원 재로그인 ──

    @Test
    fun q03_마이페이지_설정_토큰() {
        fault("pass")
        launch()
        waitPane("메인")
        click("마이페이지")
        waitPane("마이페이지")
        click("내 정보 수정")
        waitPane("내 정보 수정")
        row("MY-01", "생년월일_만14세미만_서버거절_문구") {
            click("생년월일: 1970년 1월 1일")
            type("20150101")
            click("저장")
            waitText("만 14세 이상만 이용할 수 있어요")
            click("취소")
        }
        waitPane("내 정보 수정")
        row("MY-02", "키수정_서버500_오류후_다시저장") {
            click("키: 175센티미터")
            type("180")
            fault("500", "path=/me/&method=PATCH")
            click("저장")
            waitText("잠시 후 다시 시도해 주세요")
            shot("qa/MY-02_오류")
            fault("pass")
            click("저장")
            waitText("정보를 저장했어요")
            waitText("키: 180센티미터")
        }
        fault("pass")
        back()
        waitPane("마이페이지")
        row("MY-03", "민감정보_미동의_건강정보수정_일반모드") {
            click("건강 정보 수정")
            waitPane("건강 정보 수정")
            waitText("건강정보 처리에 동의하지 않아") // 불러온 뒤 판정(불러오는 중에 검사하면 흔들림)
            assertFalse("질환 고르기 없음", has(hasText("당뇨") and hasClickAction()))
            back()
        }
        waitPane("마이페이지")
        back()
        waitPane("메인")
        click("설정")
        waitPane("설정")
        click("개인정보·동의 관리")
        waitPane("개인정보·동의 관리")
        row("CON-04", "민감정보_동의_시간초과_후_다시") {
            click("건강정보(민감정보) 처리 동의하기")
            waitPane("동의 확인")
            fault("hang", "path=/me/consents/&seconds=25")
            click("동의")
            waitText("인터넷 연결을 확인해 주세요", timeout = HANG_TIMEOUT)
            shot("qa/CON-04_시간초과")
            fault("pass")
            if (hasPane("동의 확인")) click("동의") else click("건강정보(민감정보) 처리 동의하기").also { click("동의") }
            waitText("건강정보(민감정보) 처리 동의 철회")
        }
        fault("pass")
        back()
        waitPane("설정")
        row("RST-01", "식사초기화_오프라인_다시시도") {
            click("데이터 초기화")
            waitPane("데이터 초기화")
            click("식사 기록 모두 지우기")
            click("계속하기")
            waitPane("지우기 확인")
            fault("reset")
            click("지우기")
            waitText("인터넷 연결을 확인해 주세요")
            assertTrue(has(hasText("다시 시도해서 지우기") and hasClickAction()))
            shot("qa/RST-01_오프라인")
            fault("pass")
            click("다시 시도해서 지우기")
            waitPane("지우기 완료")
            click("확인")
        }
        fault("pass")
        scenario?.close()

        row("TOK-01", "access토큰_만료_자동갱신") {
            val refresh = entry.tokens().refreshToken!!
            entry.tokens().save("expired-or-garbage-access", refresh)
            launch()
            waitPane("메인")
            waitText("QA님, 안녕하세요.")
            assertTrue(entry.tokens().accessToken != "expired-or-garbage-access")
        }
        scenario?.close()
        row("TOK-02", "refresh토큰_거절_로그아웃_시작화면") {
            entry.tokens().save("garbage-access", "garbage-refresh")
            launch()
            waitPane("시선 시작하기", timeout = LONG_TIMEOUT)
            assertFalse(entry.tokens().signedIn.value)
        }
        row("EXI-01", "기존회원_재로그인") {
            if (!hasPane("시선 시작하기")) {
                scenario?.close()
                launch()
            }
            waitPane("시선 시작하기")
            click("이미 가입했어요")
            waitPane("전화번호 인증")
            type(phone)
            click("인증번호 받기")
            enterCodeIfNeeded("가입 완료", "메인")
            if (hasPane("가입 완료")) click("시작하기")
            waitPane("메인")
            waitText("QA님, 안녕하세요.")
        }
    }

    // ── q04 동의 문서 변경(호스트가 TOS 새 버전을 올린 뒤): 다시 동의 안내·기록 403 보류·다시 동의 후 전송 ──

    @Test
    fun q04_동의문서변경_다시동의() {
        fault("pass")
        launch()
        waitPane("메인")
        row("RC-01", "문서변경_메인_다시동의_안내") { waitText("동의 문서가 바뀌었어요") }
        row("RC-02", "다시동의전_운동기록_403_큐보류") {
            click("운동")
            waitPane("운동")
            click("한 가지 운동")
            click("전체 운동")
            waitText("전체 29개")
            click("맨몸 스쿼트")
            waitText("상태: 재생 중", timeout = LONG_TIMEOUT)
            Thread.sleep(PLAY_MS)
            click("운동 마치기")
            waitText("운동을 기록했어요")
            Thread.sleep(SYNC_GRACE_MS)
            assertEquals("403(동의 필요)이면 큐에 남김", 1, unsent())
            click("목록으로")
            back()
            back()
            waitPane("운동")
            back()
            waitPane("메인")
        }
        row("RC-03", "동의다시하기_이용약관_재동의") {
            click("동의 다시 하기")
            waitPane("개인정보·동의 관리")
            click("See:Sun 이용약관 동의 다시 하기")
            waitPane("동의 확인")
            click("동의")
            rule.waitUntil(TIMEOUT) { !textExists("다시 동의가 필요해요") }
            back()
            waitPane("메인")
            rule.waitUntil(TIMEOUT) { !textExists("동의 문서가 바뀌었어요") }
        }
        row("RC-04", "다시동의후_보류기록_자동전송_BUG10") {
            // 다시 동의하면 막혀 있던 기록 전송을 다시 예약한다(운동 화면을 다시 열지 않아도).
            waitSynced(RETRY_TIMEOUT)
            rule.waitUntil(TIMEOUT) { !textExists("보내지 못한 기록") }
        }
    }

    // ── q05 TalkBack 켜짐(호스트가 켠 뒤): 자동 재생·자동 마이크 없음(A9), 실제 화면 ATF 검사, 의미 트리 기록 ──

    @Test
    fun q05_TalkBack_의미검사() {
        assertTrue("호스트가 TalkBack을 켜야 함", shell("settings get secure enabled_accessibility_services").contains("talkback"))
        (rule as AndroidComposeTestRule<*, *>).enableAccessibilityChecks()
        fault("pass")
        launch()
        waitPane("메인")
        val screens = mutableListOf<String>()
        fun atf(id: String, name: String) = row(id, name) {
            rule.onAllNodes(isRoot())[0].tryPerformAccessibilityChecks()
            Log.i(TAG, "TREE $name\n" + rule.onAllNodes(isRoot())[0].printToString(maxDepth = Int.MAX_VALUE))
            screens += name
        }
        atf("TB-01", "ATF_메인")
        click("운동")
        waitPane("운동")
        atf("TB-02", "ATF_운동첫화면")
        click("한 가지 운동")
        click("전체 운동")
        waitText("전체 29개")
        atf("TB-03", "ATF_운동목록")
        click("맨몸 스쿼트")
        row("TB-04", "TalkBack켜짐_재생화면_자동재생없음_A9") {
            waitText("상태: ", timeout = LONG_TIMEOUT)
            Thread.sleep(SHORT_MS)
            assertFalse("자동 재생하지 않음", textExists("상태: 재생 중"))
            assertTrue(textExists("재생을 누르면"))
        }
        atf("TB-05", "ATF_재생화면")
        back()
        if (textExists("운동을 끝낼까요?")) click("기록하지 않고 나가기")
        back()
        back()
        waitPane("운동")
        back()
        waitPane("메인")
        click("마이페이지")
        waitPane("마이페이지")
        atf("TB-06", "ATF_마이페이지")
        back()
        click("설정")
        waitPane("설정")
        atf("TB-07", "ATF_설정")
        click("개인정보·동의 관리")
        waitPane("개인정보·동의 관리")
        atf("TB-08", "ATF_동의관리")
        fact("talkback_atf_screens", screens.size.toString())
    }

    // ── q06 탈퇴: 오프라인이면 아무것도 지우지 않음 → 다시 시도 → 완료 안내 → 탈퇴 계정 번호로 재가입 경로 ──

    @Test
    fun q06_탈퇴_오프라인_재가입() {
        fault("pass")
        launch()
        waitPane("메인")
        click("설정")
        waitPane("설정")
        click("회원 탈퇴")
        waitPane("회원 탈퇴")
        click("탈퇴 계속하기")
        waitPane("탈퇴 확인")
        row("DEL-01", "탈퇴_오프라인_기기데이터_유지_다시시도") {
            fault("reset")
            click("탈퇴하기")
            waitText("인터넷 연결을 확인해 주세요")
            assertTrue(has(hasText("다시 시도해서 탈퇴하기") and hasClickAction()))
            assertTrue("오프라인이면 로그인 유지", entry.tokens().signedIn.value)
        }
        row("DEL-02", "다시시도_탈퇴완료_안내_BUG07") {
            fault("pass")
            click("다시 시도해서 탈퇴하기")
            waitPane("탈퇴 완료")
            waitText("이 기기의 기록과 로그인 정보도 지웠어요")
            click("확인")
            waitPane("시선 시작하기")
            assertEquals(0, unsent())
        }
        fault("pass")
        row("DEL-03", "탈퇴한_번호로_이미가입했어요_새가입경로") {
            click("이미 가입했어요")
            waitPane("전화번호 인증")
            type(phone)
            click("인증번호 받기")
            enterCodeIfNeeded("연령 확인") // 서버에 이 번호의 회원이 없음 → 생년월일부터(BIRTHDATE_REQUIRED)
        }
    }

    // ── q07 TalkBack 켜짐 + 로그인 전 시작 화면 ──

    @Test
    fun q07_TalkBack_시작화면() {
        launch()
        waitPane("시선 시작하기")
        row("TB-09", "TalkBack켜짐_시작화면_안내") { waitText("TalkBack이 켜져 있어요") }
        row("TB-10", "TalkBack켜짐_전체듣기_전문펼침") {
            click("처음 시작하기")
            waitPane("연령 확인")
            type("19700101")
            click("다음")
            waitPane("약관 동의")
            click("이용약관 자세히 보기")
            // TalkBack이 켜져 있으면 앱 음성 대신 전문을 글자로 펼친다(A9).
            waitPane("See:Sun 이용약관")
            waitText("의료기기가 아니며")
        }
    }

    private companion object {
        const val SHORT_MS = 1_500L
        const val PLAY_MS = 11_000L
        const val SYNC_GRACE_MS = 6_000L
        const val SYNC_TIMEOUT = 90_000L
        const val RETRY_TIMEOUT = 150_000L
        const val HANG_TIMEOUT = 45_000L
    }
}
