package kr.seesun.core.data.fake

import java.time.Clock
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.Period
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.roundToInt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kr.seesun.core.data.repository.AuthRepository
import kr.seesun.core.data.repository.ConsentRepository
import kr.seesun.core.data.repository.DataResetRepository
import kr.seesun.core.data.repository.HealthRepository
import kr.seesun.core.data.repository.ProfileRepository
import kr.seesun.core.data.repository.ResetResult
import kr.seesun.core.data.repository.SessionRepository
import kr.seesun.core.data.repository.SessionState
import kr.seesun.core.data.repository.SignInResult
import kr.seesun.core.network.ApiFailure
import kr.seesun.core.network.ApiResult
import kr.seesun.core.network.model.AllergenDto
import kr.seesun.core.network.model.ConsentDecisionDto
import kr.seesun.core.network.model.ConsentDocumentDto
import kr.seesun.core.network.model.ConsentListResponse
import kr.seesun.core.network.model.ConsentStatusDto
import kr.seesun.core.network.model.ConsentTypes
import kr.seesun.core.network.model.DocumentKinds
import kr.seesun.core.network.model.Goals
import kr.seesun.core.network.model.HealthProfileDto
import kr.seesun.core.network.model.HealthProfileRequest
import kr.seesun.core.network.model.MeDto
import kr.seesun.core.network.model.MePatchRequest
import kr.seesun.core.network.model.TargetKcalDetailDto

/**
 * 서버 없이 계정 흐름(동의·내 정보·건강·로그인·탈퇴)을 흉내 내는 메모리 백엔드.
 * 검증 규칙은 서버(계약 §1~§4)와 같게 맞췄다: 만 14세 미만 차단, 버전 불일치·필수 철회 거부, 민감정보 동의 게이트.
 *
 * 테스트 조작: [offline]을 켜면 모든 호출이 네트워크 실패, [failNext]는 다음 한 번만 그 실패를 낸다.
 */
@Singleton
class FakeAccountBackend @Inject constructor(private val clock: Clock) :
    ConsentRepository,
    ProfileRepository,
    HealthRepository,
    DataResetRepository {

    @Volatile var offline: Boolean = false

    @Volatile var failNext: ApiFailure? = null

    /** 이름이 [call]인 호출(예: "submit") 한 번만 [failure]로 실패시킨다. */
    fun failOnce(call: String, failure: ApiFailure = ApiFailure(ApiFailure.NETWORK, ApiFailure.NETWORK_MESSAGE)) {
        failOn = call to failure
    }

    @Volatile private var failOn: Pair<String, ApiFailure>? = null

    /** 응답 지연(밀리초). 요청 중 화면 동작(두 번 누름 등)을 시험할 때 쓴다. */
    @Volatile var responseDelayMs: Long = 0

    /** 호출 기록(테스트에서 중복 제출 확인용). */
    val calls: MutableList<String> = mutableListOf()

    var me: MeDto? = null
        private set

    private val records = linkedMapOf<String, ConsentDecisionDto>()
    private var health = HealthProfileDto()

    var documents: List<ConsentDocumentDto> = DEFAULT_DOCUMENTS

    // ── 인증 ─────────────────────────────────────────────

    @Synchronized
    fun signIn(phone: String, birthdate: LocalDate?, name: String?): ApiResult<SignInResult> {
        failure("signIn")?.let { return it }
        val existing = me
        if (existing == null) {
            if (birthdate == null) return fail("BIRTHDATE_REQUIRED", "생년월일을 먼저 입력해 주세요.", HTTP_BAD_REQUEST)
            if (birthdate.isAfter(today()) || Period.between(birthdate, today()).years < MIN_AGE) {
                return fail("AGE_RESTRICTED", "만 14세 이상만 가입할 수 있어요.", HTTP_FORBIDDEN)
            }
            me = MeDto(id = "fake-user", name = name, phoneNumberMasked = mask(phone), birthdate = birthdate.toString()).recompute()
        }
        return ApiResult.Success(
            SignInResult(
                isNewUser = existing == null,
                profileCompleted = me!!.profileCompleted,
                consentsRequired = missingRequired(),
                sensitiveConsent = agreed(ConsentTypes.SENSITIVE),
                ttsMessage = if (existing == null) "전화번호 인증이 완료되었습니다." else "로그인되었습니다."
            )
        )
    }

    @Synchronized
    fun deleteAccount(): ApiResult<String?> {
        failure("deleteAccount")?.let { return it }
        reset()
        return ApiResult.Success("회원 탈퇴가 완료되었어요. 그동안 시선을 이용해 주셔서 감사합니다.")
    }

    @Synchronized
    fun reset() {
        me = null
        records.clear()
        health = HealthProfileDto()
    }

    // ── 동의 ─────────────────────────────────────────────

    override suspend fun documents(): ApiResult<List<ConsentDocumentDto>> {
        pause()
        return failure("documents") ?: ApiResult.Success(documents)
    }

    override suspend fun status(): ApiResult<ConsentListResponse> = pause().let {
        synchronized(this) {
            failure("status") ?: signedInOr { ApiResult.Success(statusNow()) }
        }
    }

    override suspend fun submit(decisions: List<ConsentDecisionDto>): ApiResult<ConsentListResponse> = pause().let {
        synchronized(this) {
            failure("submit") ?: signedInOr {
                val byType = documents.associateBy { it.type }
                decisions.forEach { d ->
                    val doc = byType[d.type] ?: return@signedInOr fail("UNKNOWN_CONSENT", "지금 받을 수 없는 동의 항목이에요.", HTTP_BAD_REQUEST)
                    if (doc.version != d.version) return@signedInOr fail("VERSION_MISMATCH", "동의 문서가 바뀌었어요.", HTTP_BAD_REQUEST)
                    if (doc.kind == DocumentKinds.NOTICE && !d.agreed) {
                        return@signedInOr fail("NOTICE_ACK_ONLY", "고지는 확인만 할 수 있어요.", HTTP_BAD_REQUEST)
                    }
                    if (doc.required && !d.agreed) {
                        return@signedInOr fail("REQUIRED_CONSENT", "필수 동의는 철회할 수 없어요.", HTTP_BAD_REQUEST)
                    }
                }
                var deleted = emptyList<String>()
                decisions.forEach { d ->
                    if (d.type == ConsentTypes.SENSITIVE && !d.agreed && records[d.type]?.agreed == true) {
                        health = HealthProfileDto(goal = me?.goal)
                        deleted = listOf("diet_scores", "health_profile")
                    }
                    records[d.type] = d
                }
                ApiResult.Success(statusNow().copy(deleted = deleted))
            }
        }
    }

    // ── 내 정보 ──────────────────────────────────────────

    override suspend fun me(): ApiResult<MeDto> = pause().let {
        synchronized(this) {
            failure("me") ?: signedInOr { ApiResult.Success(me!!) }
        }
    }

    override suspend fun update(patch: MePatchRequest): ApiResult<MeDto> = pause().let {
        synchronized(this) {
            failure("update") ?: signedInOr {
                val current = me!!
                val birth = patch.birthdate?.let(LocalDate::parse)
                if (birth != null && Period.between(birth, today()).years < MIN_AGE) {
                    return@signedInOr fail("AGE_RESTRICTED", "만 14세 이상만 이용할 수 있어요.", HTTP_FORBIDDEN)
                }
                val updated = current.copy(
                    name = patch.name ?: current.name,
                    birthdate = patch.birthdate ?: current.birthdate,
                    gender = patch.gender ?: current.gender,
                    heightCm = patch.heightCm ?: current.heightCm,
                    weightKg = patch.weightKg ?: current.weightKg,
                    goal = patch.goal ?: current.goal
                ).recompute()
                me = updated
                ApiResult.Success(updated.copy(ttsMessage = "정보를 저장했어요."))
            }
        }
    }

    // ── 건강 ─────────────────────────────────────────────

    override suspend fun allergens(): ApiResult<List<AllergenDto>> {
        pause()
        return failure("allergens") ?: ApiResult.Success(ALLERGENS)
    }

    override suspend fun get(): ApiResult<HealthProfileDto> = pause().let {
        synchronized(this) {
            failure("health") ?: sensitiveOr { ApiResult.Success(health.copy(goal = me?.goal)) }
        }
    }

    override suspend fun save(request: HealthProfileRequest): ApiResult<HealthProfileDto> = pause().let {
        synchronized(this) {
            failure("saveHealth") ?: sensitiveOr {
                val known = ALLERGENS.map { it.code }.toSet()
                if (request.allergens.any { it !in known }) return@sensitiveOr fail("VALIDATION_ERROR", "알 수 없는 알레르기예요.", HTTP_BAD_REQUEST)
                health = HealthProfileDto(
                    diseases = request.diseases.distinct().sorted(),
                    allergens = request.allergens.distinct().sorted(),
                    otherAllergies = request.otherAllergies.map(String::trim).filter(String::isNotEmpty),
                    updatedAt = OffsetDateTime.now(clock).toString()
                )
                if (request.goal != null) me = me!!.copy(goal = request.goal).recompute()
                ApiResult.Success(health.copy(goal = me?.goal, ttsMessage = "건강 정보를 저장했어요."))
            }
        }
    }

    // ── 데이터 초기화 ────────────────────────────────────

    /** 지운 데이터 종류 기록(시험 확인용). */
    val resets: MutableList<String> = mutableListOf()

    override suspend fun deleteMeals(): ApiResult<ResetResult> {
        pause()
        return synchronized(this) {
            failure("deleteMeals") ?: signedInOr {
                resets += "meals"
                ApiResult.Success(ResetResult("식사 기록 0건을 삭제했어요.", 0))
            }
        }
    }

    override suspend fun deleteHealth(): ApiResult<ResetResult> {
        pause()
        return synchronized(this) {
            failure("deleteHealth") ?: signedInOr {
                resets += "health"
                health = HealthProfileDto()
                ApiResult.Success(ResetResult("건강 정보를 삭제했어요. 질환과 알레르기 정보가 지워졌어요."))
            }
        }
    }

    // ── 내부 ─────────────────────────────────────────────

    fun agreed(type: String): Boolean {
        val doc = documents.firstOrNull { it.type == type } ?: return false
        val rec = records[type] ?: return false
        return rec.agreed && rec.version == doc.version
    }

    private fun statusNow(): ConsentListResponse {
        val items = documents.map { doc ->
            val rec = records[doc.type]
            ConsentStatusDto(
                type = doc.type,
                kind = doc.kind,
                title = doc.title,
                required = doc.required,
                currentVersion = doc.version,
                agreed = agreed(doc.type),
                agreedVersion = rec?.takeIf { it.agreed }?.version,
                agreedAt = rec?.takeIf { it.agreed }?.let { OffsetDateTime.now(clock).toString() },
                method = rec?.method,
                needsReconsent = rec != null && rec.agreed && rec.version != doc.version
            )
        }
        return ConsentListResponse(items, items.filter { it.required }.all { it.agreed })
    }

    private fun missingRequired(): List<String> = documents.filter { it.required && !agreed(it.type) }.map { it.type }

    private fun MeDto.recompute(): MeDto {
        val birth = birthdate?.let(LocalDate::parse)
        val age = birth?.let { Period.between(it, today()).years }
        val sexConstant = when (gender) {
            "M" -> MALE_CONSTANT
            "F" -> FEMALE_CONSTANT
            else -> null
        }
        val inputs = listOf(age, sexConstant, heightCm, weightKg)
        val target = if (inputs.any { it == null }) null else targetKcal(age!!, sexConstant!!, heightCm!!, weightKg!!, goal)
        return copy(
            age = age,
            targetKcal = target?.targetKcal,
            targetKcalDetail = target,
            profileCompleted = !name.isNullOrBlank() && birth != null && sexConstant != null && heightCm != null && weightKg != null
        )
    }

    private fun targetKcal(age: Int, sexConstant: Double, height: Int, weight: Int, goal: String?): TargetKcalDetailDto {
        val bmr = WEIGHT_COEF * weight + HEIGHT_COEF * height - AGE_COEF * age + sexConstant
        val adjustment = when (goal) {
            Goals.LOSE_WEIGHT -> LOSE_ADJUSTMENT
            Goals.GAIN_MUSCLE -> GAIN_ADJUSTMENT
            else -> 0
        }
        return TargetKcalDetailDto(
            (bmr * ACTIVITY + adjustment).roundToInt(),
            bmr,
            ACTIVITY,
            bmr * ACTIVITY,
            adjustment,
            "mifflin_st_jeor_v1"
        )
    }

    private fun today(): LocalDate = LocalDate.now(clock)

    private suspend fun pause() {
        if (responseDelayMs > 0) delay(responseDelayMs)
    }

    private fun mask(phone: String): String {
        val digits = phone.filter(Char::isDigit).let { if (it.startsWith("82")) "0" + it.drop(2) else it }
        return if (digits.length >= PHONE_MIN) "${digits.take(3)}-****-${digits.takeLast(4)}" else digits
    }

    private fun failure(call: String): ApiResult.Failure? {
        calls += call
        failOn?.takeIf { it.first == call }?.let {
            failOn = null
            return ApiResult.Failure(it.second)
        }
        failNext?.let {
            failNext = null
            return ApiResult.Failure(it)
        }
        return if (offline) ApiResult.Failure(ApiFailure(ApiFailure.NETWORK, ApiFailure.NETWORK_MESSAGE)) else null
    }

    private inline fun <T> signedInOr(block: () -> ApiResult<T>): ApiResult<T> =
        if (me == null) fail(ApiFailure.UNAUTHORIZED, ApiFailure.UNAUTHORIZED_MESSAGE, HTTP_UNAUTHORIZED) else block()

    private inline fun <T> sensitiveOr(block: () -> ApiResult<T>): ApiResult<T> = signedInOr {
        if (!agreed(ConsentTypes.SENSITIVE)) {
            fail(ApiFailure.SENSITIVE_CONSENT_REQUIRED, "건강정보 처리 동의가 필요해요.", HTTP_FORBIDDEN)
        } else {
            block()
        }
    }

    private fun fail(code: String, message: String, status: Int) = ApiResult.Failure(ApiFailure(code, message, status))

    companion object {
        private const val MIN_AGE = 14
        private const val HTTP_BAD_REQUEST = 400
        private const val HTTP_UNAUTHORIZED = 401
        private const val HTTP_FORBIDDEN = 403
        private const val PHONE_MIN = 10
        private const val WEIGHT_COEF = 10.0
        private const val HEIGHT_COEF = 6.25
        private const val AGE_COEF = 5.0
        private const val MALE_CONSTANT = 5.0
        private const val FEMALE_CONSTANT = -161.0
        private const val ACTIVITY = 1.3
        private const val LOSE_ADJUSTMENT = -500
        private const val GAIN_ADJUSTMENT = 300
        const val VERSION = "2026-10-01"

        /** 국외이전 고지(고지 확인으로 바뀐 판) */
        const val NOTICE_VERSION = "2026-10-12"

        /** 문서 종류별 현재 버전. */
        fun versionOf(type: String): String = if (type == ConsentTypes.OVERSEAS) NOTICE_VERSION else VERSION

        /** 서버 backend/apps/consents/data/documents.json과 같은 내용(광고성 정보는 1단계 비활성이라 뺌). */
        val DEFAULT_DOCUMENTS = listOf(
            ConsentDocumentDto(
                ConsentTypes.TOS,
                VERSION,
                "See:Sun 이용약관",
                required = true,
                url = "",
                summary = "See:Sun은 의료기기가 아닌 참고용 건강관리 서비스입니다. 서비스 이용 조건과 책임 범위를 안내합니다.",
                effectiveAt = VERSION
            ),
            ConsentDocumentDto(
                ConsentTypes.PRIVACY,
                VERSION,
                "개인정보 수집·이용 안내",
                required = true,
                url = "",
                summary = "이름, 전화번호, 생년월일, 성별, 키, 몸무게를 서비스 제공을 위해 수집하며, 탈퇴하면 바로 파기합니다.",
                effectiveAt = VERSION
            ),
            ConsentDocumentDto(
                ConsentTypes.OVERSEAS,
                NOTICE_VERSION,
                "개인정보 국외이전 고지",
                kind = DocumentKinds.NOTICE,
                required = false,
                url = "",
                summary = "전화번호 인증을 위해 개인정보가 국외로 이전됩니다. 이전 항목: 휴대폰 번호, 인증 요청 시 기기·네트워크 정보(IP 주소 등). " +
                    "이전 국가: 미국. 이전 시기와 방법: 인증번호를 요청할 때 암호화된 네트워크로 전송. " +
                    "받는 자: Google LLC(Firebase Authentication). 이용 목적: 휴대폰 문자 인증과 로그인. " +
                    "보유 기간: 회원 탈퇴 시 삭제 요청, 삭제 완료까지 최대 180일. " +
                    "거부 방법과 효과: 전화번호 인증을 진행하지 않으면 되며, 이 경우 가입할 수 없습니다.",
                effectiveAt = NOTICE_VERSION
            ),
            ConsentDocumentDto(
                ConsentTypes.SENSITIVE,
                VERSION,
                "건강정보(민감정보) 처리 동의",
                required = false,
                url = "",
                summary = "질환과 알레르기 정보를 맞춤 경고와 추천에 사용합니다. 동의하지 않아도 기본 기능을 이용할 수 있고, " +
                    "설정에서 언제든 철회할 수 있습니다.",
                effectiveAt = VERSION
            )
        )

        /** 지정 순서 19종(계약 §4). */
        val ALLERGENS = listOf(
            "EGG" to "알류", "MILK" to "우유", "BUCKWHEAT" to "메밀", "PEANUT" to "땅콩", "SOYBEAN" to "대두",
            "WHEAT" to "밀", "PINE_NUT" to "잣", "WALNUT" to "호두", "CRAB" to "게", "SHRIMP" to "새우",
            "SQUID" to "오징어", "MACKEREL" to "고등어", "SHELLFISH" to "조개류", "PEACH" to "복숭아", "TOMATO" to "토마토",
            "CHICKEN" to "닭고기", "PORK" to "돼지고기", "BEEF" to "쇠고기", "SULFITE" to "아황산류"
        ).mapIndexed { i, (code, name) -> AllergenDto(code, name, i + 1) }
    }
}

/** 가짜 로그인: [FakeAccountBackend]에 계정을 만들거나 찾는다. ID 토큰 `fake:<uid>:<E.164>`에서 전화번호를 읽는다. */
@Singleton
class FakeAuthRepository @Inject constructor(private val backend: FakeAccountBackend) : AuthRepository {
    private val state = MutableStateFlow(false)
    override val signedIn: StateFlow<Boolean> = state.asStateFlow()

    override suspend fun signIn(idToken: String, birthdate: LocalDate?, name: String?): ApiResult<SignInResult> {
        val result = backend.signIn(idToken.substringAfterLast(':'), birthdate, name)
        if (result is ApiResult.Success) state.value = true
        return result
    }

    override suspend fun signOut() {
        state.value = false
    }
}

/** 가짜 세션: 기기 데이터 삭제 대신 메모리 상태만 지운다. */
@Singleton
class FakeSessionRepository @Inject constructor(private val backend: FakeAccountBackend, private val auth: FakeAuthRepository) :
    SessionRepository {
    private val onboarded = MutableStateFlow(false)

    // Unconfined: 로그인 상태가 바뀌면 같은 호출 안에서 바로 반영된다(테스트에서 기다릴 필요 없음).
    override val state: StateFlow<SessionState> = combine(auth.signedIn, onboarded, ::SessionState)
        .stateIn(CoroutineScope(SupervisorJob() + Dispatchers.Unconfined), SharingStarted.Eagerly, SessionState(false, false))

    /** 지운 횟수(테스트 확인용). */
    var wipeCount: Int = 0
        private set

    override fun markOnboardingCompleted() {
        onboarded.value = true
    }

    override suspend fun logout() {
        auth.signOut()
        wipe()
    }

    override suspend fun deleteAccount(): ApiResult<String?> {
        val result = backend.deleteAccount()
        if (result is ApiResult.Success) {
            auth.signOut()
            wipe()
        }
        return result
    }

    private fun wipe() {
        wipeCount++
        onboarded.value = false
    }
}
