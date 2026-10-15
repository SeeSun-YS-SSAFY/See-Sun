package kr.seesun.feature.onboarding

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kr.seesun.core.designsystem.component.ActionLabel
import kr.seesun.core.designsystem.component.AdaptiveActions
import kr.seesun.core.designsystem.component.BodyText
import kr.seesun.core.designsystem.component.BottomActions
import kr.seesun.core.designsystem.component.ButtonKind
import kr.seesun.core.designsystem.component.Clause
import kr.seesun.core.designsystem.component.ClauseAccordion
import kr.seesun.core.designsystem.component.DisabledReason
import kr.seesun.core.designsystem.component.NextButton
import kr.seesun.core.designsystem.component.ReadingBlock
import kr.seesun.core.designsystem.component.ReadingPages
import kr.seesun.core.designsystem.component.SeeSunButton
import kr.seesun.core.designsystem.theme.LayoutTier
import kr.seesun.core.designsystem.theme.LocalLayoutTier
import kr.seesun.core.designsystem.theme.SeeSunDimens
import kr.seesun.core.designsystem.theme.SeeSunTheme
import kr.seesun.core.network.model.ConsentDocumentDto
import kr.seesun.core.network.model.ConsentTypes
import kr.seesun.core.network.model.DocumentKinds
import kr.seesun.core.ui.VoiceCommandButton

/**
 * LGL-02~06을 합친 약관 동의 한 화면(소유자 실기기 피드백 2026-10-13: "약관 정보가 너무 많다").
 *
 * 맨 위 한 줄 의료기기 아님 고지 → [전체 동의] → 행 4개(필수 이용약관·필수 개인정보·고지 국외이전·선택 민감정보,
 * 각 행은 한 줄 요약 + [자세히]) → [말로 동의하기]·[다음].
 *
 * 법적 최소 요건(05 문서 §4, 법적 검토 §3.2·3.3):
 * - 필수·선택·고지를 행마다 글자로 구분하고, 미리 체크해 두지 않는다.
 * - 민감정보는 따로 체크하는 행이다. [전체 동의]를 눌러도 그 행이 따로 체크된 것이 보이고, 결과 안내가 민감정보 포함을 말한다.
 * - 개인정보·민감정보의 고지 4요소(목적·항목·보유기간·거부권과 불이익)는 [자세히] 첫 부분에 있다.
 * - 국외이전은 동의가 아니라 확인(고지)이다. 확인하지 않으면 다음으로 갈 수 없다(인증에 꼭 필요).
 */
@Composable
internal fun ColumnScope.ConsentStep(ctx: StepContext) {
    when (val docs = ctx.state.documents) {
        Loadable.Loading -> BodyText(stringResource(R.string.onb_loading))

        is Loadable.Failed -> {
            BodyText(docs.message, emphasis = true)
            SeeSunButton(text = stringResource(R.string.onb_retry), onClick = ctx.vm::loadDocuments, kind = ButtonKind.SECONDARY)
        }

        is Loadable.Loaded -> {
            val detail = ctx.state.detailDocument
            val pending = ctx.state.voiceConsentPending
            when {
                detail != null -> ConsentDetail(ctx, detail)
                pending != null -> VoiceConsentConfirm(ctx, pending)
                else -> ConsentList(ctx, docs.value)
            }
        }
    }
}

@Composable
private fun ColumnScope.ConsentList(ctx: StepContext, docs: List<ConsentDocumentDto>) {
    val vm = ctx.vm
    BodyText(
        stringResource(R.string.onb_medical_line),
        emphasis = true,
        spoken = stringResource(R.string.onb_medical_line_spoken)
    )
    ConsentToggle(
        title = stringResource(R.string.onb_consent_all),
        summary = stringResource(R.string.onb_consent_all_desc).takeUnless { compactConsentRows() },
        spoken = stringResource(R.string.onb_consent_all_spoken),
        checked = ctx.state.allChecked,
        stateOn = stringResource(R.string.onb_consent_state_agreed),
        stateOff = stringResource(R.string.onb_consent_state_not_agreed),
        onToggle = vm::toggleAllConsents,
        large = true
    )
    Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
        docs.forEach { doc -> ConsentDocRow(ctx, doc) }
    }
    // 하단 행동 영역: [다음](주요)·[말로 고르기]. 한 줄에 들어가면 나란히, 아니면 세로(11_레이아웃_기준.md §3).
    val canNext = ctx.state.requiredChecked && !ctx.busy
    val nextLabel = stringResource(R.string.onb_next)
    val voiceLabel = stringResource(kr.seesun.core.ui.R.string.ui_voice_command_idle)
    BottomActions {
        if (!canNext) DisabledReason(stringResource(R.string.onb_consent_next_disabled))
        // 두 줄 글자까지 나란히(3차, 작은 폰 한 화면). 글꼴을 키우면 세로로.
        AdaptiveActions(listOf(ActionLabel(nextLabel), ActionLabel(voiceLabel, hasIcon = true)), maxLines = 2) { stacked, style ->
            ConsentActions(ctx, canNext, nextLabel, stacked, style)
        }
    }
}

/** [다음]·[말로 고르기]를 나란히 또는 세로로(포커스 순서 다음 → 말로 고르기). */
@Composable
private fun ConsentActions(ctx: StepContext, canNext: Boolean, nextLabel: String, stacked: Boolean, style: TextStyle) {
    val vm = ctx.vm
    val hint = stringResource(R.string.onb_consent_voice_hint)
    if (stacked) {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(SeeSunDimens.Gap)) {
            SeeSunButton(text = nextLabel, onClick = vm::consentNext, kind = ButtonKind.PRIMARY, enabled = canNext, textStyle = style)
            VoiceCommandButton(vm.consentVoice, hint, ctx.talkBack, ctx.speak, textStyle = style)
        }
    } else {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(SeeSunDimens.Gap), verticalAlignment = Alignment.Top) {
            SeeSunButton(
                text = nextLabel,
                onClick = vm::consentNext,
                kind = ButtonKind.PRIMARY,
                enabled = canNext,
                textStyle = style,
                modifier = Modifier.weight(1f)
            )
            VoiceCommandButton(vm.consentVoice, hint, ctx.talkBack, ctx.speak, Modifier.weight(1f), style)
        }
    }
}

/** 문서 한 건 행: [체크(행 전체)] + [자세히]. */
@Composable
private fun ConsentDocRow(ctx: StepContext, doc: ConsentDocumentDto) {
    val label = rowLabel(doc)
    val notice = doc.kind == DocumentKinds.NOTICE
    Row(
        modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Min),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        // 폭 400dp 미만에서는 한 줄 요약을 숨기고 구분(필수·고지·선택)만 제목 앞에 둔다. 요약은 낭독(spoken)과 [자세히]에 남는다
        // (11_레이아웃_기준.md §6 작은 화면 축약 규칙).
        val compact = compactConsentRows()
        ConsentToggle(
            title = if (compact) "${label.kind} · ${label.title}" else label.title,
            summary = if (compact) null else "${label.kind} · ${label.summary}",
            spoken = stringResource(R.string.onb_consent_row_spoken, label.kind, doc.title, label.summary),
            checked = ctx.state.isChecked(doc.type),
            stateOn = stringResource(if (notice) R.string.onb_consent_state_noticed else R.string.onb_consent_state_agreed),
            stateOff = stringResource(if (notice) R.string.onb_consent_state_not_noticed else R.string.onb_consent_state_not_agreed),
            onToggle = { ctx.vm.toggleConsent(doc.type) },
            modifier = Modifier.weight(1f),
            separate = doc.type == ConsentTypes.SENSITIVE
        )
        val full = consentDetailText(doc)
        val moreLabel = stringResource(R.string.onb_consent_more_label, label.title)
        SeeSunButton(
            text = stringResource(R.string.onb_consent_more),
            kind = ButtonKind.OUTLINE,
            onClick = { ctx.vm.openConsentDetail(doc.type, full, ctx.talkBack) },
            minHeight = consentRowHeight(),
            modifier = Modifier
                // 최소 88dp, 글꼴을 키우면 레이블 한 줄 폭만큼 넓어진다("자세/히" 한 글자 줄바꿈 방지).
                .widthIn(min = MORE_WIDTH.dp)
                .width(IntrinsicSize.Max)
                .fillMaxHeight()
                .semantics { contentDescription = moreLabel },
            textStyle = SeeSunTheme.typography.caption.copy(fontWeight = FontWeight.Bold)
        )
    }
}

/**
 * 체크 행. 행 전체가 체크박스 하나다(TalkBack: "필수, See:Sun 이용약관. 이용 조건·책임 범위. 체크박스, 동의 안 함").
 * 상태는 색만이 아니라 체크 표시·글자로도 보인다(COM-07).
 * @param separate 민감정보 별도 동의 행: 굵은 노랑 테두리로 다른 행과 구분한다.
 */
@Composable
private fun ConsentToggle(
    title: String,
    summary: String?,
    spoken: String,
    checked: Boolean,
    stateOn: String,
    stateOff: String,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
    large: Boolean = false,
    separate: Boolean = false
) {
    val colors = SeeSunTheme.colors
    val shape = RoundedCornerShape(SeeSunDimens.CornerRadius)
    val content = if (checked) colors.onPrimary else colors.onSurface
    Row(
        modifier = modifier
            .fillMaxWidth()
            // 동의 행은 한 화면에 다섯 줄이 들어가야 해서 Figma 74dp 대신 56dp(법적 화면, FIGMA_ALIGN_REPORT §5),
            // 아주 작은 폰은 48dp(누르는 곳 최소, 3차).
            .heightIn(min = consentRowHeight())
            .background(if (checked) colors.primary else colors.surface, shape)
            .border(
                if (separate) SEPARATE_BORDER.dp else SeeSunDimens.BorderWidth,
                if (separate) colors.secondary else colors.border,
                shape
            )
            .toggleable(value = checked, role = Role.Checkbox, onValueChange = { onToggle() })
            .semantics(mergeDescendants = false) {
                contentDescription = spoken
                stateDescription = if (checked) stateOn else stateOff
            }
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Box(
            modifier = Modifier
                .size(CHECK_BOX.dp)
                .background(colors.surface, RoundedCornerShape(6.dp))
                .border(SeeSunDimens.BorderWidth, colors.border, RoundedCornerShape(6.dp)),
            contentAlignment = Alignment.Center
        ) {
            if (checked) {
                Icon(
                    painterResource(kr.seesun.core.designsystem.R.drawable.ic_check),
                    contentDescription = null,
                    tint = colors.onSurface,
                    modifier = Modifier.size(22.dp)
                )
            }
        }
        Column(Modifier.weight(1f).clearAndSetSemantics { }) {
            Text(
                text = title,
                style = SeeSunTheme.typography.listItem.copy(
                    fontSize = if (large) 22.sp else 18.sp,
                    lineHeight = if (large) 28.sp else 24.sp
                ),
                color = content
            )
            if (summary != null) Text(text = summary, style = SeeSunTheme.typography.caption, color = content)
        }
    }
}

/**
 * [자세히](3차 소유자 msg 800): 법적으로 눈에 띄어야 하는 핵심 — 개인정보 보호법 제22조 목적·항목·보유기간·거부권과 불이익,
 * 민감정보·국외이전 고지 — 은 보통 크기·강조로 늘 보이고, 나머지(서비스 성격·요약·문서 버전·전문 안내)는 조항으로 접어 작은 글자로.
 * 아래에 [웹에서 전문]·이 행 체크·[목록으로]가 늘 보인다(스크롤 0).
 */
@Composable
private fun ColumnScope.ConsentDetail(ctx: StepContext, doc: ConsentDocumentDto) {
    val label = rowLabel(doc)
    val key = keyNotice(doc)
    val summary = doc.summary?.takeIf { it.isNotBlank() }
    val lead = buildList {
        add(ReadingBlock(stringResource(R.string.onb_consent_detail_kind, label.kind), emphasis = true))
        key?.let { add(ReadingBlock(it, emphasis = true)) }
        // 민감정보·국외이전은 요약 자체가 고지 내용이라 접지 않는다.
        if (summary != null && doc.type in KEY_SUMMARY_TYPES) add(ReadingBlock(summary, emphasis = true))
    }
    val clauses = buildList {
        if (doc.type ==
            ConsentTypes.TOS
        ) {
            add(Clause(stringResource(R.string.onb_clause_service), listOf(stringResource(R.string.onb_consent_detail_tos))))
        }
        if (summary != null && doc.type !in KEY_SUMMARY_TYPES) add(Clause(stringResource(R.string.onb_clause_summary), listOf(summary)))
        add(
            Clause(
                stringResource(R.string.onb_clause_doc_info),
                listOfNotNull(
                    stringResource(R.string.onb_consent_version, doc.version, formatDate(doc.effectiveAt)),
                    if (doc.url.isNullOrBlank()) stringResource(R.string.onb_consent_full_pending) else null
                )
            )
        )
    }
    ClauseAccordion(clauses = clauses, lead = lead)
    val notice = doc.kind == DocumentKinds.NOTICE
    BottomActions(pushDown = false) {
        if (!doc.url.isNullOrBlank()) {
            val uri = LocalUriHandler.current
            val url = doc.url.orEmpty()
            SeeSunButton(text = stringResource(R.string.onb_consent_open_web), kind = ButtonKind.OUTLINE, onClick = { uri.openUri(url) })
        }
        ConsentToggle(
            title = label.title,
            summary = "${label.kind} · ${label.summary}".takeUnless { compactConsentRows() },
            spoken = stringResource(R.string.onb_consent_row_spoken, label.kind, doc.title, label.summary),
            checked = ctx.state.isChecked(doc.type),
            stateOn = stringResource(if (notice) R.string.onb_consent_state_noticed else R.string.onb_consent_state_agreed),
            stateOff = stringResource(if (notice) R.string.onb_consent_state_not_noticed else R.string.onb_consent_state_not_agreed),
            onToggle = { ctx.vm.toggleConsent(doc.type) },
            separate = doc.type == ConsentTypes.SENSITIVE
        )
        SeeSunButton(text = stringResource(R.string.onb_consent_detail_back), onClick = ctx.vm::closeConsentDetail)
    }
}

/** 음성 동의 재확인(05 §4): 무엇에 동의하는지 다시 묻고 [동의 확인]·[취소]. */
@Composable
private fun ColumnScope.VoiceConsentConfirm(ctx: StepContext, scope: VoiceConsentScope) {
    val question = stringResource(
        if (scope == VoiceConsentScope.ALL) R.string.onb_consent_voice_confirm_all else R.string.onb_consent_voice_confirm_required
    )
    BodyText(question, emphasis = true)
    BottomActions {
        SeeSunButton(text = stringResource(R.string.onb_consent_voice_yes), onClick = ctx.vm::confirmVoiceConsent, enabled = !ctx.busy)
        SeeSunButton(text = stringResource(R.string.onb_consent_voice_no), kind = ButtonKind.OUTLINE, onClick = ctx.vm::cancelVoiceConsent)
        VoiceCommandButton(ctx.vm.consentVoice, stringResource(R.string.onb_consent_voice_hint), ctx.talkBack, ctx.speak)
    }
}

/** 동의 행·[자세히] 높이: 56dp, 아주 작은 폰 48dp. */
@Composable
private fun consentRowHeight() =
    if (LocalLayoutTier.current == LayoutTier.TINY) SeeSunDimens.MinTouchTarget else SeeSunDimens.SmallButtonMinHeight

private data class RowLabel(val kind: String, val title: String, val summary: String)

@Composable
private fun rowLabel(doc: ConsentDocumentDto): RowLabel {
    val kind = stringResource(
        when {
            doc.kind == DocumentKinds.NOTICE -> R.string.onb_consent_kind_notice
            doc.type == ConsentTypes.SENSITIVE -> R.string.onb_consent_kind_sensitive
            doc.required -> R.string.onb_consent_kind_required
            else -> R.string.onb_consent_kind_optional
        }
    )
    val (title, summary) = when (doc.type) {
        ConsentTypes.TOS -> R.string.onb_consent_row_tos to R.string.onb_consent_row_tos_desc
        ConsentTypes.PRIVACY -> R.string.onb_consent_row_privacy to R.string.onb_consent_row_privacy_desc
        ConsentTypes.OVERSEAS -> R.string.onb_consent_row_overseas to R.string.onb_consent_row_overseas_desc
        ConsentTypes.SENSITIVE -> R.string.onb_consent_row_sensitive to R.string.onb_consent_row_sensitive_desc
        else -> return RowLabel(kind, doc.title, doc.summary.orEmpty())
    }
    return RowLabel(kind, stringResource(title), stringResource(summary))
}

/** 법적 핵심 고지(개인정보 보호법 제22조 4요소). 이용약관은 핵심 고지가 아니라 조항으로 접는다. */
@Composable
private fun keyNotice(doc: ConsentDocumentDto): String? = when (doc.type) {
    ConsentTypes.PRIVACY -> stringResource(R.string.onb_consent_detail_privacy)
    ConsentTypes.SENSITIVE -> stringResource(R.string.onb_consent_detail_sensitive)
    else -> null
}

/** 요약이 곧 고지 내용인 문서(민감정보 처리·국외이전): 요약을 접지 않고 보통 크기로. */
private val KEY_SUMMARY_TYPES = setOf(ConsentTypes.SENSITIVE, ConsentTypes.OVERSEAS)

/** [자세히] 첫 부분(법정 고지 요소, 전체 듣기용 글). 국외이전은 서버 문서 요약에 법정 항목이 모두 있다. */
private fun detailLead(doc: ConsentDocumentDto): Int? = when (doc.type) {
    ConsentTypes.TOS -> R.string.onb_consent_detail_tos
    ConsentTypes.PRIVACY -> R.string.onb_consent_detail_privacy
    ConsentTypes.SENSITIVE -> R.string.onb_consent_detail_sensitive
    else -> null
}

/** [자세히] 낭독 문구: 제목 → 법정 고지 요소 → 요약 → 버전·시행일 → (전문 미확정 안내). */
@Composable
internal fun consentDetailText(doc: ConsentDocumentDto): String = listOf(
    doc.title,
    detailLead(doc)?.let { stringResource(it) }.orEmpty(),
    doc.summary.orEmpty(),
    stringResource(R.string.onb_consent_version, doc.version, formatDate(doc.effectiveAt)),
    if (doc.url.isNullOrBlank()) stringResource(R.string.onb_consent_full_pending) else ""
).filter { it.isNotBlank() }.joinToString(" ")

private const val MORE_WIDTH = 88
private const val CHECK_BOX = 30
private const val SEPARATE_BORDER = 4

/** 동의 행을 줄여 그릴 좁은 화면인가(폭 400dp 미만). */
@Composable
private fun compactConsentRows(): Boolean = LocalConfiguration.current.screenWidthDp < COMPACT_ROW_DP

private const val COMPACT_ROW_DP = 400
