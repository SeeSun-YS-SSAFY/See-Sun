package kr.seesun.core.network.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// API 계약: 05-seesun-backend/docs/api/phase2-api.md v0.1 (2026-10-12) §0~§4.
// openapi.yaml이 나오면 그 스키마 이름에 맞춰 다시 확인한다(계약상 openapi.yaml 우선).

/** 오류 응답 공통 형식(§0). */
@Serializable
data class ApiErrorDto(
    val error: String,
    @SerialName("tts_message") val ttsMessage: String? = null,
    val errors: Map<String, List<String>>? = null,
)

/** §1.1 요청: Firebase ID 토큰 → 앱 JWT. */
@Serializable
data class FirebaseTokenExchangeRequest(
    @SerialName("id_token") val idToken: String,
    /** 신규 가입이면 필수(만 14세 미만 차단, 서버 비저장). "YYYY-MM-DD" */
    val birthdate: String? = null,
    val name: String? = null,
)

@Serializable
data class OnboardingStatusDto(
    @SerialName("profile_completed") val profileCompleted: Boolean = false,
    @SerialName("consents_required") val consentsRequired: List<String> = emptyList(),
    @SerialName("sensitive_consent") val sensitiveConsent: Boolean = false,
)

/** §1.1 응답. */
@Serializable
data class AuthTokenResponse(
    @SerialName("access_token") val accessToken: String,
    @SerialName("refresh_token") val refreshToken: String,
    @SerialName("expires_in") val expiresIn: Int,
    @SerialName("is_new_user") val isNewUser: Boolean,
    val user: MeDto? = null,
    val onboarding: OnboardingStatusDto = OnboardingStatusDto(),
    @SerialName("tts_message") val ttsMessage: String? = null,
)

/** §1.2 */
@Serializable
data class TokenRefreshRequest(val refresh: String)

@Serializable
data class TokenRefreshResponse(val access: String, val refresh: String? = null)

/** §1.3 */
@Serializable
data class LogoutRequest(@SerialName("refresh_token") val refreshToken: String)

@Serializable
data class TargetKcalDetailDto(
    val bmr: Double? = null,
    @SerialName("activity_factor") val activityFactor: Double? = null,
    val adjustment: Double? = null,
    val formula: String? = null,
)

/** `settings.voice_speed` = SLOW|NORMAL|FAST */
@Serializable
data class UserSettingsDto(
    @SerialName("voice_guide") val voiceGuide: Boolean = true,
    @SerialName("voice_speed") val voiceSpeed: String = "NORMAL",
    @SerialName("hw_button_voice") val hwButtonVoice: Boolean = false,
)

/** §2 GET /me/ — goal: LOSE_WEIGHT|GAIN_MUSCLE|MAINTAIN, gender: M|F */
@Serializable
data class MeDto(
    val id: String,
    val name: String? = null,
    @SerialName("phone_number_masked") val phoneNumberMasked: String? = null,
    val birthdate: String? = null,
    val age: Int? = null,
    val gender: String? = null,
    @SerialName("height_cm") val heightCm: Double? = null,
    @SerialName("weight_kg") val weightKg: Double? = null,
    val goal: String? = null,
    @SerialName("target_kcal") val targetKcal: Double? = null,
    @SerialName("target_kcal_detail") val targetKcalDetail: TargetKcalDetailDto? = null,
    val settings: UserSettingsDto = UserSettingsDto(),
    @SerialName("profile_completed") val profileCompleted: Boolean = false,
    @SerialName("recommended_exercise_categories") val recommendedExerciseCategories: List<String> = emptyList(),
)

/** §2 PATCH /me/ — 보낸 필드만 바뀐다(null은 직렬화에서 빠진다). */
@Serializable
data class MePatchRequest(
    val name: String? = null,
    val birthdate: String? = null,
    val gender: String? = null,
    @SerialName("height_cm") val heightCm: Double? = null,
    @SerialName("weight_kg") val weightKg: Double? = null,
    val goal: String? = null,
    val settings: UserSettingsDto? = null,
)

/** §2 DELETE /me/ */
@Serializable
data class DeleteMeRequest(val confirmation: Boolean = true)

/** §3 GET /consents/documents/ */
@Serializable
data class ConsentDocumentDto(
    val type: String,
    val version: String,
    val title: String,
    val required: Boolean,
    val url: String? = null,
    val summary: String? = null,
    @SerialName("effective_at") val effectiveAt: String? = null,
)

@Serializable
data class ConsentStatusDto(
    val type: String,
    val title: String? = null,
    val required: Boolean = false,
    @SerialName("current_version") val currentVersion: String? = null,
    val agreed: Boolean = false,
    @SerialName("agreed_version") val agreedVersion: String? = null,
    @SerialName("agreed_at") val agreedAt: String? = null,
    val method: String? = null,
    @SerialName("needs_reconsent") val needsReconsent: Boolean = false,
)

/** §3 GET·POST /me/consents/ 응답 */
@Serializable
data class ConsentListResponse(
    val items: List<ConsentStatusDto>,
    @SerialName("all_required_agreed") val allRequiredAgreed: Boolean,
)

/** type: TOS|PRIVACY|OVERSEAS|SENSITIVE|MARKETING, method: TOUCH|VOICE */
@Serializable
data class ConsentDecisionDto(val type: String, val version: String, val agreed: Boolean, val method: String)

@Serializable
data class ConsentUpdateRequest(val items: List<ConsentDecisionDto>)

/** §4 GET /allergens/ */
@Serializable
data class AllergenDto(
    val code: String,
    @SerialName("name_ko") val nameKo: String,
    @SerialName("display_order") val displayOrder: Int,
)

/** §4 GET·PUT /me/health/ — diseases: DIABETES|HYPERTENSION, []=없음 */
@Serializable
data class HealthProfileDto(
    val diseases: List<String> = emptyList(),
    val allergens: List<String> = emptyList(),
    @SerialName("other_allergies") val otherAllergies: List<String> = emptyList(),
    val goal: String? = null,
)

/** §11 GET /healthz/ */
@Serializable
data class HealthzDto(val status: String, val db: String? = null)
