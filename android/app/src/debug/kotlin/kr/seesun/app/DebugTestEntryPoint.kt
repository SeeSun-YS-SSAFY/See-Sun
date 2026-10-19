package kr.seesun.app

import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kr.seesun.core.auth.AuthProvider
import kr.seesun.core.auth.TokenStore
import kr.seesun.core.data.repository.SessionFlags
import kr.seesun.core.data.repository.SessionRepository
import kr.seesun.core.data.repository.UnsentRecords

/**
 * debug 전용: 기기 시험(app/src/androidTest)이 실제 앱 그래프의 싱글턴으로 세션을 심고 지우는 통로.
 *
 * 저장소 파일(SharedPreferences)에 직접 쓰면, 앱 시작 때 돈 예약 작업(식사·운동 동기화)이 먼저 만든
 * 토큰 저장소·가입 표시 싱글턴이 옛 값을 들고 있어 시작 화면이 어긋난다. release에는 들어가지 않는다.
 */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface DebugTestEntryPoint {
    fun tokens(): TokenStore

    fun sessionFlags(): SessionFlags

    /** 기기에 남은 보내지 못한 기록(식사 + 운동). 로그아웃·탈퇴 뒤 0인지, 운동 기록이 서버로 갔는지 확인한다. */
    fun unsentRecords(): UnsentRecords

    /** 로그인 상태(토큰·가입 표시를 합친 흐름). 심은 세션이 반영될 때까지 기다리는 데 쓴다(시선 비서 기기 시험). */
    fun session(): SessionRepository

    /** debug는 FakeAuthProvider. QA가 SMS 자동 입력을 끄고 인증번호 직접 입력·오류 경로를 시험한다. */
    fun authProvider(): AuthProvider
}
