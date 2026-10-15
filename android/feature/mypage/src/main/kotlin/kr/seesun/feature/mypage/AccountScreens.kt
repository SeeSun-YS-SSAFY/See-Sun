package kr.seesun.feature.mypage

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineBreak
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kr.seesun.core.a11y.rememberTalkBackEnabled
import kr.seesun.core.data.repository.ConsentRepository
import kr.seesun.core.data.repository.SessionRepository
import kr.seesun.core.designsystem.component.ActionRow
import kr.seesun.core.designsystem.component.ActionSpec
import kr.seesun.core.designsystem.component.BodyText
import kr.seesun.core.designsystem.component.BottomActions
import kr.seesun.core.designsystem.component.ButtonKind
import kr.seesun.core.designsystem.component.Clause
import kr.seesun.core.designsystem.component.ClauseAccordion
import kr.seesun.core.designsystem.component.ConfirmContent
import kr.seesun.core.designsystem.component.ConfirmScreenContent
import kr.seesun.core.designsystem.component.ReadingBlock
import kr.seesun.core.designsystem.component.ReadingPages
import kr.seesun.core.designsystem.component.ScreenScaffold
import kr.seesun.core.designsystem.component.SeeSunButton
import kr.seesun.core.designsystem.theme.SeeSunDimens
import kr.seesun.core.designsystem.theme.SeeSunTheme
import kr.seesun.core.network.ApiResult
import kr.seesun.core.network.model.ConsentDecisionDto
import kr.seesun.core.network.model.ConsentDocumentDto
import kr.seesun.core.network.model.ConsentMethods
import kr.seesun.core.network.model.ConsentStatusDto
import kr.seesun.core.network.model.ConsentTypes
import kr.seesun.core.network.model.DocumentKinds
import kr.seesun.core.ui.AutoGuidance
import kr.seesun.core.ui.GuidanceVoice
import kr.seesun.core.voice.tts.Speaker

// ── SET-05 개인정보·동의 관리 ───────────────────────────

/** 확인을 받는 중인 동의 변경. */
data class PendingConsentChange(val type: String, val agree: Boolean)

data class ConsentManageState(
    val loading: Boolean = true,
    val items: List<ConsentStatusDto> = emptyList(),
    val documents: Map<String, ConsentDocumentDto> = emptyMap(),
    val pending: PendingConsentChange? = null,
    val busy: Boolean = false,
    val message: String? = null,
    /** 방금 바꾼 결과(서버 문구가 없을 때 화면 기본 문구를 고른다). */
    val result: PendingConsentChange? = null,
    /** 민감정보 철회로 지운 데이터 종류(health_profile, diet_scores). */
    val deleted: List<String> = emptyList(),
    val messageSeq: Int = 0
)

/**
 * 동의 상태 보기·철회·다시 동의(05 §3 B "동의 기록·철회", 계약 §3).
 * - 선택 항목은 철회할 수 있다. 민감정보 철회는 건강정보·질환 기반 점수를 바로 지운다는 것을 먼저 알린다.
 * - 필수 항목은 철회할 수 없다(서버 400 REQUIRED_CONSENT) → 회원 탈퇴를 안내한다.
 * - 문서가 바뀌어 `needs_reconsent`면 새 문서 요약을 보여 주고 다시 동의받는다.
 */
@HiltViewModel
class ConsentManageViewModel @Inject constructor(private val consents: ConsentRepository, speaker: Speaker) : ViewModel() {
    val voice = GuidanceVoice(speaker)
    private val _state = MutableStateFlow(ConsentManageState())
    val state: StateFlow<ConsentManageState> = _state.asStateFlow()

    fun load() {
        viewModelScope.launch {
            val status = consents.status()
            val docs = (consents.documents() as? ApiResult.Success)?.data.orEmpty().associateBy { it.type }
            _state.update {
                when (status) {
                    is ApiResult.Success -> it.copy(
                        loading = false,
                        items = status.data.items.sortedBy { item -> ConsentTypes.ORDER.indexOf(item.type) },
                        documents = docs
                    )

                    is ApiResult.Failure -> it.copy(loading = false, message = status.failure.ttsMessage, messageSeq = it.messageSeq + 1)
                }
            }
        }
    }

    fun ask(type: String, agree: Boolean) = _state.update {
        it.copy(pending = PendingConsentChange(type, agree), message = null, result = null)
    }

    /** 확인 화면을 닫는다. 처리했으면 true. */
    fun cancel(): Boolean {
        if (state.value.pending == null || state.value.busy) return false
        _state.update { it.copy(pending = null) }
        return true
    }

    fun confirm() {
        val change = state.value.pending ?: return
        val item = state.value.items.firstOrNull { it.type == change.type } ?: return
        if (state.value.busy) return
        _state.update { it.copy(busy = true) }
        viewModelScope.launch {
            val decision = ConsentDecisionDto(item.type, item.currentVersion, change.agree, ConsentMethods.TOUCH, screenId = SCREEN_ID)
            when (val r = consents.submit(listOf(decision))) {
                is ApiResult.Success -> _state.update {
                    it.copy(
                        busy = false,
                        pending = null,
                        items = r.data.items.sortedBy { i -> ConsentTypes.ORDER.indexOf(i.type) },
                        result = change,
                        deleted = r.data.deleted,
                        message = null,
                        messageSeq = it.messageSeq + 1
                    )
                }

                is ApiResult.Failure -> _state.update {
                    it.copy(busy = false, message = r.failure.ttsMessage, result = null, messageSeq = it.messageSeq + 1)
                }
            }
        }
    }
}

/** 동의 기록에 남길 화면 ID(SET-05 개인정보·동의 관리, 법적 검토 C-07). */
private const val SCREEN_ID = "SET-05"

@Composable
fun ConsentManageRoute(onBack: () -> Unit, onOpenDocument: (String) -> Unit, viewModel: ConsentManageViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    LifecycleResumeEffect(viewModel) {
        viewModel.load()
        onPauseOrDispose { viewModel.voice.stop() }
    }
    BackHandler(enabled = state.pending != null) { viewModel.cancel() }
    val talkBack = rememberTalkBackEnabled()
    ConsentManageScreen(state, viewModel::ask, viewModel::confirm, { viewModel.cancel() }, onBack, onOpenDocument, talkBack) {
        viewModel.voice.auto(it, talkBack)
    }
}

@Composable
fun ConsentManageScreen(
    state: ConsentManageState,
    onAsk: (String, Boolean) -> Unit,
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
    onBack: () -> Unit,
    onOpenDocument: (String) -> Unit,
    talkBackEnabled: Boolean = false,
    speak: (String) -> Unit = {}
) {
    val pending = state.pending
    val pendingItem = pending?.let { p -> state.items.firstOrNull { it.type == p.type } }
    if (pending != null && pendingItem != null) {
        ConsentChangeConfirm(state, pending, pendingItem, onConfirm, onCancel, talkBackEnabled, speak)
        return
    }
    ScreenScaffold(title = stringResource(R.string.my_set_consents), onBack = onBack) {
        StatusMessage(resultText(state), state.messageSeq, talkBackEnabled, speak)
        if (state.loading) BodyText(stringResource(R.string.my_loading))
        state.items.forEach { item -> ConsentItemCard(item, onAsk, onOpenDocument) }
    }
}

/**
 * 동의 항목 한 줄(한 화면에 4개가 들어가게, 소유자 피드백 2026-10-13): 제목·상태 카드 + [내용] + [동작(철회·동의·다시 확인)].
 * 짧은 버튼 글자 대신 TalkBack은 "건강정보(민감정보) 처리 동의 철회"처럼 전체 이름을 읽는다(버튼 설명).
 * 필수 항목은 동작 버튼 대신 "철회는 탈퇴로"를 상태 줄에 보이고, 자세한 안내는 낭독으로.
 */
@Composable
private fun ConsentItemCard(item: ConsentStatusDto, onAsk: (String, Boolean) -> Unit, onOpenDocument: (String) -> Unit) {
    val res = LocalResources.current
    val notice = item.kind == DocumentKinds.NOTICE
    val kind = stringResource(
        when {
            notice -> R.string.my_consent_notice_kind
            item.required -> R.string.my_required
            else -> R.string.my_optional
        }
    )
    val status = when {
        item.needsReconsent -> stringResource(R.string.my_consent_needs_reconsent)
        notice && item.agreed -> stringResource(R.string.my_consent_notice_status, formatDate(res, item.agreedAt))
        item.agreed -> stringResource(R.string.my_consent_agreed_on, formatDate(res, item.agreedAt))
        else -> stringResource(R.string.my_consent_not_agreed)
    }
    val action: Pair<String, String>? = when {
        notice && (item.needsReconsent || !item.agreed) ->
            stringResource(R.string.my_consent_short_reack) to stringResource(R.string.my_consent_reack, item.title)

        notice -> null

        item.needsReconsent || !item.agreed -> stringResource(R.string.my_consent_short_agree) to stringResource(
            if (item.needsReconsent) R.string.my_consent_reagree else R.string.my_consent_agree,
            consentName(item.title)
        )

        item.required -> null

        else -> stringResource(R.string.my_consent_short_withdraw) to stringResource(R.string.my_consent_withdraw, consentName(item.title))
    }
    val note = when {
        action != null -> null
        notice -> stringResource(R.string.my_consent_notice_note)
        else -> stringResource(R.string.my_consent_required_note)
    }
    val colors = SeeSunTheme.colors
    val shape = RoundedCornerShape(SeeSunDimens.CornerRadius)
    Row(
        modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Min),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight()
                .background(colors.surface, shape)
                .border(SeeSunDimens.BorderWidth, colors.border, shape)
                .padding(horizontal = 10.dp, vertical = 6.dp)
                .semantics(mergeDescendants = true) {
                    contentDescription = listOfNotNull(item.title, kind, status, note).joinToString(". ")
                },
            verticalArrangement = Arrangement.Center
        ) {
            Text(item.title, style = SeeSunTheme.typography.listItem.copy(fontSize = 18.sp, lineHeight = 24.sp), color = colors.onSurface)
            val shortNote = if (note != null) " · " + stringResource(R.string.my_consent_short_locked) else ""
            Text(
                "$kind · $status$shortNote",
                // 줄 길이를 고르게(한 글자만 다음 줄로 넘어가지 않게, 3차 아주 작은 폰)
                style = SeeSunTheme.typography.caption.copy(lineBreak = LineBreak.Heading),
                color = colors.onSurface
            )
        }
        val readLabel = stringResource(R.string.my_consent_read, item.title)
        SeeSunButton(
            text = stringResource(R.string.my_consent_short_read),
            kind = ButtonKind.OUTLINE,
            onClick = { onOpenDocument(item.type) },
            modifier = Modifier.widthIn(min = SMALL_BUTTON.dp).width(IntrinsicSize.Max).fillMaxHeight().semantics {
                contentDescription =
                    readLabel
            },
            textStyle = SeeSunTheme.typography.caption.copy(fontWeight = FontWeight.Bold)
        )
        if (action != null) {
            val withdraw = action.first == stringResource(R.string.my_consent_short_withdraw)
            SeeSunButton(
                text = action.first,
                kind = if (withdraw) ButtonKind.SECONDARY else ButtonKind.PRIMARY,
                onClick = { onAsk(item.type, !withdraw) },
                modifier = Modifier.widthIn(min = SMALL_BUTTON.dp).width(IntrinsicSize.Max).fillMaxHeight().semantics {
                    contentDescription =
                        action.second
                },
                textStyle = SeeSunTheme.typography.caption.copy(fontWeight = FontWeight.Bold)
            )
        }
    }
}

private const val SMALL_BUTTON = 72

@Composable
private fun ConsentChangeConfirm(
    state: ConsentManageState,
    change: PendingConsentChange,
    item: ConsentStatusDto,
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
    talkBack: Boolean,
    speak: (String) -> Unit
) {
    val summary = state.documents[item.type]?.summary
    val lines = listOfNotNull(
        item.title,
        summary,
        when {
            !change.agree && item.type == ConsentTypes.SENSITIVE -> stringResource(R.string.my_consent_withdraw_sensitive)
            !change.agree -> stringResource(R.string.my_consent_withdraw_note)
            else -> null
        }
    )
    val notice = item.kind == DocumentKinds.NOTICE
    val question = stringResource(
        when {
            notice -> R.string.my_consent_ack_question
            change.agree -> R.string.my_consent_agree_question
            else -> R.string.my_consent_withdraw_question
        },
        if (notice) item.title else consentName(item.title)
    )
    val title = when {
        notice -> R.string.my_consent_ack_title
        change.agree -> R.string.my_consent_agree_title
        else -> R.string.my_consent_withdraw_title
    }
    ScreenScaffold(title = stringResource(title), onBack = onCancel) {
        StatusMessage(state.message, state.messageSeq, talkBack, speak)
        ConfirmScreenContent(
            lines = lines,
            question = if (state.busy) stringResource(R.string.my_saving) else question,
            onConfirm = onConfirm,
            onEdit = onCancel,
            confirmText = stringResource(
                when {
                    notice -> R.string.my_consent_ack_button
                    change.agree -> R.string.my_consent_agree_button
                    else -> R.string.my_consent_withdraw_button
                }
            ),
            editText = stringResource(R.string.my_cancel)
        )
    }
    AutoGuidance("consent-change:${change.type}:${change.agree}", (lines + question).joinToString(". "), talkBack, speak)
}

@Composable
private fun resultText(state: ConsentManageState): String? {
    state.message?.let { return it }
    val result = state.result ?: return null
    val item = state.items.firstOrNull { it.type == result.type }
    val title = item?.title.orEmpty()
    val base = stringResource(
        when {
            item?.kind == DocumentKinds.NOTICE -> R.string.my_consent_acked_result
            result.agree -> R.string.my_consent_agreed_result
            else -> R.string.my_consent_withdrawn_result
        },
        if (item?.kind == DocumentKinds.NOTICE) title else consentName(title)
    )
    return if (state.deleted.isNotEmpty()) base + " " + stringResource(R.string.my_consent_deleted_result) else base
}

/**
 * 동의 문구에 넣을 이름. 제목이 이미 "…동의"로 끝나면(예: "건강정보(민감정보) 처리 동의") 그대로, 아니면 "제목 동의".
 * 화면 낭독에서 "처리 동의 동의 철회"처럼 같은 말이 겹쳐 들리던 것을 막는다(통합 E2E BUG-08).
 */
internal fun consentName(title: String): String = title.trimEnd().let { if (it.endsWith("동의")) it else "$it 동의" }

// ── SET-09 회원 탈퇴(2단계 확인) ─────────────────────────

enum class DeleteStep { EXPLAIN, CONFIRM, DONE }

data class DeleteAccountState(
    val step: DeleteStep = DeleteStep.EXPLAIN,
    val busy: Boolean = false,
    val error: String? = null,
    val doneMessage: String? = null,
    val seq: Int = 0
)

/**
 * 회원 탈퇴: ① 지워지는 내용 안내 → ② 마지막 확인 → `DELETE /me/` → 성공하면 이 기기 데이터도 지우고 ③ 완료 안내.
 * 실패(오프라인 등)면 아무것도 지우지 않고 [다시 시도]를 보인다.
 */
@HiltViewModel
class DeleteAccountViewModel @Inject constructor(private val session: SessionRepository, speaker: Speaker) : ViewModel() {
    val voice = GuidanceVoice(speaker)
    private val _state = MutableStateFlow(DeleteAccountState())
    val state: StateFlow<DeleteAccountState> = _state.asStateFlow()

    fun next() = _state.update { it.copy(step = DeleteStep.CONFIRM, error = null) }

    /** 한 단계 뒤로. 처리했으면 true(첫 단계면 화면을 닫도록 false). */
    fun back(): Boolean {
        val s = state.value
        if (s.busy || s.step == DeleteStep.DONE) return true
        if (s.step == DeleteStep.CONFIRM) {
            _state.update { it.copy(step = DeleteStep.EXPLAIN, error = null) }
            return true
        }
        return false
    }

    fun delete() {
        val s = state.value
        if (s.busy || s.step != DeleteStep.CONFIRM) return
        _state.update { it.copy(busy = true, error = null) }
        viewModelScope.launch {
            when (val r = session.deleteAccount()) {
                is ApiResult.Success -> _state.update {
                    it.copy(busy = false, step = DeleteStep.DONE, doneMessage = r.data, seq = it.seq + 1)
                }

                is ApiResult.Failure -> _state.update { it.copy(busy = false, error = r.failure.ttsMessage, seq = it.seq + 1) }
            }
        }
    }
}

@Composable
fun DeleteAccountRoute(onBack: () -> Unit, onDeleted: () -> Unit, viewModel: DeleteAccountViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    BackHandler { if (!viewModel.back()) onBack() }
    val talkBack = rememberTalkBackEnabled()
    DeleteAccountScreen(
        state = state,
        onBack = { if (!viewModel.back()) onBack() },
        onNext = viewModel::next,
        onDelete = viewModel::delete,
        onDone = onDeleted,
        talkBackEnabled = talkBack,
        speak = { viewModel.voice.auto(it, talkBack) }
    )
}

@Composable
fun DeleteAccountScreen(
    state: DeleteAccountState,
    onBack: () -> Unit,
    onNext: () -> Unit,
    onDelete: () -> Unit,
    onDone: () -> Unit,
    talkBackEnabled: Boolean = false,
    speak: (String) -> Unit = {}
) {
    when (state.step) {
        DeleteStep.EXPLAIN -> ScreenScaffold(title = stringResource(R.string.my_delete), onBack = onBack) {
            val lines = listOf(
                stringResource(R.string.my_delete_explain),
                stringResource(R.string.my_delete_explain_device),
                stringResource(R.string.my_delete_explain_web)
            )
            // 지워지는 것(되돌릴 수 없는 결과)은 보통 크기·강조로 늘 보이고, 기기·백업·웹 안내는 조항으로 접는다(3차 msg 800).
            ClauseAccordion(
                clauses = listOf(
                    Clause(stringResource(R.string.my_delete_clause_device), listOf(lines[1])),
                    Clause(stringResource(R.string.my_delete_clause_web), listOf(lines[2]))
                ),
                lead = listOf(ReadingBlock(lines[0], emphasis = true))
            )
            BottomActions(pushDown = false) {
                ActionRow(
                    listOf(
                        ActionSpec(stringResource(R.string.my_delete_continue), onNext, ButtonKind.SECONDARY),
                        ActionSpec(stringResource(R.string.my_cancel), onBack, ButtonKind.OUTLINE)
                    )
                )
            }
            AutoGuidance("delete-explain", lines.joinToString(" "), talkBackEnabled, speak)
        }

        DeleteStep.CONFIRM -> ScreenScaffold(title = stringResource(R.string.my_delete_confirm_title), onBack = onBack) {
            StatusMessage(state.error ?: stringResource(R.string.my_deleting).takeIf { state.busy }, state.seq, talkBackEnabled, speak)
            val question = stringResource(R.string.my_delete_question)
            BodyText(question, emphasis = true)
            BottomActions {
                SeeSunButton(
                    text = stringResource(if (state.error != null) R.string.my_delete_retry else R.string.my_delete_button),
                    supportingText = stringResource(R.string.my_delete_irreversible),
                    onClick = onDelete,
                    enabled = !state.busy,
                    kind = ButtonKind.SECONDARY
                )
                SeeSunButton(text = stringResource(R.string.my_cancel), onClick = onBack, kind = ButtonKind.OUTLINE, enabled = !state.busy)
            }
            AutoGuidance("delete-confirm", question, talkBackEnabled, speak)
        }

        DeleteStep.DONE -> ScreenScaffold(title = stringResource(R.string.my_delete_done_title)) {
            val message = state.doneMessage ?: stringResource(R.string.my_delete_done)
            BodyText(message, emphasis = true)
            BodyText(stringResource(R.string.my_delete_done_device))
            BottomActions { SeeSunButton(text = stringResource(R.string.my_ok), onClick = onDone) }
            AutoGuidance("delete-done", message, talkBackEnabled, speak)
        }
    }
}
