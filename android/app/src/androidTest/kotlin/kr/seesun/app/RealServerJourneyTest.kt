// 실서버 시나리오는 한 사용자의 순서 있는 흐름이라 메서드를 쪼개면 단계 순서가 흐려진다.
@file:Suppress("LongMethod")

package kr.seesun.app

import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 실제 백엔드에 붙은 통합 E2E(가짜 저장소 없음): 첫 실행 → 온보딩 → 메인 → 운동 → 마이페이지 → 로그아웃·재로그인 → 탈퇴.
 *
 * 평소 게이트(connectedDebugAndroidTest)에서는 건너뛴다. 로컬 백엔드(scripts/demo/run_local_backend.ps1)를 켜고
 * `adb reverse tcp:8000 tcp:<서버 포트>` 한 뒤, 단계(메서드)마다 따로 실행한다(단계 사이에 서버 DB를 확인):
 *
 *     adb shell am instrument -w -e realServer true -e phone 01055501234 \
 *       -e class kr.seesun.app.RealServerJourneyTest#a_온보딩 kr.seesun.app.debug.test/androidx.test.runner.AndroidJUnitRunner
 *
 * 단계마다 화면 사진을 `/data/local/tmp/seesun-e2e/NN_단계.png`에 남기고(screencap), 서버와 맞춰 볼 값은
 * logcat `SeeSunE2E: FACT 키=값`으로 남긴다. 호출·확인 순서는 `scripts/e2e/run_integrated_e2e.py`.
 */
@RealServer
class RealServerJourneyTest : RealServerTestBase() {

    // ── 1. 첫 실행 → 온보딩 ─────────────────────────────────────────

    @Test
    fun a_온보딩() {
        launch()
        waitPane("시선 시작하기")
        shot("01_첫실행_시작화면")
        click("처음 시작하기")

        waitPane("연령 확인")
        type("19560315")
        shot("02_연령확인_생년월일")
        click("다음")

        consents()

        waitPane("전화번호 인증")
        type(phone)
        waitText("Firebase로 전송돼요")
        shot("09_전화번호")
        click("인증번호 받기")
        rule.waitUntil(TIMEOUT) { hasPane("인증번호 입력") || hasPane("이름") }
        if (hasPane("인증번호 입력")) {
            shot("10_인증번호")
            if (!hasPane("이름")) {
                runCatching { type("123456") }
                runCatching { click("인증하기") }
            }
        }

        waitPane("이름")
        type("김시선")
        shot("11_이름")
        click("다음")
        waitPane("생년월일")
        shot("12_생년월일_재사용")
        click("다음")
        waitPane("성별")
        click("여성")
        click("다음")
        waitPane("키")
        type("158")
        click("다음")
        waitPane("몸무게")
        type("56")
        click("다음")
        waitPane("입력 확인")
        shot("13_프로필_확인")
        click("저장")

        waitPane("질환")
        click("당뇨")
        click("고혈압")
        shot("14_건강_질환")
        click("다음")
        waitPane("알레르기")
        waitText("1번째, 전체 19개")
        click("다음")
        waitText("2번째, 전체 19개")
        toggle("우유")
        click("다음")
        click("다음")
        waitText("4번째, 전체 19개")
        toggle("땅콩")
        waitText("선택한 알레르기 2개")
        shot("15_건강_알레르기_2개")
        click("선택 마치기")
        waitPane("기타 알레르기")
        click("없음")
        waitPane("목표")
        click("건강 유지") // 체중 감량은 이 프로필에서 하한 1,200kcal에 걸려 키를 바꿔도 같은 값(QA 따로 확인)
        shot("16_건강_목표")
        click("다음")
        waitPane("건강 정보 확인")
        shot("17_건강_확인")
        click("저장")

        waitPane("가입 완료")
        waitText("하루 목표 열량은")
        val kcal = Regex("""하루 목표 열량은 ([\d,]+)킬로칼로리""").find(allText())!!.groupValues[1].replace(",", "")
        fact("onboarding_target_kcal", kcal)
        shot("18_가입완료_목표열량")
        click("시작하기")

        waitPane("메인")
        waitText("하루 목표 열량 ${"%,d".format(kcal.toInt())}킬로칼로리")
        waitText("김시선님, 안녕하세요.")
        shot("19_메인_오늘요약")
    }

    /**
     * 약관 동의 한 화면(2026-10-13): 의료기기 아님 한 줄 + [전체 동의] + 행 4개.
     * 민감정보는 음성 경로를 먼저 시도하고, [자세히]로 개인정보 고지 4요소를 확인한 뒤 전체 동의 → [다음].
     */
    private fun consents() {
        waitPane("약관 동의")
        waitText("의료기기가 아니에요")
        shot("03_약관동의_한화면")
        voiceAttempt()
        if (!hasPane("약관 동의")) return // 음성 동의가 실제로 인식돼 이미 넘어감
        click("개인정보 수집·이용 자세히 보기")
        waitText("동의를 거부할 권리가 있어요")
        shot("04_약관동의_자세히_개인정보")
        click("목록으로 돌아가기")
        waitPane("약관 동의")
        click("전체 동의.")
        waitText("4개 모두 체크(민감정보 포함)")
        shot("05_약관동의_전체동의")
        click("다음")
        // 실기기는 다음 화면(전화번호)에 들어서자마자 '이 폰 번호' 창이 앞에 떠 앱 화면을 찾을 수 없다(2026-10-13 Galaxy A55).
        // 그때도 동의 화면은 이미 지나간 것이므로 통과로 본다. 창 닫기는 waitPane(전화번호 인증)이 맡는다.
        rule.waitUntil(TIMEOUT) { runCatching { !hasPane("약관 동의") }.getOrDefault(true) }
    }

    /** 에뮬레이터(마이크 입력 없음)에서 [말로 고르기]: 인식 실패 시 버튼 안내로 돌아오는지 남긴다. */
    private fun voiceAttempt() {
        if (!has(hasText("말로 고르기") and hasClickAction())) return
        click("말로 고르기")
        val settled = runCatching {
            rule.waitUntil(VOICE_TIMEOUT) { textExists("다시") || textExists("버튼") || textExists("못 알아") || textExists("동의하시겠어요?") }
        }.isSuccess
        fact("voice_consent_settled", settled.toString())
        shot("08_동의_음성시도_결과")
        if (textExists("동의 확인") && has(hasText("동의 확인") and hasClickAction())) {
            // 실제로 인식된 경우(호스트 마이크 등): 재확인 질문에 답한다.
            click("동의 확인")
        }
    }

    // ── 2. 메인 → 운동 ─────────────────────────────────────────────

    @Test
    fun b_운동() {
        launch()
        waitPane("메인")
        click("운동")
        waitPane(EXERCISE_HOME)
        shot("20_운동_첫화면")

        more("전체 운동·즐겨찾기") // v1.9: [한 가지 운동]은 [⋯ 더 보기]의 「전체 운동·즐겨찾기」로
        waitPane("한 가지 운동")
        click("전체 운동")
        waitText("전체 29개")
        assertTrue("서버 목록(기기 내장 대본 아님)", !textExists("기기에 저장된 운동 정보"))
        shot("21_운동목록_서버_29개")
        click("맨몸 스쿼트")

        waitText("건강 확인 시작")
        shot("22_첫운동_안전안내")
        click("건강 확인 시작")
        waitText("건강 확인 1/7")
        repeat(SAFETY_QUESTIONS) { click("아니요") }
        waitText("확인하고 운동하기")
        shot("23_안전안내_건강확인_결과")
        click("확인하고 운동하기")

        waitPlaying()
        Thread.sleep(PLAY_MS) // 주간 집계는 10초 이상 한 운동만 센다
        shot("24_한가지운동_재생중")
        finishSingle() // v1.9: [운동 마치기] 대신 안내 끝 화면의 [기록하고 끝내기]
        waitText("운동을 기록했어요")
        shot("25_한가지운동_기록저장")
        waitSynced()
        fact("single_synced", "true")
        click("목록으로")

        back()
        back()
        waitPane(EXERCISE_HOME)
        more("루틴 운동")
        waitPane("루틴 운동")
        click("근력 운동 루틴")
        waitText("루틴 시작")
        shot("26_루틴_시작전_순서")
        click("루틴 시작")
        waitPlaying()
        shot("27_루틴_첫운동_재생중")
        // 첫 운동 안내를 [⋯]의 [다음 구간]으로 끝까지 넘기면 휴식으로 간다.
        repeat(MAX_SEGMENTS) { if (!textExists("남은 휴식")) runCatching { more("다음 구간") } }
        waitText("남은 휴식")
        waitText("다음 운동: ")
        shot("28_루틴_휴식_카운트다운")
        waitText("운동 8개 중 2번째", timeout = LONG_TIMEOUT) // 휴식이 끝나면 저절로 다음 운동
        shot("29_루틴_두번째운동_자동시작")
        more("루틴 끝내기")
        waitText("루틴을 끝낼까요?")
        shot("30_루틴_끝내기_확인")
        click("기록하고 끝내기")
        waitSynced()
        fact("routine_synced", "true")

        // v1.9 R-14: 다 마친 뒤 버튼은 들어온 곳 이름(루틴 운동 메뉴 → 「루틴 운동으로」). 중간에 끝내면 바로 앞 화면으로.
        rule.waitUntil(TIMEOUT) { hasPane("루틴 운동") || hasPane(EXERCISE_HOME) || textExists("루틴 운동으로") }
        if (has(hasText("루틴 운동으로") and hasClickAction())) click("루틴 운동으로")
        while (!hasPane(EXERCISE_HOME)) back()
        more("이번 주 기록")
        waitText("이번 주 운동", timeout = LONG_TIMEOUT)
        val weekly = Regex("""이번 주 운동 (\d+)회""").find(allText())?.groupValues?.get(1) ?: "?"
        fact("weekly_count", weekly)
        shot("31_이번주_운동기록")
    }

    // ── 3. 마이페이지 ───────────────────────────────────────────────

    @Test
    fun c_마이페이지_키수정() {
        launch()
        waitPane("메인")
        click("마이페이지")
        waitPane("마이페이지")
        waitText("하루 목표 열량")
        shot("32_마이페이지")
        click("내 정보 수정")
        waitPane("내 정보 수정")
        click("키: 158센티미터")
        waitPane("키 수정")
        type("170")
        shot("33_키수정_입력")
        click("저장")
        waitText("정보를 저장했어요")
        shot("34_키수정_저장_재계산")
        back()
        waitPane("마이페이지")
        waitText("키 170센티미터")
        // 화면에는 "하루 목표 1,445kcal(추정값)"으로 줄여 보인다(낭독은 "하루 목표 열량 …킬로칼로리").
        val kcal = Regex("""하루 목표(?: 열량)? ([\d,]+)(?:킬로칼로리|kcal)""").find(allText())?.groupValues?.get(1)?.replace(",", "") ?: "?"
        fact("mypage_target_kcal_after_height", kcal)
        shot("35_마이페이지_새목표열량")
    }

    @Test
    fun d_동의철회_재동의_데이터초기화() {
        launch()
        waitPane("메인")
        click("설정")
        waitPane("설정")
        shot("36_설정")
        click("계정") // v1.9: 설정 첫 목록은 여섯 갈래, 동의 관리는 [계정] 안
        waitPane("계정")
        click("개인정보·동의 관리")
        waitPane("개인정보·동의 관리")
        waitText("건강정보(민감정보) 처리 동의 철회")
        shot("37_동의관리")
        click("건강정보(민감정보) 처리 동의 철회")
        waitPane("동의 철회 확인")
        shot("38_민감정보_철회_확인")
        click("철회")
        waitText("지웠어요")
        shot("39_민감정보_철회_완료")
        fact("sensitive_withdrawn", "true")

        click("건강정보(민감정보) 처리 동의하기")
        waitPane("동의 확인")
        click("동의")
        waitText("건강정보(민감정보) 처리 동의 철회")
        shot("40_민감정보_다시동의")
        back()
        waitPane("계정")
        back()
        waitPane("설정")
        back()
        waitPane("메인")

        click("마이페이지")
        waitPane("마이페이지")
        click("건강 정보 수정")
        waitPane("건강 정보 수정")
        click("질환 고르기")
        waitPane("질환")
        click("당뇨")
        click("선택 마치기")
        waitPane("건강 정보 수정")
        click("저장")
        waitText("건강 정보를 저장했어요")
        shot("41_건강정보_다시저장")
        fact("health_resaved", "true")
        back()
        waitPane("마이페이지")
        back()
        waitPane("메인")

        click("설정")
        waitPane("설정")
        click("계정")
        waitPane("계정")
        click("데이터 초기화")
        waitPane("데이터 초기화")
        click("건강 정보만 지우기")
        waitText("건강 정보만 지워요")
        shot("42_데이터초기화_건강만_설명")
        click("계속하기")
        waitPane("지우기 확인")
        click("지우기")
        waitPane("지우기 완료")
        shot("43_데이터초기화_건강만_완료")
        fact("health_reset", "true")
        click("확인")
    }

    @Test
    fun e_로그아웃_재로그인() {
        launch()
        waitPane("메인")
        click("설정")
        waitPane("설정")
        click("계정")
        waitPane("계정")
        click("로그아웃")
        waitPane("로그아웃 확인")
        shot("44_로그아웃_확인")
        click("로그아웃") // 확인 화면(대화상자 아님)의 [로그아웃]
        waitPane("시선 시작하기")
        val left = unsent()
        assertEquals("로그아웃 뒤 기기에 남은 보내지 못한 기록", 0, left)
        shot("45_로그아웃_후_시작화면")

        click("이미 가입했어요")
        waitPane("전화번호 인증")
        type(phone)
        click("인증번호 받기")
        rule.waitUntil(TIMEOUT) { hasPane("인증번호 입력") || hasPane("가입 완료") || hasPane("메인") }
        if (hasPane("인증번호 입력") && !hasPane("가입 완료")) {
            runCatching { type("123456") }
            runCatching { click("인증하기") }
        }
        rule.waitUntil(TIMEOUT) { hasPane("가입 완료") || hasPane("메인") }
        shot("46_재로그인_기존회원_요약")
        if (hasPane("가입 완료")) click("시작하기")
        waitPane("메인")
        waitText("김시선님, 안녕하세요.")
        shot("47_재로그인_메인")
    }

    @Test
    fun f_회원탈퇴() {
        launch()
        waitPane("메인")
        click("설정")
        waitPane("설정")
        click("계정")
        waitPane("계정")
        click("회원 탈퇴")
        waitPane("회원 탈퇴")
        shot("48_탈퇴_설명")
        click("탈퇴 계속하기")
        waitPane("탈퇴 확인")
        shot("49_탈퇴_마지막확인")
        click("탈퇴하기")
        waitPane("탈퇴 완료")
        shot("50_탈퇴_완료")
        click("확인")
        waitPane("시선 시작하기")
        val left = unsent()
        assertEquals(0, left)
        assertTrue("탈퇴 뒤 로그인 풀림", !entry.tokens().signedIn.value)
        shot("51_탈퇴_후_시작화면")
    }

    private companion object {
        const val VOICE_TIMEOUT = 15_000L
        const val PLAY_MS = 12_000L
    }
}
