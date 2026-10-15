package kr.seesun.feature.onboarding

import android.app.Activity
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import java.lang.ref.WeakReference
import java.time.Clock
import java.time.LocalDate
import javax.inject.Inject
import kotlin.math.roundToInt
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kr.seesun.core.auth.AuthError
import kr.seesun.core.auth.AuthErrors
import kr.seesun.core.auth.AuthProvider
import kr.seesun.core.auth.AuthResult
import kr.seesun.core.auth.NoPhoneNumberHint
import kr.seesun.core.auth.PhoneNumberHintSource
import kr.seesun.core.auth.PhoneNumbers
import kr.seesun.core.auth.PhoneVerificationEvent
import kr.seesun.core.data.repository.AuthRepository
import kr.seesun.core.data.repository.ConsentRepository
import kr.seesun.core.data.repository.HealthRepository
import kr.seesun.core.data.repository.ProfileRepository
import kr.seesun.core.data.repository.SessionRepository
import kr.seesun.core.network.ApiResult
import kr.seesun.core.network.model.ConsentDecisionDto
import kr.seesun.core.network.model.ConsentMethods
import kr.seesun.core.network.model.ConsentTypes
import kr.seesun.core.network.model.DocumentKinds
import kr.seesun.core.network.model.Goals
import kr.seesun.core.network.model.HealthProfileRequest
import kr.seesun.core.network.model.MePatchRequest
import kr.seesun.core.ui.GuidanceVoice
import kr.seesun.core.ui.HealthVoiceParser
import kr.seesun.core.ui.VoiceCommandController
import kr.seesun.core.ui.VoiceFieldController
import kr.seesun.core.voice.parse.Answer
import kr.seesun.core.voice.parse.AnswerParser
import kr.seesun.core.voice.parse.DateParser
import kr.seesun.core.voice.parse.PhoneNumberParser
import kr.seesun.core.voice.parse.ProfileParsers
import kr.seesun.core.voice.recognition.SpeechInput
import kr.seesun.core.voice.tts.Speaker

/**
 * 온보딩 전체 흐름(LGL-01~06, ONB-01~08, HLT-01~08).
 *
 * - 단계·입력값은 [OnboardingForm]으로 SavedStateHandle에 저장해 회전·프로세스 종료 뒤 같은 단계에서 이어 간다.
 * - 동의는 로그인 전에는 기기에만 두었다가, 전화번호 인증으로 로그인한 직후 서버에 보낸다(POST /me/consents/는 로그인 필요).
 * - 서버 요청 중에는 [OnboardingUiState.busy]로 같은 요청을 다시 보내지 않는다(두 번 누름 방지).
 * - 실패하면 서버 낭독 문구를 보여 주고 [retry]로 같은 단계를 다시 시도한다(오프라인 포함).
 */
@HiltViewModel
@Suppress("TooManyFunctions", "LongParameterList", "LargeClass") // 온보딩 한 흐름(단계 상태 하나)을 한 곳에서 관리한다
class OnboardingViewModel @Inject constructor(
    private val savedState: SavedStateHandle,
    private val authProvider: AuthProvider,
    private val auth: AuthRepository,
    private val consents: ConsentRepository,
    private val profile: ProfileRepository,
    private val health: HealthRepository,
    private val session: SessionRepository,
    private val speech: SpeechInput,
    speaker: Speaker,
    private val clock: Clock,
    /** 이 폰 번호 가져오기(Play 서비스 Phone Number Hint). 화면이 창을 띄우고 결과 번호를 [onPhoneHintNumber]로 넘긴다. */
    val phoneHint: PhoneNumberHintSource = NoPhoneNumberHint
) : ViewModel() {

    private val json = Json { ignoreUnknownKeys = true }
    private val _state = MutableStateFlow(OnboardingUiState(form = restoreForm()))
    val state: StateFlow<OnboardingUiState> = _state.asStateFlow()

    /** 앱 안내 음성(TalkBack 켜짐이면 자동 재생 안 함). */
    val voice = GuidanceVoice(speaker)

    /** 인증번호 자동 수신 경로가 있는가(화면 안내 "문자가 오면 저절로 입력돼요"). */
    val autoRetrieval: Boolean get() = authProvider.supportsAutoRetrieval

    /** 화면이 알려 주는 TalkBack 켜짐 여부(음성 명령 "다시 들려줘" 처리에 쓴다). */
    var talkBackEnabled: Boolean = false

    /** Firebase reCAPTCHA 대체 화면용(가짜 인증은 쓰지 않음). 화면이 붙였다 뗀다. */
    private var activity: WeakReference<Activity>? = null

    /** 전화번호 인증으로 받은 ID 토큰. 비밀값이라 저장하지 않는다(프로세스가 끝나면 다시 인증). */
    private var idToken: String? = null
    private var verification: Job? = null

    private val form: OnboardingForm get() = _state.value.form
    private val today: LocalDate get() = LocalDate.now(clock)

    // ── 입력 칸 ─────────────────────────────────────────

    val ageField = field(::parseBirthdate, ::onAgeEntered)
    val phoneField = field(PhoneNumberParser::parse) { phone ->
        updateForm { copy(phone = phone, verificationId = null) }
        requestCode()
    }
    val codeField = field({ t -> PhoneNumberParser.digits(t).takeIf { it.length == CODE_LENGTH } }, ::verifyCode)
    val nameField = field(ProfileParsers::name) { name ->
        updateForm { copy(name = name) }
        goTo(Step.BIRTHDATE)
    }
    val birthField = field(::parseBirthdate, ::onProfileBirthdate)
    val heightField = field({ t -> ProfileParsers.heightCm(t)?.roundToInt()?.toString() }) { v ->
        updateForm { copy(heightCm = v.toInt()) }
        goTo(Step.WEIGHT)
    }
    val weightField = field({ t -> ProfileParsers.weightKg(t)?.roundToInt()?.takeIf { it in WEIGHT_RANGE }?.toString() }) { v ->
        updateForm { copy(weightKg = v.toInt()) }
        goTo(Step.PROFILE_CONFIRM)
    }
    val otherAllergyField = field({ t -> t.trim().takeIf { it.length in 1..OTHER_MAX } }) { v ->
        updateForm { copy(otherAllergy = v) }
        goTo(Step.GOAL)
    }

    // ── 음성 명령(선택 화면) ────────────────────────────

    val consentVoice = command(::onConsentVoice)
    val genderVoice = command { c ->
        c.firstNotNullOfOrNull(ProfileParsers::gender)?.let { selectGender(it.name) } != null
    }
    val diseaseVoice = command { c ->
        c.firstNotNullOfOrNull(HealthVoiceParser::diseases)?.let { updateForm { copy(diseases = it) } } != null
    }
    val allergenVoice = command { c ->
        val names = (state.value.allergens as? Loadable.Loaded)?.value.orEmpty().associate { it.code to it.nameKo }
        c.firstNotNullOfOrNull { HealthVoiceParser.allergens(it, names) }?.let { found ->
            updateForm { copy(allergens = if (found.isEmpty()) emptySet() else allergens + found) }
            if (found.isEmpty()) notify(Notice.ALLERGENS_NONE)
        } != null
    }
    val goalVoice = command { c -> c.firstNotNullOfOrNull(HealthVoiceParser::goal)?.let(::selectGoal) != null }

    /** 시작 화면 [말로 고르기]: "설정"(톱니바퀴와 같음), "시작·가입", "로그인·이미 가입" (소유자 msg 959). */
    val introVoice = command { c ->
        when (c.firstNotNullOfOrNull(IntroVoiceMatcher::match)) {
            IntroCommand.SETTINGS -> _settingsRequests.update { it + 1 }
            IntroCommand.SIGN_UP -> startSignUp()
            IntroCommand.LOGIN -> startLogin()
            null -> return@command false
        }
        true
    }

    private val _settingsRequests = MutableStateFlow(0)

    /** 말로 "설정"이라고 했다(바뀔 때마다 화면이 설정을 연다). */
    val settingsRequests: StateFlow<Int> = _settingsRequests.asStateFlow()

    init {
        syncFields()
        when (form.step) {
            Step.CONSENT -> loadDocuments()
            Step.ALLERGENS -> loadAllergens()
            Step.DONE -> loadDone()
            else -> Unit
        }
        // 로그인 상태(토큰)가 사라졌으면(프로세스 종료 중 만료 등) 로그인 이후 단계는 다시 인증부터.
        if (form.signedIn && !auth.signedIn.value) updateForm { copy(signedIn = false, consentsSubmitted = false, step = Step.PHONE) }
    }

    fun bindActivity(activity: Activity?) {
        this.activity = activity?.let(::WeakReference)
    }

    // ── 시작·연령·동의 ──────────────────────────────────

    fun startSignUp() {
        updateForm { copy(loginMode = false) }
        goTo(Step.AGE)
    }

    fun startLogin() {
        updateForm { copy(loginMode = true) }
        goTo(Step.PHONE)
        loadDocuments()
    }

    private fun onAgeEntered(birth: String) {
        if (DateParser.ageOn(LocalDate.parse(birth), today) < MIN_AGE) {
            blockUnderAge()
        } else {
            updateForm { copy(birthdate = birth) }
            goTo(Step.CONSENT)
            loadDocuments()
        }
    }

    /** 만 14세 미만: 가입을 막고 입력한 생년월일을 지운다(저장하지 않음). */
    private fun blockUnderAge() {
        ageField.setValue(null)
        birthField.setValue(null)
        updateForm { copy(birthdate = null) }
        goTo(Step.AGE_BLOCKED)
    }

    fun retryAge() = goTo(Step.AGE)

    fun loadDocuments() {
        if (state.value.documents is Loadable.Loaded) return
        _state.update { it.copy(documents = Loadable.Loading) }
        viewModelScope.launch {
            val loaded = when (val r = consents.documents()) {
                is ApiResult.Success -> Loadable.Loaded(
                    r.data.filter {
                        it.type in ConsentTypes.ORDER
                    }.sortedBy { ConsentTypes.ORDER.indexOf(it.type) }
                )

                is ApiResult.Failure -> Loadable.Failed(r.failure.ttsMessage)
            }
            _state.update { it.copy(documents = loaded) }
        }
    }

    // ── 약관 동의 한 화면(소유자 피드백 2026-10-13: 동의 단계를 한 화면으로) ──

    /** 행 하나 체크/해제(터치). 고지 행은 "확인함"으로 체크한다. */
    fun toggleConsent(type: String) {
        if (state.value.busy) return notify(Notice.BUSY)
        val on = !state.value.isChecked(type)
        updateForm {
            copy(consentChecks = consentChecks + (type to on), consentMethods = consentMethods + (type to ConsentMethods.TOUCH))
        }
        _state.update { it.copy(voiceConsentPending = null) }
        clearMessages()
    }

    /**
     * [전체 동의]: 하나라도 꺼져 있으면 모두 켜고, 모두 켜져 있으면 모두 끈다. 민감정보(선택) 행도 따로 체크된 것이 보이고,
     * 결과 안내(liveRegion·음성)에 포함 항목을 하나씩 말한다(05 §4: 전체 동의는 포함 항목을 알림).
     */
    fun toggleAllConsents() {
        if (state.value.busy) return notify(Notice.BUSY)
        val types = state.value.consentDocuments.map { it.type }
        if (types.isEmpty()) return
        val on = !state.value.allChecked
        updateForm { copy(consentChecks = types.associateWith { on }, consentMethods = types.associateWith { ConsentMethods.TOUCH }) }
        _state.update { it.copy(voiceConsentPending = null) }
        notify(if (on) Notice.CONSENT_ALL_CHECKED else Notice.CONSENT_ALL_CLEARED)
    }

    /** [자세히]: 문서 전문을 화면 안에서 펼친다. TalkBack이 꺼져 있으면 앱 음성으로도 읽는다. */
    fun openConsentDetail(type: String, text: String, talkBackEnabled: Boolean) {
        _state.update { it.copy(expandedDocument = type, voiceConsentPending = null) }
        voice.onDemand(text, talkBackEnabled)
    }

    fun closeConsentDetail() {
        voice.stop()
        _state.update { it.copy(expandedDocument = null) }
    }

    /**
     * 동의 화면의 [다음]: 필수 동의와 고지 확인이 모두 체크돼야 넘어간다. 선택 항목은 체크하지 않으면 "동의 안 함"으로 기록한다.
     * 기록(문서 버전·방법·화면 ID·음성 재확인)은 로그인 직후 서버로 보낸다(API 계약 그대로).
     */
    fun consentNext() {
        val st = state.value
        if (st.busy) return notify(Notice.BUSY)
        val docs = st.consentDocuments
        if (docs.isEmpty()) return
        if (!st.requiredChecked) return notify(Notice.CONSENT_CHECKS_REQUIRED)
        val decisions = docs.associate { doc ->
            val agreed = st.isChecked(doc.type)
            val method = form.consentMethods[doc.type] ?: ConsentMethods.TOUCH
            doc.type to Decision(
                version = doc.version,
                agreed = agreed,
                method = method,
                screenId = CONSENT_SCREEN_ID,
                voiceConfirmed = if (agreed && method == ConsentMethods.VOICE) true else null,
                notice = doc.kind == DocumentKinds.NOTICE
            )
        }
        updateForm { copy(decisions = decisions, noticeAcknowledged = true) }
        _state.update { it.copy(voiceConsentPending = null, expandedDocument = null) }
        clearMessages()
        consentsDone()
    }

    /** 음성 동의 재확인 질문에 대한 [취소]. */
    fun cancelVoiceConsent() = _state.update { it.copy(voiceConsentPending = null) }

    /** 음성 동의 재확인 [동의 확인]: 말한 범위대로 체크하고(VOICE) 바로 다음으로 간다. */
    fun confirmVoiceConsent() {
        val scope = state.value.voiceConsentPending ?: return
        val docs = state.value.consentDocuments
        val checks = docs.associate { doc ->
            doc.type to (scope == VoiceConsentScope.ALL || doc.required || doc.kind == DocumentKinds.NOTICE)
        }
        updateForm { copy(consentChecks = checks, consentMethods = docs.associate { it.type to ConsentMethods.VOICE }) }
        _state.update { it.copy(voiceConsentPending = null) }
        consentNext()
    }

    /**
     * 동의 화면 [말로 동의하기]: "전체 동의" / "필수만 동의" → 재확인 질문 → "네" → 기록(VOICE).
     * 재확인 중 "아니요"는 취소, "다시 들려줘"는 항목 요약을 다시 읽는다.
     */
    private fun onConsentVoice(candidates: List<String>): Boolean {
        val pending = state.value.voiceConsentPending
        val text = candidates.firstOrNull().orEmpty().replace(" ", "")
        val requiredOnly = listOf("필수만", "필수항목만", "필수동의").any { text.contains(it) }
        val saysAgree = requiredOnly || (text.contains("동의") && listOf("동의안", "동의하지않").none { text.contains(it) })
        val answer = when {
            listOf("동의안", "동의하지않", "거부", "싫").any { text.contains(it) } -> Answer.NO
            saysAgree -> Answer.YES
            else -> candidates.firstNotNullOfOrNull { AnswerParser.parse(it) }
        }
        when (answer) {
            Answer.YES -> {
                val scope = if (requiredOnly) VoiceConsentScope.REQUIRED_ONLY else VoiceConsentScope.ALL
                // 재확인 중 "네/동의"는 확정, 범위를 바꿔 말하면("필수만") 그 범위로 다시 묻는다.
                if (pending != null && (scope == pending || !requiredOnly)) {
                    confirmVoiceConsent()
                } else {
                    _state.update { it.copy(voiceConsentPending = scope, expandedDocument = null) }
                }
            }

            Answer.NO -> if (pending != null) cancelVoiceConsent() else notify(Notice.CONSENT_CHECKS_REQUIRED)

            Answer.REPEAT -> voice.onDemand(consentSummaryForVoice(), talkBackEnabled)

            Answer.EDIT, null -> return false
        }
        return true
    }

    /** "다시 들려줘": 문서 제목·요약을 이어 읽는다. */
    private fun consentSummaryForVoice(): String = state.value.consentDocuments.joinToString(" ") { "${it.title}. ${it.summary.orEmpty()}" }

    private fun consentsDone() {
        when {
            form.signedIn -> submitConsentsAndRoute()
            idToken != null -> finishAuth()
            else -> goTo(Step.PHONE)
        }
    }

    // ── 전화번호 인증 ───────────────────────────────────

    /** 이 폰 번호 가져오기 창을 띄웠다(자동으로는 전화번호 단계에 처음 들어올 때 한 번만). */
    fun markPhoneHintTried() = updateForm { copy(phoneHintTried = true) }

    /** 창을 띄울 수 없다(Play 서비스 없음·번호 없음): 버튼을 숨기고 말·키보드 입력을 안내한다. */
    fun onPhoneHintUnavailable() {
        _state.update { it.copy(phoneHintAvailable = false) }
        notify(Notice.PHONE_HINT_NONE)
    }

    /**
     * 창 결과. 번호를 고르면 국내 형식으로 바꿔 칸에 넣고(인증번호는 사용자가 [인증번호 받기]로 요청),
     * 취소했거나 한국 휴대폰 번호가 아니면 칸을 그대로 두고 직접 입력을 안내한다.
     */
    fun onPhoneHintNumber(raw: String?) {
        if (raw.isNullOrBlank()) return notify(Notice.PHONE_HINT_NONE)
        val domestic = PhoneNumbers.domesticFromHint(raw) ?: return notify(Notice.PHONE_HINT_NOT_MOBILE)
        updateForm { copy(phone = domestic) }
        phoneField.setValue(domestic)
        notify(Notice.PHONE_HINT_FILLED)
    }

    /**
     * [인증번호 받기]·[인증번호 다시 받기]. 인증번호 화면에서 다시 받으면 Firebase 재전송 토큰을 쓴다.
     * 첫 응답(보냄·자동 인증·오류)이 [FIRST_RESPONSE_TIMEOUT_MS] 안에 오지 않으면(reCAPTCHA 화면에서 멈춤·네트워크) 멈추고
     * 다시 시도를 안내한다 — 「처리 중」에 갇히지 않게.
     */
    fun requestCode() {
        val phone = form.phone ?: return
        if (state.value.busy) return notify(Notice.BUSY)
        val e164 = PhoneNumbers.toE164(phone) ?: return showError(AuthError.INVALID_PHONE.message, retryable = false)
        val resend = form.step == Step.CODE && form.verificationId != null
        setBusy(true)
        verification?.cancel()
        verification = viewModelScope.launch {
            var answered = false
            val watchdog = launch {
                delay(FIRST_RESPONSE_TIMEOUT_MS)
                if (!answered) {
                    setBusy(false)
                    showError(AuthErrors.describe(AuthError.TIMEOUT, "TIMEOUT"), retryable = true)
                    verification?.cancel()
                }
            }
            authProvider.startPhoneVerification(e164, activity?.get(), resend).collect { event ->
                answered = true
                watchdog.cancel()
                onVerificationEvent(event)
            }
        }
    }

    private fun onVerificationEvent(event: PhoneVerificationEvent) {
        when (event) {
            is PhoneVerificationEvent.CodeSent -> {
                updateForm { copy(verificationId = event.verificationId) }
                setBusy(false)
                if (form.step != Step.CODE) goTo(Step.CODE)
                codeField.setValue(null)
                notify(Notice.CODE_SENT)
            }

            is PhoneVerificationEvent.CodeAutoRetrieved -> {
                updateForm { copy(verificationId = event.verificationId) }
                if (form.step != Step.CODE) goTo(Step.CODE)
                codeField.setValue(event.smsCode)
                notify(Notice.CODE_AUTO_FILLED)
                setBusy(false)
                verifyCode(event.smsCode)
            }

            is PhoneVerificationEvent.AutoRetrievalTimedOut -> {
                // 자동 입력만 끝났다. 보낸 인증번호는 그대로 쓸 수 있다(이미 입력 중이면 방해하지 않음).
                if (form.step == Step.CODE && idToken == null && !state.value.busy) notify(Notice.CODE_AUTO_TIMEOUT)
            }

            is PhoneVerificationEvent.Verified -> {
                idToken = event.result.idToken
                finishAuth()
            }

            is PhoneVerificationEvent.Failed -> {
                setBusy(false)
                showError(AuthErrors.describe(event.error, event.code), retryable = event.error in RETRYABLE_AUTH_ERRORS)
            }
        }
    }

    private fun verifyCode(code: String) {
        val id = form.verificationId ?: return requestCode()
        if (state.value.busy || idToken != null) return
        setBusy(true)
        viewModelScope.launch {
            when (val result = authProvider.confirmCode(id, code)) {
                is AuthResult.Success -> {
                    idToken = result.idToken
                    finishAuth()
                }

                is AuthResult.Failure -> {
                    setBusy(false)
                    codeField.setValue(null)
                    showError(AuthErrors.describe(result.error, result.code), retryable = result.error in RETRYABLE_AUTH_ERRORS)
                }
            }
        }
    }

    /** 인증 후: 토큰 교환(신규면 생년월일과 함께) → 동의 제출 → 다음 단계. 실패하면 [retry]로 이어서 한다. */
    private fun finishAuth() {
        setBusy(true)
        viewModelScope.launch {
            if (!form.signedIn && !signIn()) return@launch
            submitConsentsAndRoute()
        }
    }

    /** @return 다음으로 진행해도 되면 true */
    private suspend fun signIn(): Boolean {
        val token = idToken ?: run {
            setBusy(false)
            goTo(Step.PHONE)
            notify(Notice.VERIFY_AGAIN)
            return false
        }
        val result = auth.signIn(token, form.birthdate?.let(LocalDate::parse), null)
        if (result is ApiResult.Failure) {
            setBusy(false)
            when (result.failure.code) {
                AGE_RESTRICTED -> blockUnderAge()

                BIRTHDATE_REQUIRED -> {
                    // "이미 가입했어요"로 왔지만 처음 가입하는 번호: 연령 확인·동의부터 받는다.
                    updateForm { copy(loginMode = false) }
                    goTo(Step.AGE)
                    notify(Notice.NEW_USER_DETECTED)
                }

                else -> showError(result.failure.ttsMessage, retryable = true)
            }
            return false
        }
        val r = (result as ApiResult.Success).data
        updateForm {
            copy(
                signedIn = true,
                isNewUser = r.isNewUser,
                profileCompletedOnServer = r.profileCompleted,
                sensitiveAgreed = r.sensitiveConsent,
                consentsSubmitted = consentsSubmitted || (decisions.isEmpty() && r.consentsRequired.isEmpty())
            )
        }
        if (form.loginMode && !r.isNewUser && form.decisions.isEmpty()) {
            if (r.consentsRequired.isEmpty()) {
                route()
            } else {
                // 문서가 바뀌었거나 필수 동의가 빠진 기존 회원: 동의를 다시 받는다.
                updateForm { copy(consentChecks = emptyMap(), consentMethods = emptyMap()) }
                goTo(Step.CONSENT)
                notify(Notice.WELCOME_BACK)
            }
            setBusy(false)
            return false
        }
        return true
    }

    private fun submitConsentsAndRoute() {
        setBusy(true)
        viewModelScope.launch {
            if (!form.consentsSubmitted) {
                val items = form.decisions.map { (type, d) ->
                    ConsentDecisionDto(type, d.version, d.agreed, d.method, screenId = d.screenId, voiceConfirmed = d.voiceConfirmed)
                }
                when (val r = consents.submit(items)) {
                    is ApiResult.Failure -> {
                        setBusy(false)
                        if (r.failure.code == VERSION_MISMATCH) {
                            // 그사이 문서가 바뀌었다: 새 문서를 받아 처음부터 다시 동의받는다.
                            _state.update { it.copy(documents = Loadable.Loading) }
                            updateForm { copy(decisions = emptyMap(), consentChecks = emptyMap(), consentMethods = emptyMap()) }
                            goTo(Step.CONSENT)
                            loadDocuments()
                            showError(r.failure.ttsMessage, retryable = false)
                        } else {
                            showError(r.failure.ttsMessage, retryable = true)
                        }
                        return@launch
                    }

                    is ApiResult.Success -> updateForm {
                        copy(
                            consentsSubmitted = true,
                            sensitiveAgreed = r.data.items.any { it.type == ConsentTypes.SENSITIVE && it.agreed }
                        )
                    }
                }
            }
            setBusy(false)
            route()
        }
    }

    /** 로그인·동의 뒤 갈 곳: 프로필을 이미 마친 기존 회원은 완료 요약으로(ONB-06), 아니면 이름부터. */
    private fun route() {
        if (!form.isNewUser && form.profileCompletedOnServer) {
            goTo(Step.DONE)
            loadDone()
        } else {
            goTo(Step.NAME)
        }
    }

    // ── 프로필 ─────────────────────────────────────────

    private fun onProfileBirthdate(birth: String) {
        if (DateParser.ageOn(LocalDate.parse(birth), today) < MIN_AGE) {
            birthField.setValue(form.birthdate)
            return notify(Notice.UNDER_AGE)
        }
        updateForm { copy(birthdate = birth) }
        goTo(Step.GENDER)
    }

    fun selectGender(code: String) {
        updateForm { copy(gender = code) }
    }

    fun genderNext() {
        if (form.gender != null) goTo(Step.HEIGHT)
    }

    fun editProfile() = goTo(Step.NAME)

    fun saveProfile() {
        if (state.value.busy) return notify(Notice.BUSY)
        setBusy(true)
        viewModelScope.launch {
            val patch = MePatchRequest(
                name = form.name,
                birthdate = form.birthdate,
                gender = form.gender,
                heightCm = form.heightCm,
                weightKg = form.weightKg
            )
            when (val r = profile.update(patch)) {
                is ApiResult.Failure -> {
                    setBusy(false)
                    showError(r.failure.ttsMessage, retryable = true)
                }

                is ApiResult.Success -> {
                    setBusy(false)
                    updateForm { copy(profileSaved = true) }
                    if (form.sensitiveAgreed) {
                        goTo(Step.DISEASES)
                        loadAllergens()
                    } else {
                        goTo(Step.GOAL)
                    }
                }
            }
        }
    }

    // ── 건강 ───────────────────────────────────────────

    fun toggleDisease(code: String) = updateForm {
        val now = diseases.orEmpty()
        copy(diseases = if (code in now) now - code else now + code)
    }

    fun selectNoDisease() = updateForm { copy(diseases = emptySet()) }

    fun diseasesNext() {
        if (form.diseases != null) {
            goTo(Step.ALLERGENS)
            loadAllergens()
        }
    }

    fun loadAllergens() {
        if (state.value.allergens is Loadable.Loaded) return
        _state.update { it.copy(allergens = Loadable.Loading) }
        viewModelScope.launch {
            val loaded = when (val r = health.allergens()) {
                is ApiResult.Success -> Loadable.Loaded(r.data)
                is ApiResult.Failure -> Loadable.Failed(r.failure.ttsMessage)
            }
            _state.update { it.copy(allergens = loaded) }
        }
    }

    fun toggleAllergen(code: String) = updateForm { copy(allergens = if (code in allergens) allergens - code else allergens + code) }

    fun setAllergenIndex(index: Int) = updateForm { copy(allergenIndex = index) }

    fun noAllergens() {
        updateForm { copy(allergens = emptySet()) }
        notify(Notice.ALLERGENS_NONE)
    }

    fun allergensNext() = goTo(Step.OTHER_ALLERGY)

    fun skipOtherAllergy() {
        otherAllergyField.setValue(null)
        updateForm { copy(otherAllergy = null) }
        goTo(Step.GOAL)
    }

    fun selectGoal(code: String) = updateForm { copy(goal = code) }

    fun goalNext() {
        if (form.goal != null) goTo(Step.HEALTH_CONFIRM)
    }

    fun editHealth() = goTo(if (form.sensitiveAgreed) Step.DISEASES else Step.GOAL)

    fun saveHealth() {
        if (state.value.busy) return notify(Notice.BUSY)
        setBusy(true)
        viewModelScope.launch {
            val result = if (form.sensitiveAgreed) {
                health.save(
                    HealthProfileRequest(
                        diseases = form.diseases.orEmpty().sorted(),
                        allergens = form.allergens.sorted(),
                        otherAllergies = form.otherAllergy?.split(',', '、')?.map(String::trim)?.filter(String::isNotEmpty).orEmpty(),
                        goal = form.goal
                    )
                )
            } else {
                profile.update(MePatchRequest(goal = form.goal))
            }
            setBusy(false)
            when (result) {
                is ApiResult.Failure -> showError(result.failure.ttsMessage, retryable = true)

                is ApiResult.Success -> {
                    updateForm { copy(healthSaved = true) }
                    goTo(Step.DONE)
                    loadDone()
                }
            }
        }
    }

    // ── 완료 ───────────────────────────────────────────

    private fun loadDone() {
        _state.update { it.copy(doneLoaded = false) }
        viewModelScope.launch {
            val me = profile.me()
            val data = (me as? ApiResult.Success)?.data
            val detail = data?.targetKcalDetail
            val raw = detail?.let { d -> d.tdee?.plus(d.adjustment ?: 0)?.roundToInt() }
            val target = data?.targetKcal
            val floored = data?.goal == Goals.LOSE_WEIGHT && raw != null && target != null && raw < target
            _state.update {
                it.copy(targetKcal = data?.targetKcal, targetFloored = floored, doneName = data?.name ?: form.name, doneLoaded = true)
            }
        }
    }

    fun finish() {
        if (state.value.finished) return
        session.markOnboardingCompleted()
        voice.stop()
        _state.update { it.copy(finished = true) }
    }

    // ── 공통 ───────────────────────────────────────────

    /** 실패한 요청을 다시 보낸다(현재 단계 기준). */
    fun retry() {
        clearMessages()
        when (form.step) {
            Step.CONSENT -> if (state.value.documents is Loadable.Failed) loadDocuments() else consentsDone()
            Step.CODE, Step.PHONE -> if (idToken != null) finishAuth() else requestCode()
            Step.PROFILE_CONFIRM -> saveProfile()
            Step.ALLERGENS -> loadAllergens()
            Step.HEALTH_CONFIRM -> saveHealth()
            Step.DONE -> loadDone()
            else -> Unit
        }
    }

    /**
     * 뒤로 가기(화면 [뒤로 가기]와 시스템 뒤로). 처리했으면 true.
     * 로그인 이후에는 연령·동의·인증 단계로 돌아갈 수 없다(이미 서버에 반영) → 안내만 한다.
     */
    fun back(): Boolean {
        if (state.value.busy) {
            notify(Notice.BUSY)
            return true
        }
        val f = form
        if (f.step == Step.INTRO) return false
        if (f.step == Step.CONSENT && (state.value.expandedDocument != null || state.value.voiceConsentPending != null)) {
            // [자세히]·음성 재확인 중이면 행 목록으로 돌아간다.
            closeConsentDetail()
            cancelVoiceConsent()
            return true
        }
        val previous: Step? = previousStep(f)
        if (previous == null) {
            notify(Notice.BACK_LOCKED)
        } else {
            if (f.step == Step.CODE) verification?.cancel()
            goTo(previous)
            if (previous == Step.CONSENT) loadDocuments()
        }
        return true
    }

    /** 이전 단계. null이면 잠김(로그인 이후 연령·동의·인증 단계, 완료 화면). */
    private fun previousStep(f: OnboardingForm): Step? = when (f.step) {
        Step.AGE -> Step.INTRO
        Step.CONSENT -> if (f.signedIn) null else Step.AGE
        Step.PHONE -> if (f.loginMode) Step.INTRO else Step.CONSENT
        Step.GOAL -> if (f.sensitiveAgreed) Step.OTHER_ALLERGY else Step.PROFILE_CONFIRM
        else -> LINEAR_BACK[f.step]
    }

    /** 이 단계에서 [뒤로 가기] 버튼을 보일지(로그인 이후 잠긴 단계는 숨긴다). */
    fun canGoBack(f: OnboardingForm): Boolean = when (f.step) {
        Step.INTRO, Step.NAME, Step.DONE -> false
        Step.CONSENT -> !f.signedIn
        else -> true
    }

    private fun goTo(step: Step) {
        voice.stop()
        updateForm { copy(step = step) }
        _state.update { it.copy(voiceConsentPending = null, expandedDocument = null, error = null, retryable = false, notice = null) }
        syncFields()
    }

    /** 단계에 들어올 때 입력 칸을 저장된 값으로 채운다(이전 단계로 돌아와도 값이 남아 있게). */
    private fun syncFields() {
        val f = form
        when (f.step) {
            Step.AGE -> ageField.setValue(f.birthdate)
            Step.PHONE -> phoneField.setValue(f.phone)
            Step.NAME -> nameField.setValue(f.name)
            Step.BIRTHDATE -> birthField.setValue(f.birthdate)
            Step.HEIGHT -> heightField.setValue(f.heightCm?.toString())
            Step.WEIGHT -> weightField.setValue(f.weightKg?.toString())
            Step.OTHER_ALLERGY -> otherAllergyField.setValue(f.otherAllergy)
            else -> Unit
        }
    }

    private fun clearMessages() = _state.update { it.copy(error = null, retryable = false, notice = null) }

    private fun notify(notice: Notice) = _state.update { it.copy(notice = notice, noticeSeq = it.noticeSeq + 1) }

    private fun showError(message: String, retryable: Boolean) =
        _state.update { it.copy(error = message, retryable = retryable, noticeSeq = it.noticeSeq + 1) }

    private fun setBusy(busy: Boolean) = _state.update { it.copy(busy = busy, error = if (busy) null else it.error) }

    private fun updateForm(transform: OnboardingForm.() -> OnboardingForm) {
        _state.update { it.copy(form = it.form.transform()) }
        savedState[KEY_FORM] = json.encodeToString(OnboardingForm.serializer(), form)
    }

    private fun restoreForm(): OnboardingForm = savedState.get<String>(KEY_FORM)
        ?.let { runCatching { json.decodeFromString(OnboardingForm.serializer(), it) }.getOrNull() }
        ?: OnboardingForm()

    private fun parseBirthdate(text: String): String? =
        DateParser.parse(text, today)?.takeIf { !it.isAfter(today) && it.year >= MIN_BIRTH_YEAR }?.toString()

    private fun field(parser: (String) -> String?, onCommit: (String) -> Unit) =
        VoiceFieldController(viewModelScope, speech, parser, onBeforeListen = { voice.stop() }, onCommit = onCommit)

    private fun command(handler: (List<String>) -> Boolean) =
        VoiceCommandController(viewModelScope, speech, onBeforeListen = { voice.stop() }, handler = handler)

    companion object {
        const val KEY_FORM = "onboarding.form"

        /** 동의 기록에 남길 화면 ID: 약관·개인정보·국외이전 고지·민감정보를 한 화면에서 받는다(04 문서 LGL-03 통합, 2026-10-13). */
        const val CONSENT_SCREEN_ID = "LGL-03"

        /** 앞 단계가 하나로 정해진 단계들(INTRO·NAME·DONE은 없음 = 잠김). */
        private val LINEAR_BACK = mapOf(
            Step.AGE_BLOCKED to Step.AGE,
            Step.CODE to Step.PHONE,
            Step.BIRTHDATE to Step.NAME,
            Step.GENDER to Step.BIRTHDATE,
            Step.HEIGHT to Step.GENDER,
            Step.WEIGHT to Step.HEIGHT,
            Step.PROFILE_CONFIRM to Step.WEIGHT,
            Step.DISEASES to Step.PROFILE_CONFIRM,
            Step.ALLERGENS to Step.DISEASES,
            Step.OTHER_ALLERGY to Step.ALLERGENS,
            Step.HEALTH_CONFIRM to Step.GOAL
        )
        const val MIN_AGE = 14
        private const val MIN_BIRTH_YEAR = 1900
        private const val CODE_LENGTH = 6
        private const val OTHER_MAX = 50
        private val WEIGHT_RANGE = 20..300
        private const val AGE_RESTRICTED = "AGE_RESTRICTED"
        private const val BIRTHDATE_REQUIRED = "BIRTHDATE_REQUIRED"
        private const val VERSION_MISMATCH = "VERSION_MISMATCH"

        /** 인증번호 요청 뒤 첫 응답을 기다리는 시간. reCAPTCHA(브라우저) 확인까지 넉넉히. */
        const val FIRST_RESPONSE_TIMEOUT_MS = 90_000L

        /** 같은 단계에서 [다시 시도]를 보일 인증 오류(일시적인 것). 한도·지역·형식 오류는 다시 눌러도 같다. */
        private val RETRYABLE_AUTH_ERRORS = setOf(AuthError.NETWORK, AuthError.TIMEOUT, AuthError.RECAPTCHA_CANCELLED, AuthError.UNKNOWN)
    }
}
