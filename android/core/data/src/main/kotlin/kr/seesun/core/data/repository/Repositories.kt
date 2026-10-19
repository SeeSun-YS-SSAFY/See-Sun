package kr.seesun.core.data.repository

import java.time.LocalDate
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kr.seesun.core.data.model.MealDraft
import kr.seesun.core.data.model.MealItem
import kr.seesun.core.data.model.MealRecord
import kr.seesun.core.data.sync.SyncOutcome
import kr.seesun.core.network.ApiResult
import kr.seesun.core.network.model.DietEvaluateResponse
import kr.seesun.core.network.model.FoodSummaryDto
import kr.seesun.core.network.model.RecommendationResponseDto

// 기능 모듈이 쓰는 저장소 인터페이스. 서버 연결 전에도 기능을 만들 수 있도록 가짜 구현(`fake` 패키지)이 있다.
// DataConfig.useFakeRepositories로 바꾼다.

/** 식사 기록(오프라인 우선). */
interface MealRepository {
    fun observeMeals(date: LocalDate): Flow<List<MealRecord>>

    /** 전송 대기 건수(마이페이지·설정의 "보내지 못한 기록 n건"). */
    fun observePendingCount(): Flow<Int>

    /** 기기에 먼저 저장하고(새 client_uuid) 동기화를 예약한다. 네트워크가 없어도 성공한다. */
    suspend fun record(draft: MealDraft): MealRecord

    /** 지금 바로 전송을 시도한다(당겨서 새로고침·테스트용). */
    suspend fun syncNow(): SyncOutcome

    /**
     * 기록 하나 지우기(오늘 기록 목록, v2.0). 목록에서 바로 빠지고, 서버에 있던 기록이면 연결될 때 서버에서도 지운다(오프라인 삭제 가능).
     * @return 지울 기록이 있었으면 true
     */
    suspend fun delete(clientUuid: String): Boolean
}

/** 음식 검색·확인 화면 평가. */
interface FoodRepository {
    suspend fun search(query: String, limit: Int = 20): ApiResult<List<FoodSummaryDto>>

    /** 확인 화면용 평가(저장 안 함): 영양값·알레르기·당류 경고(API 계약 §6.1). */
    suspend fun evaluate(items: List<MealItem>): ApiResult<DietEvaluateResponse>

    /**
     * 추천(§8). 경고 팝업의 [대체 식단 보기](type=allergy, key=알레르기 코드)·[추천 음식 보기](type=disease, key=DIABETES).
     */
    suspend fun recommendations(type: String, key: String?): ApiResult<RecommendationResponseDto>
}

/** 로그인 결과(온보딩 분기용). */
data class SignInResult(
    val isNewUser: Boolean,
    val profileCompleted: Boolean,
    val consentsRequired: List<String>,
    val sensitiveConsent: Boolean,
    val ttsMessage: String?
)

/** Firebase ID 토큰 → 앱 JWT 교환과 로그아웃. */
interface AuthRepository {
    val signedIn: StateFlow<Boolean>

    /** @param birthdate 신규 가입이면 필수(연령 확인 LGL-01 값, 서버 비저장). */
    suspend fun signIn(idToken: String, birthdate: LocalDate? = null, name: String? = null): ApiResult<SignInResult>

    suspend fun signOut()
}
