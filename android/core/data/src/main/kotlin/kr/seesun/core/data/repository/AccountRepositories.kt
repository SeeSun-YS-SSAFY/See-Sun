package kr.seesun.core.data.repository

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kr.seesun.core.network.ApiResult
import kr.seesun.core.network.model.AllergenDto
import kr.seesun.core.network.model.ConsentDecisionDto
import kr.seesun.core.network.model.ConsentDocumentDto
import kr.seesun.core.network.model.ConsentListResponse
import kr.seesun.core.network.model.HealthProfileDto
import kr.seesun.core.network.model.HealthProfileRequest
import kr.seesun.core.network.model.MeDto
import kr.seesun.core.network.model.MePatchRequest

// 온보딩·메인·마이페이지가 쓰는 계정 저장소(API 계약 §2~§4). 가짜 구현은 fake/FakeAccountRepositories.kt.

/** 동의(§3). 문서 목록은 로그인 전에도 읽을 수 있다. */
interface ConsentRepository {
    /** 현재 동의 문서(유형·버전·제목·필수 여부·낭독용 요약). */
    suspend fun documents(): ApiResult<List<ConsentDocumentDto>>

    /** 내 동의 상태(로그인 필요). */
    suspend fun status(): ApiResult<ConsentListResponse>

    /** 동의·철회 기록. 민감정보 철회면 응답 `deleted`에 지운 데이터 종류가 온다. */
    suspend fun submit(decisions: List<ConsentDecisionDto>): ApiResult<ConsentListResponse>
}

/** 내 정보(§2). */
interface ProfileRepository {
    suspend fun me(): ApiResult<MeDto>

    /** 보낸 필드만 바뀐다. 응답에 다시 계산한 목표 칼로리가 들어 있다. */
    suspend fun update(patch: MePatchRequest): ApiResult<MeDto>
}

/** 건강 설정(§4) — 민감정보 동의가 없으면 403 SENSITIVE_CONSENT_REQUIRED. */
interface HealthRepository {
    /** 알레르기 19종(지정 순서). */
    suspend fun allergens(): ApiResult<List<AllergenDto>>

    suspend fun get(): ApiResult<HealthProfileDto>

    /** 통째로 바꾼다. goal을 보내면 목표 칼로리도 다시 계산된다. */
    suspend fun save(request: HealthProfileRequest): ApiResult<HealthProfileDto>
}

/** 앱 시작 경로를 정하는 세션 상태. */
data class SessionState(val signedIn: Boolean, val onboardingCompleted: Boolean) {
    /** 로그인 전이거나 가입 단계를 아직 마치지 않았으면 온보딩부터. */
    val needsOnboarding: Boolean get() = !signedIn || !onboardingCompleted
}

/** 로그인 세션·로그아웃·탈퇴. 로그아웃·탈퇴는 이 기기의 개인 데이터를 모두 지운다. */
interface SessionRepository {
    val state: StateFlow<SessionState>

    /** 가입 완료 요약까지 마쳤다(다음 실행부터 메인으로 바로 간다). */
    fun markOnboardingCompleted()

    /** 서버 로그아웃(실패해도 진행) → 기기 데이터 삭제. */
    suspend fun logout()

    /**
     * 회원 탈퇴: `DELETE /me/` 성공 후에만 기기 데이터를 지운다. 실패면 아무것도 지우지 않는다.
     * @return 성공 시 서버 낭독 문구(없으면 null)
     */
    suspend fun deleteAccount(): ApiResult<String?>
}

/** 이 기기에 남은 개인 데이터(암호화 DB·전송 대기 작업·로그인 토큰·세션 표시·[AccountScopedStore])를 지운다. */
fun interface LocalDataWiper {
    suspend fun wipe()
}

/**
 * DB 밖에 둔 계정별 기기 저장값(예: 운동 안전 문답). 기능 모듈이 Hilt 집합(@IntoSet)으로 등록하면
 * 로그아웃·탈퇴 때 [LocalDataWiper]가 모두 지운다. 다음에 로그인한 사람에게 넘어가지 않게 하려는 것.
 */
fun interface AccountScopedStore {
    fun clearOnSignOut()
}

/** 데이터 초기화 결과: 서버 낭독 문구와 지운 식사 기록 수. */
data class ResetResult(val ttsMessage: String?, val count: Int? = null)

/** 계정은 두고 일부 데이터만 지운다(법적 검토 A-03). */
interface DataResetRepository {
    /** 식사 기록 전체(서버 + 이 기기의 기록·전송 대기). */
    suspend fun deleteMeals(): ApiResult<ResetResult>

    /** 건강 정보만(질환·알레르기·기타, 질환 기반 점수). 동의 기록과 목표는 남는다. */
    suspend fun deleteHealth(): ApiResult<ResetResult>
}

/**
 * 아직 서버로 보내지 못한 기록(식사 + 운동) 건수. 메인 요약과 로그아웃 경고(로그아웃하면 함께 지워짐)에 쓴다.
 * 운동 기록이 빠지면 로그아웃 때 경고 없이 운동 기록이 지워진다(E2E 통합 시험 BUG-03).
 */
fun interface UnsentRecords {
    fun observeCount(): Flow<Int>
}
