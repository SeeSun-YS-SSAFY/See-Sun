package kr.seesun.feature.mypage

import androidx.activity.compose.BackHandler
import androidx.annotation.RawRes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
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
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kr.seesun.core.a11y.rememberTalkBackEnabled
import kr.seesun.core.data.repository.DataResetRepository
import kr.seesun.core.data.repository.MealRepository
import kr.seesun.core.designsystem.component.ActionRow
import kr.seesun.core.designsystem.component.ActionSpec
import kr.seesun.core.designsystem.component.BodyText
import kr.seesun.core.designsystem.component.BottomActions
import kr.seesun.core.designsystem.component.ButtonKind
import kr.seesun.core.designsystem.component.Clause
import kr.seesun.core.designsystem.component.ClauseAccordion
import kr.seesun.core.designsystem.component.ListButton
import kr.seesun.core.designsystem.component.ReadingBlock
import kr.seesun.core.designsystem.component.ReadingPages
import kr.seesun.core.designsystem.component.ScreenScaffold
import kr.seesun.core.designsystem.component.SeeSunButton
import kr.seesun.core.designsystem.theme.LayoutTier
import kr.seesun.core.designsystem.theme.LocalLayoutTier
import kr.seesun.core.network.ApiResult
import kr.seesun.core.ui.AutoGuidance
import kr.seesun.core.ui.GuidanceVoice
import kr.seesun.core.voice.tts.Speaker

// ── A-04 내 개인정보 권리 ────────────────────────────────

/** 권리 행사 항목과 가는 곳(열람·정정·일부 삭제·처리정지/철회·탈퇴). */
enum class RightsItem(val label: Int, val description: Int, val route: Any) {
    VIEW(R.string.my_rights_view, R.string.my_rights_view_desc, MyPageRoute),
    EDIT(R.string.my_rights_edit, R.string.my_rights_edit_desc, ProfileEditRoute),
    DELETE_PART(R.string.my_rights_delete_part, R.string.my_rights_delete_part_desc, DataResetRoute),
    STOP(R.string.my_rights_stop, R.string.my_rights_stop_desc, ConsentManageRoute),
    DELETE_ALL(R.string.my_rights_delete_all, R.string.my_rights_delete_all_desc, DeleteAccountRoute)
}

/**
 * 내 개인정보 권리(법적 검토 A-04): 수집할 때보다 어렵지 않게 — 버튼 한 번으로 각 권리 화면으로 가고,
 * 설정의 [말로 고르기]로도 같은 곳에 갈 수 있다. 전화·문자·이메일 창구를 함께 알린다.
 */
@Composable
fun RightsScreen(onBack: () -> Unit, onOpen: (Any) -> Unit, talkBackEnabled: Boolean = false, speak: (String) -> Unit = {}) {
    val intro = stringResource(R.string.my_rights_intro)
    ScreenScaffold(title = stringResource(R.string.my_set_rights), onBack = onBack) {
        BodyText(stringResource(R.string.my_rights_intro_short), spoken = intro)
        RightsItem.entries.forEach { item ->
            // 작은 폰 이하에서는 설명 줄을 접고 TalkBack 설명으로 읽는다(3차, 360×740 한 화면).
            ListButton(
                stringResource(item.label),
                onClick = { onOpen(item.route) },
                description = stringResource(item.description),
                descriptionVisible = LocalLayoutTier.current == LayoutTier.REGULAR
            )
        }
        BodyText(
            stringResource(R.string.my_rights_contact_short),
            spoken = stringResource(R.string.my_rights_contact_title) + ". " + stringResource(R.string.my_rights_contact)
        )
    }
    AutoGuidance("rights", intro, talkBackEnabled, speak)
}

// ── A-03 데이터 초기화 ────────────────────────────────────

enum class ResetTarget { MEALS, HEALTH }

enum class ResetStep { CHOOSE, EXPLAIN, CONFIRM, DONE }

data class DataResetState(
    val step: ResetStep = ResetStep.CHOOSE,
    val target: ResetTarget? = null,
    val busy: Boolean = false,
    val error: String? = null,
    val doneMessage: String? = null,
    val seq: Int = 0
)

/**
 * 데이터 초기화(법적 검토 A-03): 고르기 → ① 무엇이 지워지는지 낭독 → ② 마지막 확인 → 삭제 → 결과 낭독.
 * 실패(오프라인 등)면 아무것도 지우지 않고 [다시 시도]를 보인다. 계정은 남는다.
 */
@HiltViewModel
class DataResetViewModel @Inject constructor(private val reset: DataResetRepository, meals: MealRepository, speaker: Speaker) :
    ViewModel() {
    val voice = GuidanceVoice(speaker)
    private val _state = MutableStateFlow(DataResetState())
    val state: StateFlow<DataResetState> = _state.asStateFlow()

    /** 지워질 전송 대기 기록 수(식사 초기화 설명에 넣는다). */
    val pending: StateFlow<Int> = meals.observePendingCount().stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_MS), 0)

    fun choose(target: ResetTarget) = _state.update { DataResetState(step = ResetStep.EXPLAIN, target = target, seq = it.seq) }

    fun next() = _state.update { if (it.step == ResetStep.EXPLAIN) it.copy(step = ResetStep.CONFIRM) else it }

    /** 한 단계 뒤로. 첫 화면이면 false(화면을 닫는다). */
    fun back(): Boolean {
        val s = state.value
        if (s.busy) return true
        _state.update {
            when (s.step) {
                ResetStep.CHOOSE -> return false
                ResetStep.EXPLAIN, ResetStep.DONE -> DataResetState(seq = it.seq)
                ResetStep.CONFIRM -> it.copy(step = ResetStep.EXPLAIN, error = null)
            }
        }
        return true
    }

    fun delete() {
        val s = state.value
        val target = s.target ?: return
        if (s.busy || s.step != ResetStep.CONFIRM) return
        _state.update { it.copy(busy = true, error = null) }
        viewModelScope.launch {
            val result = if (target == ResetTarget.MEALS) reset.deleteMeals() else reset.deleteHealth()
            _state.update {
                when (result) {
                    is ApiResult.Success -> it.copy(
                        busy = false,
                        step = ResetStep.DONE,
                        doneMessage = result.data.ttsMessage,
                        seq =
                            it.seq + 1
                    )

                    is ApiResult.Failure -> it.copy(busy = false, error = result.failure.ttsMessage, seq = it.seq + 1)
                }
            }
        }
    }

    private companion object {
        const val STOP_MS = 5_000L
    }
}

@Composable
fun DataResetRoute(onBack: () -> Unit, viewModel: DataResetViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val pending by viewModel.pending.collectAsStateWithLifecycle()
    BackHandler { if (!viewModel.back()) onBack() }
    val talkBack = rememberTalkBackEnabled()
    DataResetScreen(
        state = state,
        pending = pending,
        onBack = { if (!viewModel.back()) onBack() },
        onChoose = viewModel::choose,
        onNext = viewModel::next,
        onDelete = viewModel::delete,
        onDone = onBack,
        talkBackEnabled = talkBack,
        speak = { viewModel.voice.auto(it, talkBack) }
    )
}

@Composable
fun DataResetScreen(
    state: DataResetState,
    pending: Int,
    onBack: () -> Unit,
    onChoose: (ResetTarget) -> Unit,
    onNext: () -> Unit,
    onDelete: () -> Unit,
    onDone: () -> Unit,
    talkBackEnabled: Boolean = false,
    speak: (String) -> Unit = {}
) {
    val explain = when (state.target) {
        ResetTarget.MEALS -> stringResource(R.string.my_reset_meals_explain, pending)
        ResetTarget.HEALTH -> stringResource(R.string.my_reset_health_explain)
        null -> ""
    }
    val backup = stringResource(R.string.my_reset_backup)
    when (state.step) {
        ResetStep.CHOOSE -> ScreenScaffold(title = stringResource(R.string.my_set_reset), onBack = onBack) {
            val intro = stringResource(R.string.my_reset_intro)
            BodyText(intro)
            // 두 선택지가 이 화면의 행동이라 화면 아래 누르는 영역에 둔다(11_레이아웃_기준.md §2).
            BottomActions {
                ListButton(stringResource(R.string.my_reset_meals), onClick = {
                    onChoose(ResetTarget.MEALS)
                }, description = stringResource(R.string.my_reset_meals_desc))
                ListButton(stringResource(R.string.my_reset_health), onClick = {
                    onChoose(ResetTarget.HEALTH)
                }, description = stringResource(R.string.my_reset_health_desc))
            }
            AutoGuidance("reset-choose", intro, talkBackEnabled, speak)
        }

        ResetStep.EXPLAIN -> ScreenScaffold(title = stringResource(targetLabel(state.target)), onBack = onBack) {
            BodyText(explain, emphasis = true)
            BodyText(backup)
            BottomActions {
                SeeSunButton(text = stringResource(R.string.my_reset_continue), onClick = onNext, kind = ButtonKind.SECONDARY)
                SeeSunButton(text = stringResource(R.string.my_cancel), onClick = onBack, kind = ButtonKind.OUTLINE)
            }
            AutoGuidance("reset-explain:${state.target}", "$explain $backup", talkBackEnabled, speak)
        }

        ResetStep.CONFIRM -> ScreenScaffold(title = stringResource(R.string.my_reset_confirm_title), onBack = onBack) {
            StatusMessage(
                state.error ?: stringResource(R.string.my_reset_deleting).takeIf {
                    state.busy
                },
                state.seq,
                talkBackEnabled,
                speak
            )
            val question = stringResource(
                if (state.target == ResetTarget.MEALS) R.string.my_reset_meals_question else R.string.my_reset_health_question
            )
            BodyText(question, emphasis = true)
            BottomActions {
                SeeSunButton(
                    text = stringResource(if (state.error != null) R.string.my_reset_retry else R.string.my_reset_delete),
                    supportingText = stringResource(R.string.my_delete_irreversible),
                    onClick = onDelete,
                    enabled = !state.busy,
                    kind = ButtonKind.SECONDARY
                )
                SeeSunButton(text = stringResource(R.string.my_cancel), onClick = onBack, kind = ButtonKind.OUTLINE, enabled = !state.busy)
            }
            AutoGuidance("reset-confirm:${state.target}", question, talkBackEnabled, speak)
        }

        ResetStep.DONE -> ScreenScaffold(title = stringResource(R.string.my_reset_done_title)) {
            val message = state.doneMessage ?: stringResource(R.string.my_reset_done)
            BodyText(message, emphasis = true)
            BottomActions { SeeSunButton(text = stringResource(R.string.my_ok), onClick = onDone) }
            AutoGuidance("reset-done:${state.seq}", message, talkBackEnabled, speak)
        }
    }
}

private fun targetLabel(target: ResetTarget?): Int = if (target == ResetTarget.HEALTH) R.string.my_reset_health else R.string.my_reset_meals

// ── T-12 접근성 안내·문의 ─────────────────────────────────

@Composable
fun AccessibilityScreen(onBack: () -> Unit) {
    ExpandableInfoScreen(
        title = stringResource(R.string.my_set_accessibility),
        onBack = onBack,
        intro = null,
        sections = listOf(
            R.string.my_a11y_check_title to listOf(R.string.my_a11y_check),
            R.string.my_a11y_support_title to listOf(R.string.my_a11y_support),
            R.string.my_a11y_types_title to
                listOf(R.string.my_a11y_blind, R.string.my_a11y_low_vision, R.string.my_a11y_hearing, R.string.my_a11y_motor),
            R.string.my_a11y_limits_title to listOf(R.string.my_a11y_limits),
            R.string.my_a11y_contact_title to listOf(R.string.my_a11y_contact)
        ),
        smallBody = false
    )
}

// ── T-11 오픈소스 라이선스(빌드 때 자동 생성한 목록) ──────

/** app 빌드가 만든 assets/open_source_licenses.json 한 줄. */
@Serializable
data class OssLibrary(
    val group: String,
    val artifact: String,
    val version: String,
    val name: String,
    val url: String = "",
    val licenses: List<OssLicense> = emptyList()
)

@Serializable
data class OssLicense(val name: String, val url: String = "")

/** 자산 파일을 읽는다. 없거나 깨졌으면 null. */
fun loadOssLibraries(read: () -> String): List<OssLibrary>? = runCatching {
    Json { ignoreUnknownKeys = true }.decodeFromString<List<OssLibrary>>(read())
}.getOrNull()

private const val OSS_ASSET = "open_source_licenses.json"

/**
 * 오픈소스 라이선스(법적 검토 T-11·L-01): 라이선스별로 묶은 라이브러리 목록(자동 생성) + 아이콘·글꼴 고지 + Apache-2.0 전문·SQLCipher 고지를
 * 조항으로 접는다(3차 소유자 msg 800, [ClauseAccordion]). 펼친 글은 작은 글자, 넘치면 그 조항 안에서만 쪽을 넘긴다.
 * @param libraries 시험에서 넣는 목록. null이면 앱 자산에서 읽는다.
 */
@Composable
fun LicensesScreen(onBack: () -> Unit, libraries: List<OssLibrary>? = null) {
    val context = LocalContext.current
    val list =
        remember(libraries) { libraries ?: loadOssLibraries { context.assets.open(OSS_ASSET).bufferedReader().use { it.readText() } } }
    val sqlcipherName = stringResource(R.string.my_license_sqlcipher_name)
    val apache = remember { context.resources.openRawResource(R.raw.license_apache_2_0).bufferedReader().use { it.readText() } }
    val sqlcipher = remember { context.resources.openRawResource(R.raw.license_sqlcipher).bufferedReader().use { it.readText() } }
    ScreenScaffold(title = stringResource(R.string.my_set_licenses), onBack = onBack) {
        val clauses = buildList {
            list?.groupBy { lib -> licenseLabel(lib, sqlcipherName) }?.toSortedMap()?.forEach { (license, libs) ->
                add(
                    Clause(
                        stringResource(R.string.my_license_group, license, libs.size),
                        libs.map { "${it.name} ${it.version} (${it.group}:${it.artifact})" }
                    )
                )
            }
            // 아이콘·로고 글자 모양 고지(라이브러리 목록에 잡히지 않는 리소스).
            add(
                Clause(
                    stringResource(R.string.my_license_fonts_title),
                    listOf(stringResource(R.string.my_license_icons_material), stringResource(R.string.my_license_font_pretendard))
                )
            )
            add(Clause(stringResource(R.string.my_license_show_apache).removeSuffix(" 보기"), paragraphs(apache)))
            add(Clause(stringResource(R.string.my_license_show_sqlcipher).removeSuffix(" 보기"), paragraphs(sqlcipher)))
        }
        val lead = listOfNotNull(
            ReadingBlock(stringResource(R.string.my_licenses_intro)),
            if (list == null) {
                ReadingBlock(stringResource(R.string.my_licenses_load_failed), emphasis = true)
            } else {
                ReadingBlock(stringResource(R.string.my_licenses_generated, list.size))
            }
        )
        ClauseAccordion(clauses = clauses, lead = lead)
    }
}

/** 라이선스 전문을 문단으로: 빈 줄로 나누고, 문단 안 줄바꿈(80자 고정 폭)은 띄어쓰기로 이어 화면 폭에 맞춰 다시 흐르게 한다. */
internal fun paragraphs(text: String): List<String> =
    text.split(Regex("""\n\s*\n""")).map { it.replace(Regex("""\s*\n\s*"""), " ").trim() }.filter { it.isNotEmpty() }

/** 라이선스 이름 정리: Apache 2.0 표기 여러 가지를 하나로, 이름 없이 주소만 있는 SQLCipher는 이름을 붙인다. */
private fun licenseLabel(lib: OssLibrary, sqlcipherName: String): String {
    val raw = lib.licenses.firstOrNull()?.name.orEmpty()
    return when {
        raw.contains("apache", ignoreCase = true) -> "Apache License 2.0"
        raw.contains("sqlcipher", ignoreCase = true) || lib.group == "net.zetetic" -> sqlcipherName
        raw.isBlank() -> "-"
        else -> raw
    }
}

// ── 설정 음성 이동(A-01·A-04: 탈퇴·권리 화면에 말로도 간다) ──

/** 말한 내용으로 갈 설정 항목을 찾는다. 앞쪽 규칙이 우선(예: "개인정보 동의 철회"는 동의 관리). */
object SettingsVoiceMatcher {
    private val RULES: List<Pair<List<String>, Any>> = listOf(
        listOf("탈퇴", "계정삭제", "계정지워", "회원삭제") to DeleteAccountRoute,
        listOf("자동인식", "자동듣기", "음성자동", "자동마이크", "마이크자동") to VoiceAutoRoute,
        listOf("권한", "마이크허용", "알림허용") to PermissionSettingsRoute,
        listOf("버전", "업데이트") to AppVersionRoute,
        listOf("초기화", "식사기록지워", "식사기록삭제", "건강정보지워", "건강정보삭제", "일부삭제", "일부지워") to DataResetRoute,
        listOf("동의", "철회", "처리정지", "처리멈") to ConsentManageRoute,
        listOf("권리", "열람") to RightsRoute,
        listOf("고치", "정정", "수정") to ProfileEditRoute,
        listOf("내정보", "내개인정보보기") to MyPageRoute,
        listOf("처리방침", "개인정보") to DocumentRoute("PRIVACY"),
        listOf("약관") to DocumentRoute("TOS"),
        listOf("출처") to DataSourcesRoute,
        listOf("라이선스", "라이센스", "오픈소스") to LicensesRoute,
        listOf("접근성", "문의") to AccessibilityRoute,
        listOf("알림") to NotificationSettingsRoute,
        listOf("로그아웃") to SettingsItem.LOGOUT
    )

    fun match(text: String): Any? {
        val t = text.replace(" ", "")
        return RULES.firstOrNull { (words, _) -> words.any { t.contains(it) } }?.second
    }
}
