package kr.seesun.core.network.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// API 계약: docs/api/phase2-api.md v1.0 (2026-10-12) §0~§4, 기계 판독 명세 docs/api/openapi.yaml(우선).
// 스키마 이름은 각 DTO 주석에 적는다. OpenApiContractTest가 필드 이름이 명세에 있는지 검사한다.

/**
 * 오류 응답 공통 형식(§0). 서비스 오류는 `{"error","tts_message","errors"}`,
 * 인증 없음(401)·요청 제한(429)은 DRF 기본 `{"detail"}`(영어일 수 있어 낭독하지 않는다).
 */
@Serializable
data class ApiErrorDto(
    val error: String? = null,
    @SerialName("tts_message") val ttsMessage: String? = null,
    val errors: Map<String, List<String>>? = null,
    val detail: String? = null
)

/** 본문이 안내 문구뿐인 응답(로그아웃·탈퇴). */
@Serializable
data class MessageDto(val message: String? = null, @SerialName("tts_message") val ttsMessage: String? = null)

/** §1.1 요청: Firebase ID 토큰 → 앱 JWT. 스키마 FirebaseExchangeRequest. */
@Serializable
data class FirebaseTokenExchangeRequest(
    @SerialName("id_token") val idToken: String,
    /** 신규 가입이면 필수(만 14세 미만 차단, 서버 비저장). "YYYY-MM-DD" */
    val birthdate: String? = null,
    /** 1~20자 */
    val name: String? = null
)

/** 스키마 Onboarding */
@Serializable
data class OnboardingStatusDto(
    @SerialName("profile_completed") val profileCompleted: Boolean = false,
    @SerialName("consents_required") val consentsRequired: List<String> = emptyList(),
    @SerialName("sensitive_consent") val sensitiveConsent: Boolean = false
)

/** §1.1 응답. 스키마 FirebaseExchangeResponse(모든 필드 필수). */
@Serializable
data class AuthTokenResponse(
    @SerialName("access_token") val accessToken: String,
    @SerialName("refresh_token") val refreshToken: String,
    @SerialName("expires_in") val expiresIn: Int,
    @SerialName("is_new_user") val isNewUser: Boolean,
    val user: MeDto,
    val onboarding: OnboardingStatusDto,
    @SerialName("tts_message") val ttsMessage: String? = null
)

/** §1.2 스키마 TokenRefreshRequest */
@Serializable
data class TokenRefreshRequest(val refresh: String)

/** §1.2 스키마 TokenRefresh. 서버는 refresh 회전(ROTATE_REFRESH_TOKENS)을 켜서 새 refresh를 준다. */
@Serializable
data class TokenRefreshResponse(val access: String, val refresh: String? = null)

/** §1.3 스키마 LogoutRequest */
@Serializable
data class LogoutRequest(@SerialName("refresh_token") val refreshToken: String)

/** 스키마 TargetKcalDetail */
@Serializable
data class TargetKcalDetailDto(
    @SerialName("target_kcal") val targetKcal: Int? = null,
    val bmr: Double? = null,
    @SerialName("activity_factor") val activityFactor: Double? = null,
    val tdee: Double? = null,
    val adjustment: Int? = null,
    val formula: String? = null,
    val minor: Boolean = false
)

/** 스키마 AppSettings. `voice_speed` = SLOW|NORMAL|FAST */
@Serializable
data class UserSettingsDto(
    @SerialName("voice_guide") val voiceGuide: Boolean = true,
    @SerialName("voice_speed") val voiceSpeed: String = "NORMAL",
    @SerialName("hw_button_voice") val hwButtonVoice: Boolean = false
)

/** 스키마 AppSettingsRequest — 보낸 키만 덮어쓴다(§2). 그래서 기본값 없이 null이면 보내지 않는다. */
@Serializable
data class UserSettingsPatchDto(
    @SerialName("voice_guide") val voiceGuide: Boolean? = null,
    @SerialName("voice_speed") val voiceSpeed: String? = null,
    @SerialName("hw_button_voice") val hwButtonVoice: Boolean? = null
)

/** 성별(스키마 GenderEnum) */
object Genders {
    const val MALE = "M"
    const val FEMALE = "F"
}

/** 목표(스키마 GoalEnum) */
object Goals {
    const val LOSE_WEIGHT = "LOSE_WEIGHT"
    const val GAIN_MUSCLE = "GAIN_MUSCLE"
    const val MAINTAIN = "MAINTAIN"
    val ALL = listOf(LOSE_WEIGHT, GAIN_MUSCLE, MAINTAIN)
}

/**
 * §2 GET /me/ — 스키마 Me. 키·몸무게·목표 칼로리는 정수.
 * PATCH 응답에는 `tts_message`가 더 붙는다(명세 밖 필드, 낭독용).
 */
@Serializable
data class MeDto(
    val id: String,
    val name: String? = null,
    @SerialName("phone_number_masked") val phoneNumberMasked: String? = null,
    val birthdate: String? = null,
    val age: Int? = null,
    val gender: String? = null,
    @SerialName("height_cm") val heightCm: Int? = null,
    @SerialName("weight_kg") val weightKg: Int? = null,
    val goal: String? = null,
    @SerialName("target_kcal") val targetKcal: Int? = null,
    @SerialName("target_kcal_detail") val targetKcalDetail: TargetKcalDetailDto? = null,
    val settings: UserSettingsDto = UserSettingsDto(),
    @SerialName("profile_completed") val profileCompleted: Boolean = false,
    @SerialName("recommended_exercise_categories") val recommendedExerciseCategories: List<String> = emptyList(),
    @SerialName("tts_message") val ttsMessage: String? = null
)

/**
 * §2 PATCH /me/ — 스키마 PatchedMeUpdateRequest. 보낸 필드만 바뀐다(null은 직렬화에서 빠진다).
 * 검증: 이름 1~20자, 키 100~250, 몸무게 20~300(정수), 생년월일 만 14세 이상.
 */
@Serializable
data class MePatchRequest(
    val name: String? = null,
    val birthdate: String? = null,
    val gender: String? = null,
    @SerialName("height_cm") val heightCm: Int? = null,
    @SerialName("weight_kg") val weightKg: Int? = null,
    val goal: String? = null,
    val settings: UserSettingsPatchDto? = null
)

/** §2 DELETE /me/ — 확인 없으면 400 CONFIRMATION_REQUIRED. (명세에는 본문이 빠져 있으나 서버는 이 본문을 요구한다.) */
@Serializable
data class DeleteMeRequest(val confirmation: Boolean = true)

/** 동의 종류(스키마 ConsentTypeEnum) */
object ConsentTypes {
    const val TOS = "TOS"
    const val PRIVACY = "PRIVACY"
    const val OVERSEAS = "OVERSEAS"
    const val SENSITIVE = "SENSITIVE"
    const val MARKETING = "MARKETING"

    /** 화면에 보여 줄 순서 */
    val ORDER = listOf(TOS, PRIVACY, OVERSEAS, SENSITIVE, MARKETING)
}

/** 문서 종류(스키마 KindEnum): 동의 또는 고지 확인(거부 없음, 법적 검토 C-01). */
object DocumentKinds {
    const val CONSENT = "CONSENT"
    const val NOTICE = "NOTICE"
}

/** 동의 방법(스키마 MethodEnum) */
object ConsentMethods {
    const val TOUCH = "TOUCH"
    const val VOICE = "VOICE"
}

/** §3 GET /consents/documents/ — 스키마 ConsentDocument. `summary`는 낭독용 요약, `url`은 빈 문자열일 수 있다. */
@Serializable
data class ConsentDocumentDto(
    val type: String,
    val version: String,
    val title: String,
    val required: Boolean = false,
    val url: String? = null,
    val summary: String? = null,
    @SerialName("effective_at") val effectiveAt: String,
    /** CONSENT(동의) | NOTICE(고지 확인, 거부 없음) */
    val kind: String = DocumentKinds.CONSENT
)

/** 스키마 ConsentStatusItem */
@Serializable
data class ConsentStatusDto(
    val type: String,
    val title: String,
    val required: Boolean = false,
    @SerialName("current_version") val currentVersion: String,
    val agreed: Boolean = false,
    @SerialName("agreed_version") val agreedVersion: String? = null,
    @SerialName("agreed_at") val agreedAt: String? = null,
    val method: String? = null,
    @SerialName("needs_reconsent") val needsReconsent: Boolean = false,
    val kind: String = DocumentKinds.CONSENT
)

/**
 * §3 GET·POST /me/consents/ 응답 — 스키마 ConsentStatus.
 * [deleted]: POST에서 민감정보 철회로 지운 데이터 종류(`health_profile`, `diet_scores`).
 */
@Serializable
data class ConsentListResponse(
    val items: List<ConsentStatusDto>,
    @SerialName("all_required_agreed") val allRequiredAgreed: Boolean,
    val deleted: List<String> = emptyList()
)

/**
 * 스키마 ConsentDecisionRequest. type: ConsentTypes, method: ConsentMethods.
 * 입증 정보(법적 검토 C-07): [appVersion] 앱 버전, [screenId] 화면 ID(예 LGL-03), [voiceConfirmed] 음성 동의 재확인 통과.
 */
@Serializable
data class ConsentDecisionDto(
    val type: String,
    val version: String,
    val agreed: Boolean,
    val method: String,
    @SerialName("app_version") val appVersion: String? = null,
    @SerialName("screen_id") val screenId: String? = null,
    @SerialName("voice_confirmed") val voiceConfirmed: Boolean? = null
)

/** 스키마 ConsentDecisionListRequest */
@Serializable
data class ConsentUpdateRequest(val items: List<ConsentDecisionDto>)

/** §4 GET /allergens/ — 스키마 Allergen */
@Serializable
data class AllergenDto(val code: String, @SerialName("name_ko") val nameKo: String, @SerialName("display_order") val displayOrder: Int)

/** 질환(스키마 DiseasesEnum) */
object Diseases {
    const val DIABETES = "DIABETES"
    const val HYPERTENSION = "HYPERTENSION"
}

/** §4 GET·PUT /me/health/ 응답 — 스키마 HealthProfile. diseases: []=없음. PUT 응답에는 `tts_message`가 더 붙는다. */
@Serializable
data class HealthProfileDto(
    val diseases: List<String> = emptyList(),
    val allergens: List<String> = emptyList(),
    @SerialName("other_allergies") val otherAllergies: List<String> = emptyList(),
    val goal: String? = null,
    @SerialName("updated_at") val updatedAt: String? = null,
    @SerialName("tts_message") val ttsMessage: String? = null
)

/** §4 PUT /me/health/ 요청 — 스키마 HealthProfileRequest(통째 교체). goal을 보내면 목표 칼로리도 다시 계산한다. */
@Serializable
data class HealthProfileRequest(
    val diseases: List<String>,
    val allergens: List<String>,
    @SerialName("other_allergies") val otherAllergies: List<String> = emptyList(),
    val goal: String? = null
)

/** 데이터 초기화 응답(DELETE /me/meals/·/me/health/) — 스키마 PartialDeleteResponse */
@Serializable
data class PartialDeleteResponseDto(
    val deleted: List<String> = emptyList(),
    val count: Int? = null,
    @SerialName("tts_message") val ttsMessage: String? = null
)

/** §12 GET /healthz/ (API 기본 경로 밖, 미들웨어가 직접 응답) */
@Serializable
data class HealthzDto(val status: String, val db: String? = null)
