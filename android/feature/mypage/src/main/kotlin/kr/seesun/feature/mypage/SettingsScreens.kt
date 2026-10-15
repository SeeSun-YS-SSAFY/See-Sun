package kr.seesun.feature.mypage

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kr.seesun.core.a11y.rememberTalkBackEnabled
import kr.seesun.core.data.repository.ConsentRepository
import kr.seesun.core.data.repository.SessionRepository
import kr.seesun.core.data.repository.UnsentRecords
import kr.seesun.core.designsystem.component.BodyText
import kr.seesun.core.designsystem.component.BottomActions
import kr.seesun.core.designsystem.component.ButtonKind
import kr.seesun.core.designsystem.component.Clause
import kr.seesun.core.designsystem.component.ClauseAccordion
import kr.seesun.core.designsystem.component.ConfirmContent
import kr.seesun.core.designsystem.component.ConfirmScreenContent
import kr.seesun.core.designsystem.component.ListButtonGrid
import kr.seesun.core.designsystem.component.ListButtonItem
import kr.seesun.core.designsystem.component.ReadingBlock
import kr.seesun.core.designsystem.component.ScreenScaffold
import kr.seesun.core.designsystem.component.SeeSunButton
import kr.seesun.core.designsystem.theme.SeeSunTheme
import kr.seesun.core.network.ApiResult
import kr.seesun.core.network.model.ConsentDocumentDto
import kr.seesun.core.ui.AutoGuidance
import kr.seesun.core.ui.GuidanceVoice
import kr.seesun.core.ui.VoiceCommandButton
import kr.seesun.core.ui.VoiceCommandController
import kr.seesun.core.voice.recognition.SpeechInput
import kr.seesun.core.voice.tts.Speaker

// ── SET-01 설정 목록 · 로그아웃 ─────────────────────────

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val session: SessionRepository,
    unsent: UnsentRecords,
    speech: SpeechInput,
    speaker: Speaker
) : ViewModel() {
    val voice = GuidanceVoice(speaker)

    private val _voiceTarget = MutableStateFlow<Any?>(null)

    /** 말로 고른 메뉴(화면이 이동한 뒤 [consumeVoiceTarget]으로 비운다). */
    val voiceTarget: StateFlow<Any?> = _voiceTarget.asStateFlow()

    /** [말로 고르기]: "탈퇴", "동의 철회", "데이터 초기화" 같은 말로 메뉴를 연다(법적 검토 A-01·A-04). */
    val voiceCommand = VoiceCommandController(viewModelScope, speech, onBeforeListen = { voice.stop() }) { candidates ->
        val target = candidates.firstNotNullOfOrNull(SettingsVoiceMatcher::match)
            ?.takeIf { SettingsItem.allowsVoiceTarget(it, session.state.value.signedIn) }
        if (target == SettingsItem.LOGOUT) {
            askLogout()
        } else if (target != null) {
            _voiceTarget.value = target
        }
        target != null
    }

    /** 로그인했는가(아니면 시작 화면 톱니바퀴로 온 로그인 전 설정: 계정 항목을 숨긴다). */
    val signedIn: StateFlow<Boolean> = session.state.map { it.signedIn }
        .stateIn(viewModelScope, SharingStarted.Eagerly, session.state.value.signedIn)

    fun consumeVoiceTarget() {
        _voiceTarget.value = null
    }

    /** 아직 서버로 보내지 못한 기록(식사 + 운동, 로그아웃하면 지워지므로 미리 알린다). */
    val pending: StateFlow<Int> = unsent.observeCount().stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_MS), 0)

    private val _confirmingLogout = MutableStateFlow(false)
    val confirmingLogout: StateFlow<Boolean> = _confirmingLogout.asStateFlow()
    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    fun askLogout() {
        _confirmingLogout.value = true
    }

    fun cancelLogout() {
        _confirmingLogout.value = false
    }

    fun logout(onDone: () -> Unit) {
        if (_busy.value) return
        _busy.value = true
        viewModelScope.launch {
            session.logout()
            _busy.value = false
            _confirmingLogout.value = false
            onDone()
        }
    }

    private companion object {
        const val STOP_MS = 5_000L
    }
}

/** 설정에서 여는 화면. */
@Composable
fun SettingsRoute(onBack: () -> Unit, onOpen: (Any) -> Unit, onSignedOut: () -> Unit, viewModel: SettingsViewModel = hiltViewModel()) {
    val confirming by viewModel.confirmingLogout.collectAsStateWithLifecycle()
    val pending by viewModel.pending.collectAsStateWithLifecycle()
    val busy by viewModel.busy.collectAsStateWithLifecycle()
    val voiceTarget by viewModel.voiceTarget.collectAsStateWithLifecycle()
    val signedIn by viewModel.signedIn.collectAsStateWithLifecycle()
    LaunchedEffect(voiceTarget) {
        voiceTarget?.let {
            viewModel.consumeVoiceTarget()
            onOpen(it)
        }
    }
    BackHandler(enabled = confirming) { viewModel.cancelLogout() }
    val talkBack = rememberTalkBackEnabled()
    SettingsScreen(
        confirmingLogout = confirming,
        pending = pending,
        busy = busy,
        onBack = if (confirming) viewModel::cancelLogout else onBack,
        onOpen = onOpen,
        onAskLogout = viewModel::askLogout,
        onLogout = { viewModel.logout(onSignedOut) },
        onCancelLogout = viewModel::cancelLogout,
        talkBackEnabled = talkBack,
        speak = { viewModel.voice.auto(it, talkBack) },
        voiceCommand = viewModel.voiceCommand,
        signedIn = signedIn
    )
}

@Composable
fun SettingsScreen(
    confirmingLogout: Boolean,
    pending: Int,
    busy: Boolean,
    onBack: () -> Unit,
    onOpen: (Any) -> Unit,
    onAskLogout: () -> Unit,
    onLogout: () -> Unit,
    onCancelLogout: () -> Unit,
    talkBackEnabled: Boolean = false,
    speak: (String) -> Unit = {},
    voiceCommand: VoiceCommandController? = null,
    signedIn: Boolean = true
) {
    if (confirmingLogout) {
        val lines = listOfNotNull(
            stringResource(R.string.my_logout_body),
            if (pending > 0) stringResource(R.string.my_logout_pending, pending) else null
        )
        // 확인 화면에는 톱니를 두지 않는다(설정 안의 확인, 흐름 문서 P2-1).
        ScreenScaffold(title = stringResource(R.string.my_logout_confirm_title), onBack = onBack, onOpenSettings = null) {
            ConfirmScreenContent(
                lines = lines,
                question = stringResource(if (busy) R.string.my_logging_out else R.string.my_logout_question),
                onConfirm = onLogout,
                onEdit = onCancelLogout,
                confirmText = stringResource(R.string.my_logout),
                editText = stringResource(R.string.my_cancel)
            )
        }
        AutoGuidance("logout-confirm", lines.joinToString(" ") + " " + stringResource(R.string.my_logout_question), talkBackEnabled, speak)
        return
    }
    // 설정 목록 자체에는 톱니바퀴를 두지 않는다(같은 화면으로 가는 버튼).
    ScreenScaffold(title = stringResource(R.string.my_settings), onBack = onBack, onOpenSettings = null) {
        val hint = stringResource(if (signedIn) R.string.my_set_voice_hint else R.string.my_set_voice_hint_before_sign_in)
        if (voiceCommand != null) VoiceCommandButton(voiceCommand, hint, talkBackEnabled, speak)
        // 11개 메뉴를 두 칸 타일로(소유자 피드백 2026-10-13: 한 화면에). 설명은 낭독(버튼 설명)으로 남긴다.
        if (!signedIn) BodyText(stringResource(R.string.my_settings_before_sign_in))
        val items = SettingsItem.visible(signedIn).map { item ->
            val label = stringResource(item.label)
            ListButtonItem(item.name, label, spoken = item.description?.let { "$label. ${stringResource(it)}" })
        }
        ListButtonGrid(
            items = items,
            onClick = { picked -> SettingsItem.valueOf(picked.key).let { it.route?.let(onOpen) ?: onAskLogout() } },
            titleStyle = SeeSunTheme.typography.listItem.copy(fontSize = 18.sp, lineHeight = 24.sp)
        )
    }
    // 목록은 화면에 보이는 항목에서 만든다(항목이 바뀌어도 안내가 어긋나지 않게).
    val names = SettingsItem.visible(signedIn).map { stringResource(it.label) }.joinToString(", ")
    AutoGuidance(
        "settings:$signedIn",
        stringResource(if (signedIn) R.string.my_settings_guide else R.string.my_settings_guide_before_sign_in, names),
        talkBackEnabled,
        speak
    )
}

/**
 * SET-01 목록(순서 = 화면 순서). route가 null이면 로그아웃.
 * @property beforeSignIn 로그인 전(시작 화면 톱니바퀴)에도 보이는 항목(계정 없이 의미 있는 것만, 소유자 msg 959)
 */
enum class SettingsItem(val label: Int, val description: Int?, val route: Any?, val beforeSignIn: Boolean = false) {
    // 사용법 다시 듣기는 맨 위(소유자 msg 1029, 로그인 전에도)
    HOW_TO(R.string.my_set_howto, R.string.my_set_howto_desc, HowToRoute, beforeSignIn = true),

    // 소리·말(자주 바꾸는 것 먼저, UI/UX v2 §5.3) — 로그인 전에도
    VOICE_SPEED(R.string.my_set_speed, R.string.my_set_speed_desc, VoiceSpeedRoute, beforeSignIn = true),
    VOICE_AUTO(R.string.my_set_voice_auto, R.string.my_set_voice_auto_desc, VoiceAutoRoute, beforeSignIn = true),
    APP_VOICE(R.string.my_set_app_voice, R.string.my_set_app_voice_desc, AppVoiceRoute, beforeSignIn = true),
    PRESS_FEEDBACK(R.string.my_set_press, R.string.my_set_press_desc, PressFeedbackRoute, beforeSignIn = true),

    // 도움
    ACCESSIBILITY(R.string.my_set_accessibility, null, AccessibilityRoute, beforeSignIn = true),

    // 기기
    PERMISSIONS(R.string.my_set_permissions, R.string.my_set_permissions_desc, PermissionSettingsRoute, beforeSignIn = true),
    NOTIFICATIONS(R.string.my_set_notifications, R.string.my_set_notifications_desc, NotificationSettingsRoute),
    APP_VERSION(R.string.my_set_version, R.string.my_set_version_desc, AppVersionRoute, beforeSignIn = true),

    // 문서(약관·처리방침은 가입 전에도 볼 수 있어야 한다 — 개인정보 보호법 제30조, 서버 문서 목록은 인증 불필요)
    TERMS(R.string.my_set_terms, null, DocumentRoute("TOS"), beforeSignIn = true),
    PRIVACY(R.string.my_set_privacy, null, DocumentRoute("PRIVACY"), beforeSignIn = true),
    DATA_SOURCES(R.string.my_set_sources, null, DataSourcesRoute),
    LICENSES(R.string.my_set_licenses, null, LicensesRoute),

    // 계정·개인정보
    RIGHTS(R.string.my_set_rights, R.string.my_set_rights_desc, RightsRoute),
    CONSENTS(R.string.my_set_consents, R.string.my_set_consents_desc, ConsentManageRoute),
    DATA_RESET(R.string.my_set_reset, R.string.my_set_reset_desc, DataResetRoute),
    LOGOUT(R.string.my_logout, R.string.my_logout_desc, null),
    DELETE(R.string.my_delete, R.string.my_delete_desc, DeleteAccountRoute);

    companion object {
        /** 보여 줄 항목: 로그인 전이면 계정 없이 쓸 수 있는 것만. */
        fun visible(signedIn: Boolean): List<SettingsItem> = entries.filter { signedIn || it.beforeSignIn }

        /** 말로 고른 곳이 지금 보이는 항목인가(로그인 전에 "탈퇴"·"동의" 등으로 계정 화면이 열리지 않게). */
        fun allowsVoiceTarget(target: Any, signedIn: Boolean): Boolean =
            signedIn || visible(false).any { it.route == target || it == target }
    }
}

// ── 동의 문서 보기(이용약관·처리방침 등) ───────────────

@HiltViewModel
class DocumentViewModel @Inject constructor(private val consents: ConsentRepository, speaker: Speaker) : ViewModel() {
    val voice = GuidanceVoice(speaker)
    private val _doc = MutableStateFlow<Result<ConsentDocumentDto?>?>(null)
    val doc: StateFlow<Result<ConsentDocumentDto?>?> = _doc.asStateFlow()
    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    fun load(type: String) {
        _error.update { null }
        viewModelScope.launch {
            when (val r = consents.documents()) {
                is ApiResult.Success -> _doc.value = Result.success(r.data.firstOrNull { it.type == type })
                is ApiResult.Failure -> _error.value = r.failure.ttsMessage
            }
        }
    }
}

@Composable
fun DocumentRoute(type: String, onBack: () -> Unit, viewModel: DocumentViewModel = hiltViewModel()) {
    val doc by viewModel.doc.collectAsStateWithLifecycle()
    val error by viewModel.error.collectAsStateWithLifecycle()
    LaunchedEffect(type) { viewModel.load(type) }
    val talkBack = rememberTalkBackEnabled()
    val loaded = doc?.getOrNull()
    DocumentScreen(
        document = loaded,
        loading = doc == null && error == null,
        error = error,
        onBack = onBack,
        onRetry = { viewModel.load(type) },
        onListen = { text -> viewModel.voice.onDemand(text, talkBack) },
        talkBackEnabled = talkBack
    )
}

/**
 * 문서 화면: 제목·필수 여부·요약·버전·시행일을 글자로 모두 보여 준다(TalkBack이 그대로 읽음).
 * [전체 듣기]는 TalkBack이 꺼져 있을 때 앱 음성으로 읽는다. 공개 주소가 있으면 [웹에서 보기].
 */
@Composable
fun DocumentScreen(
    document: ConsentDocumentDto?,
    loading: Boolean,
    error: String?,
    onBack: () -> Unit,
    onRetry: () -> Unit,
    onListen: (String) -> Unit,
    talkBackEnabled: Boolean
) {
    val res = LocalResources.current
    ScreenScaffold(title = document?.title ?: stringResource(R.string.my_document_title), onBack = onBack) {
        when {
            loading -> BodyText(stringResource(R.string.my_loading))

            error != null -> {
                BodyText(error, emphasis = true)
                SeeSunButton(text = stringResource(R.string.my_retry), onClick = onRetry, kind = ButtonKind.SECONDARY)
            }

            document == null -> BodyText(stringResource(R.string.my_document_missing))

            else -> {
                val kind = stringResource(if (document.required) R.string.my_required else R.string.my_optional)
                val lines = listOf(
                    kind,
                    document.summary.orEmpty(),
                    stringResource(R.string.my_document_version, document.version, formatDate(res, document.effectiveAt)),
                    if (document.url.isNullOrBlank()) stringResource(R.string.my_document_full_pending) else ""
                ).filter { it.isNotBlank() }
                // 구분·요약(목적·항목·기간 등 핵심)은 보통 크기, 버전·전문 안내는 작은 글자 조항(3차 msg 800).
                ClauseAccordion(
                    clauses = listOf(Clause(stringResource(R.string.my_document_clause_info), lines.drop(2))),
                    lead = lines.take(2).mapIndexed { i, line -> ReadingBlock(line, emphasis = i == 0) }
                )
                val url = document.url
                val uri = LocalUriHandler.current
                BottomActions {
                    if (!talkBackEnabled) {
                        SeeSunButton(
                            text = stringResource(R.string.my_listen_all),
                            kind = ButtonKind.OUTLINE,
                            iconRes = kr.seesun.core.designsystem.R.drawable.ic_volume,
                            onClick = { onListen((listOf(document.title) + lines).joinToString(" ")) }
                        )
                    }
                    if (!url.isNullOrBlank()) {
                        SeeSunButton(text = stringResource(R.string.my_open_web), kind = ButtonKind.OUTLINE, onClick = { uri.openUri(url) })
                    }
                }
            }
        }
    }
}

// ── SET-06 데이터 출처 · 오픈소스 라이선스 · 알림(자리) ──

@Composable
fun DataSourcesScreen(onBack: () -> Unit) {
    ExpandableInfoScreen(
        title = stringResource(R.string.my_set_sources),
        onBack = onBack,
        intro = stringResource(R.string.my_source_note),
        sections = listOf(
            R.string.my_source_food_title to listOf(R.string.my_source_food),
            R.string.my_source_allergen_title to listOf(R.string.my_source_allergen),
            R.string.my_source_kcal_title to listOf(R.string.my_source_kcal),
            R.string.my_source_exercise_title to listOf(R.string.my_source_exercise),
            R.string.my_source_voice_title to listOf(R.string.my_source_voice)
        )
    )
}

@Composable
fun NotificationSettingsScreen(onBack: () -> Unit) {
    ScreenScaffold(title = stringResource(R.string.my_set_notifications), onBack = onBack) {
        BodyText(stringResource(R.string.my_notifications_placeholder), emphasis = true)
        BodyText(stringResource(R.string.my_notifications_permission))
    }
}
