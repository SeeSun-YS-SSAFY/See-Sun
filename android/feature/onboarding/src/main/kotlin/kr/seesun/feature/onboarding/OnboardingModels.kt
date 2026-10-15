package kr.seesun.feature.onboarding

import kotlinx.serialization.Serializable
import kr.seesun.core.network.model.AllergenDto
import kr.seesun.core.network.model.ConsentDocumentDto
import kr.seesun.core.network.model.DocumentKinds

/**
 * 온보딩 단계(04 §3.1, 05 §4 순서). 화면 하나가 한 단계다.
 * 시작 → 연령 확인 → 약관 동의 한 화면(의료기기 아님 고지 + 전체 동의 + 약관·개인정보·국외이전 고지·민감정보 행)
 * → 전화번호 → 인증번호
 * → 이름·생년월일·성별·키·몸무게 → 확인 → (민감정보 동의 시) 질환·알레르기 한 번에 말하기(Figma 1031:3673)
 *   [버튼으로 설정]이면 질환·알레르기·기타 → 목표 → 확인 → 가입 완료.
 */
@Serializable
enum class Step(val group: Int) {
    INTRO(1),
    AGE(2),
    AGE_BLOCKED(2),
    CONSENT(3),
    PHONE(4),
    CODE(4),
    NAME(5),
    BIRTHDATE(5),
    GENDER(5),
    HEIGHT(5),
    WEIGHT(5),
    PROFILE_CONFIRM(5),
    HEALTH_VOICE(6),
    DISEASES(6),
    ALLERGENS(6),
    OTHER_ALLERGY(6),
    GOAL(6),
    HEALTH_CONFIRM(6),
    DONE(7);

    companion object {
        const val GROUP_COUNT = 7
    }
}

/**
 * 동의(또는 고지 확인) 한 건의 결정. 서버로 보내기 전까지 기기 메모리에만 있다.
 * [screenId]·[voiceConfirmed]는 입증 정보(법적 검토 C-07)로 함께 보낸다.
 */
@Serializable
data class Decision(
    val version: String,
    val agreed: Boolean,
    val method: String,
    val screenId: String? = null,
    val voiceConfirmed: Boolean? = null,
    val notice: Boolean = false
)

/**
 * 사용자가 입력한 내용. 화면 회전·프로세스 종료 뒤에도 이어 가도록 SavedStateHandle에 JSON으로 둔다(ONB-05).
 * 연령 확인 생년월일은 만 14세 미만이면 바로 지운다(05 §4 1번: 값 비저장).
 */
@Serializable
data class OnboardingForm(
    val step: Step = Step.INTRO,
    /** "이미 가입했어요"로 들어와 전화번호 인증부터 하는 경로. */
    val loginMode: Boolean = false,
    val birthdate: String? = null,
    /** 의료기기 아님 고지(LGL-02)를 본 동의 화면에서 [다음]으로 확인했는가. */
    val noticeAcknowledged: Boolean = false,
    /** 동의 화면 행별 체크(문서 종류 → 체크). 미리 켜 두지 않는다. */
    val consentChecks: Map<String, Boolean> = emptyMap(),
    /** 행별 체크 방법(TOUCH/VOICE). 음성으로 체크한 행은 재확인을 거친 것이다. */
    val consentMethods: Map<String, String> = emptyMap(),
    val decisions: Map<String, Decision> = emptyMap(),
    /** 전화번호 단계에서 이 폰 번호 가져오기 창을 이미 한 번 띄웠는가(자동으로는 한 번만). */
    val phoneHintTried: Boolean = false,
    val phone: String? = null,
    val verificationId: String? = null,
    val signedIn: Boolean = false,
    val isNewUser: Boolean = true,
    val profileCompletedOnServer: Boolean = false,
    val consentsSubmitted: Boolean = false,
    val sensitiveAgreed: Boolean = false,
    val name: String? = null,
    val gender: String? = null,
    val heightCm: Int? = null,
    val weightKg: Int? = null,
    val profileSaved: Boolean = false,
    /** null = 아직 고르지 않음, 빈 집합 = 없음 */
    val diseases: Set<String>? = null,
    val allergens: Set<String> = emptySet(),
    val allergenIndex: Int = 0,
    val otherAllergy: String? = null,
    /** 질환·알레르기를 한 번에 말해 정했는가(목표 화면의 뒤로 가기가 그 화면으로 간다). */
    val healthByVoice: Boolean = false,
    val goal: String? = null,
    val healthSaved: Boolean = false
)

/** 서버에서 읽어 오는 목록의 상태. */
sealed interface Loadable<out T> {
    data object Loading : Loadable<Nothing>

    data class Loaded<T>(val value: T) : Loadable<T>

    data class Failed(val message: String) : Loadable<Nothing>
}

/** 화면이 문자열 리소스로 바꿔 보여 주는 안내. */
enum class Notice {
    REQUIRED_CONSENT,
    BACK_LOCKED,
    BUSY,
    CODE_SENT,
    CODE_AUTO_FILLED,
    CODE_AUTO_TIMEOUT,
    VERIFY_AGAIN,
    NEW_USER_DETECTED,
    WELCOME_BACK,
    ALLERGENS_NONE,
    UNDER_AGE,
    NOTICE_REQUIRED,
    CONSENT_CHECKS_REQUIRED,
    CONSENT_ALL_CHECKED,
    CONSENT_ALL_CLEARED,
    PHONE_HINT_FILLED,
    PHONE_HINT_NONE,
    PHONE_HINT_NOT_MOBILE
}

/** 음성 동의 재확인 질문의 범위. */
enum class VoiceConsentScope { ALL, REQUIRED_ONLY }

data class OnboardingUiState(
    val form: OnboardingForm = OnboardingForm(),
    val documents: Loadable<List<ConsentDocumentDto>> = Loadable.Loading,
    val allergens: Loadable<List<AllergenDto>> = Loadable.Loading,
    /** 서버 요청 중(버튼 두 번 누름 방지·뒤로 가기 보류). */
    val busy: Boolean = false,
    /** 서버·인증 오류 낭독 문구. [retryable]이면 [다시 시도] 버튼을 보인다. */
    val error: String? = null,
    val retryable: Boolean = false,
    val notice: Notice? = null,
    /** 같은 안내를 다시 알릴 때 바꾼다(liveRegion 재낭독). */
    val noticeSeq: Int = 0,
    /** 음성으로 "전체 동의/필수만 동의"라고 해 재확인을 묻는 중(05 §4: 음성 동의는 재확인). null이면 아님. */
    val voiceConsentPending: VoiceConsentScope? = null,
    /** [자세히]로 연 문서 종류(동의 화면 안의 전문 보기). null이면 행 목록. */
    val expandedDocument: String? = null,
    /** 이 폰 번호 가져오기를 쓸 수 있는가(Play 서비스 없음·번호 없음이면 false → 버튼을 숨기고 직접 입력). */
    val phoneHintAvailable: Boolean = true,
    val targetKcal: Int? = null,
    /** 감량 목표가 하한(1,200kcal)에 맞춰 올라갔는가(법적 검토 T-07). */
    val targetFloored: Boolean = false,
    /** 서버가 미성년 연령별 섭취기준(산식 kdri…)으로 목표 열량을 계산했는가(2차 의뢰서 p14). */
    val targetMinorStandard: Boolean = false,
    /** 완료 화면에서 부를 이름(기존 회원은 서버 값). */
    val doneName: String? = null,
    val doneLoaded: Boolean = false,
    val finished: Boolean = false
) {
    val step: Step get() = form.step

    /** 동의 화면에 보여 줄 문서들(서버 순서 = 이용약관·개인정보·국외이전 고지·민감정보). */
    val consentDocuments: List<ConsentDocumentDto>
        get() = (documents as? Loadable.Loaded)?.value.orEmpty()

    /** [자세히]로 연 문서. */
    val detailDocument: ConsentDocumentDto?
        get() = consentDocuments.firstOrNull { it.type == expandedDocument }

    fun isChecked(type: String): Boolean = form.consentChecks[type] == true

    /** 필수 동의와 고지 확인이 모두 체크됐는가([다음]을 누를 수 있는가). */
    val requiredChecked: Boolean
        get() = consentDocuments.isNotEmpty() &&
            consentDocuments.filter { it.required || it.kind == DocumentKinds.NOTICE }.all { isChecked(it.type) }

    val allChecked: Boolean
        get() = consentDocuments.isNotEmpty() && consentDocuments.all { isChecked(it.type) }
}

/** 시작 화면 음성 명령. */
enum class IntroCommand { SETTINGS, SIGN_UP, LOGIN }

/** 시작 화면에서 들은 말 → 명령. 띄어쓰기는 무시한다. 「이미 가입」은 가입보다 먼저 본다. */
object IntroVoiceMatcher {
    fun match(text: String): IntroCommand? {
        val t = text.replace(" ", "")
        return when {
            "설정" in t || "환경" in t -> IntroCommand.SETTINGS
            "로그인" in t || "이미" in t -> IntroCommand.LOGIN
            listOf("시작", "가입", "처음").any { it in t } -> IntroCommand.SIGN_UP
            else -> null
        }
    }
}
