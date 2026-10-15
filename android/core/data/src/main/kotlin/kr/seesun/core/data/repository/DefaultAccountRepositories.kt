package kr.seesun.core.data.repository

import android.content.Context
import androidx.work.WorkManager
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kr.seesun.core.auth.AuthProvider
import kr.seesun.core.auth.TokenStore
import kr.seesun.core.auth.security.SecretStore
import kr.seesun.core.data.db.SeeSunDatabase
import kr.seesun.core.data.di.DataConfig
import kr.seesun.core.data.sync.ExerciseSyncScheduler
import kr.seesun.core.data.sync.SyncScheduler
import kr.seesun.core.network.ApiResult
import kr.seesun.core.network.AuthApi
import kr.seesun.core.network.SeeSunApi
import kr.seesun.core.network.apiCall
import kr.seesun.core.network.model.AllergenDto
import kr.seesun.core.network.model.ConsentDecisionDto
import kr.seesun.core.network.model.ConsentDocumentDto
import kr.seesun.core.network.model.ConsentListResponse
import kr.seesun.core.network.model.ConsentUpdateRequest
import kr.seesun.core.network.model.DeleteMeRequest
import kr.seesun.core.network.model.HealthProfileDto
import kr.seesun.core.network.model.HealthProfileRequest
import kr.seesun.core.network.model.MeDto
import kr.seesun.core.network.model.MePatchRequest

@Singleton
class NetworkConsentRepository @Inject constructor(
    private val authApi: AuthApi,
    private val api: SeeSunApi,
    private val json: Json,
    private val config: DataConfig,
    private val mealSync: SyncScheduler,
    private val exerciseSync: ExerciseSyncScheduler
) : ConsentRepository {
    override suspend fun documents(): ApiResult<List<ConsentDocumentDto>> = apiCall(json) { authApi.consentDocuments() }

    override suspend fun status(): ApiResult<ConsentListResponse> = apiCall(json) { api.consents() }

    /**
     * 앱 버전을 붙여 보낸다(입증 정보, 법적 검토 C-07). 필수 동의가 모두 갖춰지면 동의가 없어 서버가 막았던(403)
     * 보내지 못한 식사·운동 기록 전송을 다시 예약한다(통합 E2E BUG-10: 운동 화면을 다시 열 때까지 남아 있었다).
     */
    override suspend fun submit(decisions: List<ConsentDecisionDto>): ApiResult<ConsentListResponse> {
        val result = apiCall(json) {
            val stamped = decisions.map { it.copy(appVersion = it.appVersion ?: config.appVersion.takeIf(String::isNotBlank)) }
            api.updateConsents(ConsentUpdateRequest(stamped))
        }
        if (result is ApiResult.Success && result.data.allRequiredAgreed) {
            mealSync.scheduleMealSync()
            exerciseSync.scheduleExerciseSync()
        }
        return result
    }
}

/** 데이터 초기화: 서버에서 지운 뒤 식사 기록은 이 기기의 암호화 DB에서도 지운다(전송 대기 기록 포함). */
@Singleton
class NetworkDataResetRepository @Inject constructor(
    private val api: SeeSunApi,
    private val database: dagger.Lazy<SeeSunDatabase>,
    private val json: Json
) : DataResetRepository {
    override suspend fun deleteMeals(): ApiResult<ResetResult> = when (val r = apiCall(json) { api.deleteMeals() }) {
        is ApiResult.Failure -> r

        is ApiResult.Success -> {
            withContext(Dispatchers.IO) { database.get().mealDao().deleteAll() }
            ApiResult.Success(ResetResult(r.data.ttsMessage, r.data.count))
        }
    }

    override suspend fun deleteHealth(): ApiResult<ResetResult> = when (val r = apiCall(json) { api.deleteHealth() }) {
        is ApiResult.Failure -> r
        is ApiResult.Success -> ApiResult.Success(ResetResult(r.data.ttsMessage))
    }
}

@Singleton
class NetworkProfileRepository @Inject constructor(private val api: SeeSunApi, private val json: Json) : ProfileRepository {
    override suspend fun me(): ApiResult<MeDto> = apiCall(json) { api.me() }

    override suspend fun update(patch: MePatchRequest): ApiResult<MeDto> = apiCall(json) { api.updateMe(patch) }
}

@Singleton
class NetworkHealthRepository @Inject constructor(private val api: SeeSunApi, private val json: Json) : HealthRepository {
    override suspend fun allergens(): ApiResult<List<AllergenDto>> = apiCall(json) { api.allergens().sortedBy { it.displayOrder } }

    override suspend fun get(): ApiResult<HealthProfileDto> = apiCall(json) { api.health() }

    override suspend fun save(request: HealthProfileRequest): ApiResult<HealthProfileDto> = apiCall(json) { api.updateHealth(request) }
}

/** 세션 표시(가입 완료 여부)는 토큰과 같은 Keystore 저장소에 둔다. 개인정보는 아니다. */
@Singleton
class SessionFlags @Inject constructor(private val secrets: SecretStore) {
    private val completed = MutableStateFlow(secrets.get(KEY_ONBOARDED) == VALUE_TRUE)
    val onboardingCompleted: StateFlow<Boolean> = completed

    fun setOnboardingCompleted(value: Boolean) {
        secrets.put(KEY_ONBOARDED, if (value) VALUE_TRUE else null)
        completed.value = value
    }

    private companion object {
        const val KEY_ONBOARDED = "session.onboarding_completed"
        const val VALUE_TRUE = "1"
    }
}

/**
 * 기기 데이터 삭제: 암호화 DB 비우기(식사·운동 기록·즐겨찾기 모두 한 DB) → 계정별 저장값 → 예약 작업 취소
 * → 토큰·세션 표시 삭제 → 인증 공급자 로그아웃.
 */
@Singleton
class DeviceDataWiper @Inject constructor(
    @ApplicationContext private val context: Context,
    private val database: dagger.Lazy<SeeSunDatabase>,
    private val accountStores: Set<@JvmSuppressWildcards AccountScopedStore>,
    private val tokens: TokenStore,
    private val flags: SessionFlags,
    private val authProvider: AuthProvider
) : LocalDataWiper {
    override suspend fun wipe() {
        withContext(Dispatchers.IO) { database.get().clearAllTables() }
        accountStores.forEach { it.clearOnSignOut() }
        WorkManager.getInstance(context).cancelAllWork()
        flags.setOnboardingCompleted(false)
        tokens.clear()
        authProvider.signOut()
    }
}

@Singleton
class DefaultSessionRepository @Inject constructor(
    private val auth: AuthRepository,
    private val flags: SessionFlags,
    private val api: SeeSunApi,
    private val wiper: LocalDataWiper,
    private val json: Json
) : SessionRepository {

    // Unconfined: 토큰·가입 표시가 바뀌면 바꾼 호출 안에서 바로 반영된다(화면 전환이 늦지 않게).
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)

    override val state: StateFlow<SessionState> = combine(auth.signedIn, flags.onboardingCompleted, ::SessionState)
        .stateIn(scope, SharingStarted.Eagerly, SessionState(auth.signedIn.value, flags.onboardingCompleted.value))

    override fun markOnboardingCompleted() = flags.setOnboardingCompleted(true)

    override suspend fun logout() {
        auth.signOut()
        wiper.wipe()
    }

    override suspend fun deleteAccount(): ApiResult<String?> = when (val result = apiCall(json) { api.deleteMe(DeleteMeRequest()) }) {
        is ApiResult.Failure -> result

        is ApiResult.Success -> {
            wiper.wipe()
            ApiResult.Success(result.data.ttsMessage)
        }
    }
}

/** 식사 큐(PENDING) + 운동 큐(PENDING) 합계. */
@Singleton
class QueueUnsentRecords @Inject constructor(private val meals: MealRepository, private val exercises: ExerciseLogRepository) :
    UnsentRecords {
    override fun observeCount(): Flow<Int> = combine(meals.observePendingCount(), exercises.observePendingCount()) { m, e -> m + e }
}
