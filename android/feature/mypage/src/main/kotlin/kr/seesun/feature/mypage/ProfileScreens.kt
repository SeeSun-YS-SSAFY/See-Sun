package kr.seesun.feature.mypage

import androidx.activity.compose.BackHandler
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.Clock
import java.time.LocalDate
import javax.inject.Inject
import kotlin.math.roundToInt
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kr.seesun.core.a11y.rememberTalkBackEnabled
import kr.seesun.core.data.repository.ConsentRepository
import kr.seesun.core.data.repository.HealthRepository
import kr.seesun.core.data.repository.ProfileRepository
import kr.seesun.core.designsystem.R as DsR
import kr.seesun.core.designsystem.component.ActionRow
import kr.seesun.core.designsystem.component.ActionSpec
import kr.seesun.core.designsystem.component.BodyText
import kr.seesun.core.designsystem.component.BottomActions
import kr.seesun.core.designsystem.component.ButtonKind
import kr.seesun.core.designsystem.component.DisabledReason
import kr.seesun.core.designsystem.component.IconTile
import kr.seesun.core.designsystem.component.ListButton
import kr.seesun.core.designsystem.component.ListButtonGrid
import kr.seesun.core.designsystem.component.ListButtonItem
import kr.seesun.core.designsystem.component.LiveStatusText
import kr.seesun.core.designsystem.component.NextButton
import kr.seesun.core.designsystem.component.ScreenScaffold
import kr.seesun.core.designsystem.component.SeeSunButton
import kr.seesun.core.designsystem.theme.LayoutTier
import kr.seesun.core.designsystem.theme.LocalLayoutTier
import kr.seesun.core.designsystem.theme.SeeSunDimens
import kr.seesun.core.designsystem.theme.SeeSunSpace
import kr.seesun.core.designsystem.theme.SeeSunTheme
import kr.seesun.core.network.ApiResult
import kr.seesun.core.network.model.ConsentTypes
import kr.seesun.core.network.model.Genders
import kr.seesun.core.network.model.HealthProfileDto
import kr.seesun.core.network.model.MeDto
import kr.seesun.core.network.model.MePatchRequest
import kr.seesun.core.ui.AutoGuidance
import kr.seesun.core.ui.DiseaseOption
import kr.seesun.core.ui.GoalOption
import kr.seesun.core.ui.GuidanceVoice
import kr.seesun.core.ui.VoiceFieldController
import kr.seesun.core.ui.VoiceInputField
import kr.seesun.core.ui.VoiceMenuButton
import kr.seesun.core.ui.VoiceMenuOption
import kr.seesun.core.voice.parse.DateParser
import kr.seesun.core.voice.parse.ProfileParsers
import kr.seesun.core.voice.recognition.SpeechInput
import kr.seesun.core.voice.tts.Speaker

// ── MY-01 마이페이지 ─────────────────────────────────────

data class MyPageState(
    val me: MeDto? = null,
    val health: HealthProfileDto? = null,
    val sensitiveAgreed: Boolean = false,
    val allergenNames: Map<String, String> = emptyMap(),
    val loading: Boolean = true,
    val error: String? = null
)

@HiltViewModel
class MyPageViewModel @Inject constructor(
    private val profile: ProfileRepository,
    private val consents: ConsentRepository,
    private val health: HealthRepository,
    speaker: Speaker
) : ViewModel() {
    val voice = GuidanceVoice(speaker)
    private val _state = MutableStateFlow(MyPageState())
    val state: StateFlow<MyPageState> = _state.asStateFlow()

    fun load() {
        viewModelScope.launch {
            val me = profile.me()
            val sensitive = (consents.status() as? ApiResult.Success)?.data?.items
                ?.any { it.type == ConsentTypes.SENSITIVE && it.agreed } == true
            val healthProfile = if (sensitive) (health.get() as? ApiResult.Success)?.data else null
            val names = if (sensitive) {
                (health.allergens() as? ApiResult.Success)?.data.orEmpty().associate {
                    it.code to it.nameKo
                }
            } else {
                emptyMap()
            }
            _state.value = MyPageState(
                me = (me as? ApiResult.Success)?.data,
                health = healthProfile,
                sensitiveAgreed = sensitive,
                allergenNames = names,
                loading = false,
                error = (me as? ApiResult.Failure)?.failure?.ttsMessage
            )
        }
    }
}

@Composable
fun MyPageRoute(
    onBack: () -> Unit,
    onEditProfile: () -> Unit,
    onEditHealth: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenExerciseReport: () -> Unit = {},
    viewModel: MyPageViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    LifecycleResumeEffect(viewModel) {
        viewModel.load()
        onPauseOrDispose { viewModel.voice.stop() }
    }
    val talkBack = rememberTalkBackEnabled()
    MyPageScreen(
        state = state,
        onBack = onBack,
        onEditProfile = onEditProfile,
        onEditHealth = onEditHealth,
        onOpenSettings = onOpenSettings,
        onRetry = viewModel::load,
        talkBackEnabled = talkBack,
        speak = { viewModel.voice.auto(it, talkBack) },
        onOpenExerciseReport = onOpenExerciseReport
    )
}

/**
 * 마이페이지(Figma 845:2184): 사람 그림 + 이름 + 목표 열량 → [내 정보 수정][건강 정보 수정](회색) → 「기록 레포트」 상자([식단][운동] 노랑 타일)
 * → [설정](회색). 예전 「내 정보」·「건강 정보」 카드의 자세한 줄은 화면에서 빼고 이름 묶음의 낭독(TalkBack)·진입 안내와
 * 내 정보 수정·건강 정보 수정 화면에 둔다.
 */
@Composable
fun MyPageScreen(
    state: MyPageState,
    onBack: () -> Unit,
    onEditProfile: () -> Unit,
    onEditHealth: () -> Unit,
    onOpenSettings: () -> Unit,
    onRetry: () -> Unit = {},
    talkBackEnabled: Boolean = false,
    speak: (String) -> Unit = {},
    onOpenExerciseReport: () -> Unit = {}
) {
    val profileLines = profileLines(state.me)
    val healthLines = healthLines(state)
    var notice by remember { mutableStateOf("") }
    val dietSoon = stringResource(R.string.my_report_diet_soon)
    ScreenScaffold(title = stringResource(R.string.my_title), onBack = onBack) {
        when {
            state.loading -> BodyText(stringResource(R.string.my_loading))

            state.me == null -> {
                BodyText(state.error ?: stringResource(R.string.my_load_failed), emphasis = true)
                SeeSunButton(text = stringResource(R.string.my_retry), onClick = onRetry, kind = ButtonKind.SECONDARY)
            }

            else -> ProfileHeader(state.me, spoken = (profileLines + healthLines).joinToString(". "))
        }
        ActionRow(
            listOf(
                ActionSpec(stringResource(R.string.my_edit_profile), onEditProfile, kind = ButtonKind.OUTLINE),
                ActionSpec(stringResource(R.string.my_edit_health), onEditHealth, kind = ButtonKind.OUTLINE)
            )
        )
        LiveStatusText(notice)
        ReportBox(
            onDiet = {
                notice = if (notice == dietSoon) dietSoon + ZERO_WIDTH else dietSoon
                speak(dietSoon)
            },
            onExercise = onOpenExerciseReport
        )
        BottomActions {
            // 말로 고르기(흐름 문서 P0-4).
            VoiceMenuButton(
                options = listOf(
                    VoiceMenuOption(listOf("내 정보", "정보 수정", "프로필")) { onEditProfile() },
                    VoiceMenuOption(listOf("건강")) { onEditHealth() },
                    VoiceMenuOption(listOf("운동 기록", "운동")) { onOpenExerciseReport() },
                    VoiceMenuOption(listOf("식단 기록", "식단")) {
                        notice = if (notice == dietSoon) dietSoon + ZERO_WIDTH else dietSoon
                        speak(dietSoon)
                    },
                    VoiceMenuOption(listOf("설정")) { onOpenSettings() }
                ),
                hint = stringResource(R.string.my_voice_hint)
            )
            SeeSunButton(
                text = stringResource(R.string.my_settings),
                onClick = onOpenSettings,
                kind = ButtonKind.OUTLINE,
                iconRes = DsR.drawable.ic_ss_settings
            )
        }
    }
    if (!state.loading) {
        AutoGuidance("mypage", stringResource(R.string.my_guide, (profileLines + healthLines).joinToString(". ")), talkBackEnabled, speak)
    }
}

/** 사람 그림(크림 원) + 이름 + 목표 열량. 한 묶음으로 읽고, 낭독은 내 정보·건강 정보 전체 줄([spoken]). */
@Composable
private fun ProfileHeader(me: MeDto, spoken: String) {
    if (LocalLayoutTier.current == LayoutTier.TINY) {
        ProfileHeaderRow(me, spoken)
        return
    }
    val colors = SeeSunTheme.colors
    Column(
        modifier = Modifier.fillMaxWidth().semantics(mergeDescendants = true) { contentDescription = spoken },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(SeeSunSpace.S)
    ) {
        Box(
            Modifier
                .size(AVATAR)
                .shadow(SeeSunDimens.Elevation, CircleShape, clip = false)
                .background(colors.micIdle, CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                painterResource(DsR.drawable.ic_ss_person),
                contentDescription = null,
                tint = colors.onPrimaryIcon,
                modifier = Modifier.size(AVATAR * 0.7f)
            )
        }
        Text(
            text = me.name ?: stringResource(R.string.my_unset),
            style = SeeSunTheme.typography.screenTitleSmall,
            color = colors.onBackground,
            textAlign = TextAlign.Center
        )
        me.targetKcal?.let {
            Text(
                text = stringResource(R.string.my_short_kcal, "%,d".format(it)),
                style = SeeSunTheme.typography.body,
                color = colors.onBackground,
                textAlign = TextAlign.Center
            )
        }
        // 만 19세 미만은 연령별 섭취기준(2차 의뢰서 p14) — 성인 공식과 다르다는 것을 글자로도 보인다.
        if (me.targetKcal != null && usesMinorStandard(me)) {
            Text(
                text = stringResource(R.string.my_short_kcal_minor),
                style = SeeSunTheme.typography.caption,
                color = colors.onBackground,
                textAlign = TextAlign.Center
            )
        }
    }
}

/** 아주 작은 폰(3차): 사람 그림(48dp)을 이름·목표 열량 왼쪽에 두어 한 화면에(읽는 내용은 같음). */
@Composable
private fun ProfileHeaderRow(me: MeDto, spoken: String) {
    val colors = SeeSunTheme.colors
    Row(
        modifier = Modifier.fillMaxWidth().semantics(mergeDescendants = true) { contentDescription = spoken },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(SeeSunSpace.M, Alignment.CenterHorizontally)
    ) {
        Box(Modifier.size(AVATAR_SMALL).background(colors.micIdle, CircleShape), contentAlignment = Alignment.Center) {
            Icon(
                painterResource(DsR.drawable.ic_ss_person),
                contentDescription = null,
                tint = colors.onPrimaryIcon,
                modifier = Modifier.size(AVATAR_SMALL * 0.7f)
            )
        }
        Column {
            Text(
                text = me.name ?: stringResource(R.string.my_unset),
                style = SeeSunTheme.typography.screenTitleSmall,
                color = colors.onBackground
            )
            me.targetKcal?.let {
                Text(
                    text = stringResource(R.string.my_short_kcal, "%,d".format(it)),
                    style = SeeSunTheme.typography.body,
                    color = colors.onBackground
                )
            }
        }
    }
}

/** 「기록 레포트」 상자(Figma: 회색 테두리 상자 + 제목·그래프 그림 + 노랑 [식단][운동] 타일). */
@Composable
private fun ReportBox(onDiet: () -> Unit, onExercise: () -> Unit) {
    val colors = SeeSunTheme.colors
    val shape = RoundedCornerShape(REPORT_RADIUS)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .border(REPORT_BORDER, colors.chip, shape)
            .padding(SeeSunSpace.L),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(SeeSunSpace.M)
    ) {
        Row(
            modifier = Modifier.semantics(mergeDescendants = true) { heading() },
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(SeeSunSpace.S)
        ) {
            Text(stringResource(R.string.my_report_title), style = SeeSunTheme.typography.button, color = colors.onBackground)
            Icon(
                painterResource(DsR.drawable.ic_ss_chart),
                contentDescription = null,
                tint = colors.onBackground,
                modifier = Modifier.size(32.dp)
            )
        }
        Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(SeeSunSpace.L)) {
            IconTile(
                text = stringResource(R.string.my_report_diet),
                onClick = onDiet,
                minHeight = REPORT_TILE,
                spoken = stringResource(R.string.my_report_diet_spoken),
                modifier = Modifier.weight(1f).fillMaxHeight()
            )
            IconTile(
                text = stringResource(R.string.my_report_exercise),
                onClick = onExercise,
                minHeight = REPORT_TILE,
                spoken = stringResource(R.string.my_report_exercise_spoken),
                modifier = Modifier.weight(1f).fillMaxHeight()
            )
        }
    }
}

private val AVATAR = 72.dp
private val AVATAR_SMALL = 48.dp
private val REPORT_RADIUS = 20.dp
private val REPORT_BORDER = 3.dp
private val REPORT_TILE = 96.dp
private const val ZERO_WIDTH = "\u200B"

@Composable
internal fun profileLines(me: MeDto?): List<String> {
    if (me == null) return emptyList()
    val res = LocalResources.current
    return listOfNotNull(
        me.name?.let { stringResource(R.string.my_line_name, it) },
        me.phoneNumberMasked?.let { stringResource(R.string.my_line_phone, it) },
        me.birthdate?.let { stringResource(R.string.my_line_birth, formatDate(res, it), me.age ?: 0) },
        me.gender?.let { stringResource(R.string.my_line_gender, genderLabel(it)) },
        me.heightCm?.let { stringResource(R.string.my_line_height, it) },
        me.weightKg?.let { stringResource(R.string.my_line_weight, it) },
        GoalOption.of(me.goal)?.let { stringResource(R.string.my_line_goal, stringResource(it.label)) },
        me.targetKcal?.let { stringResource(R.string.my_line_kcal, "%,d".format(it)) },
        me.targetKcal?.let { stringResource(if (usesMinorStandard(me)) R.string.my_kcal_minor else R.string.my_kcal_estimate) }
    )
}

@Composable
private fun healthLines(state: MyPageState): List<String> {
    val h = state.health
    if (!state.sensitiveAgreed || h == null) return listOf(stringResource(R.string.my_health_general_mode))
    val none = stringResource(kr.seesun.core.ui.R.string.ui_none)
    return listOfNotNull(
        stringResource(
            R.string.my_line_diseases,
            h.diseases.mapNotNull { DiseaseOption.of(it) }.map { stringResource(it.label) }.joinToString(", ").ifEmpty { none }
        ),
        stringResource(R.string.my_line_allergens, h.allergens.map { state.allergenNames[it] ?: it }.joinToString(", ").ifEmpty { none }),
        h.otherAllergies.takeIf { it.isNotEmpty() }?.let { stringResource(R.string.my_line_other, it.joinToString(", ")) }
    )
}

@Composable
internal fun genderLabel(code: String): String = stringResource(
    if (code ==
        Genders.MALE
    ) {
        R.string.my_gender_male
    } else {
        R.string.my_gender_female
    }
)

// ── MY-02 내 정보 수정 ───────────────────────────────────

enum class ProfileField { NAME, BIRTHDATE, GENDER, HEIGHT, WEIGHT }

data class ProfileEditState(
    val me: MeDto? = null,
    val loading: Boolean = true,
    val editing: ProfileField? = null,
    val busy: Boolean = false,
    val message: String? = null,
    /** 저장에 성공했는데 서버 문구가 없을 때 화면이 기본 "저장했어요"를 보인다. */
    val saved: Boolean = false,
    val messageSeq: Int = 0
)

/**
 * 항목 하나씩 고친다: 목록에서 항목을 누르면 현재 값을 먼저 읽어 주고(MY-02), 음성·키보드로 새 값을 받아 바로 저장한다.
 * 키·몸무게·성별·생년월일이 바뀌면 서버가 목표 열량을 다시 계산해 알려 준다.
 */
@HiltViewModel
class ProfileEditViewModel @Inject constructor(
    private val profile: ProfileRepository,
    private val speech: SpeechInput,
    speaker: Speaker,
    private val clock: Clock
) : ViewModel() {
    val voice = GuidanceVoice(speaker)
    private val _state = MutableStateFlow(ProfileEditState())
    val state: StateFlow<ProfileEditState> = _state.asStateFlow()

    private fun field(parser: (String) -> String?, target: ProfileField) =
        VoiceFieldController(viewModelScope, speech, parser, onBeforeListen = { voice.stop() }) { save(target, it) }

    val nameField = field(ProfileParsers::name, ProfileField.NAME)
    val birthField = field({ t ->
        val today = LocalDate.now(clock)
        DateParser.parse(t, today)?.takeIf { !it.isAfter(today) && it.year >= MIN_BIRTH_YEAR }?.toString()
    }, ProfileField.BIRTHDATE)
    val heightField = field({ t -> ProfileParsers.heightCm(t)?.roundToInt()?.toString() }, ProfileField.HEIGHT)
    val weightField =
        field({ t -> ProfileParsers.weightKg(t)?.roundToInt()?.takeIf { it in WEIGHT_RANGE }?.toString() }, ProfileField.WEIGHT)

    fun load() {
        viewModelScope.launch {
            when (val r = profile.me()) {
                is ApiResult.Success -> _state.update { it.copy(me = r.data, loading = false) }

                is ApiResult.Failure -> _state.update {
                    it.copy(
                        loading = false,
                        message = r.failure.ttsMessage,
                        messageSeq =
                            it.messageSeq + 1
                    )
                }
            }
        }
    }

    fun startEdit(field: ProfileField) {
        val me = state.value.me ?: return
        _state.update { it.copy(editing = field, message = null, saved = false) }
        when (field) {
            ProfileField.NAME -> nameField.setValue(me.name)
            ProfileField.BIRTHDATE -> birthField.setValue(me.birthdate)
            ProfileField.HEIGHT -> heightField.setValue(me.heightCm?.toString())
            ProfileField.WEIGHT -> weightField.setValue(me.weightKg?.toString())
            ProfileField.GENDER -> Unit
        }
    }

    /** 편집을 그만두고 목록으로. 처리했으면 true(시스템 뒤로 가기에서 쓴다). */
    fun cancelEdit(): Boolean {
        if (state.value.editing == null) return false
        voice.stop()
        _state.update { it.copy(editing = null) }
        return true
    }

    fun selectGender(code: String) = save(ProfileField.GENDER, code)

    private fun save(field: ProfileField, value: String) {
        if (state.value.busy) return
        val patch = when (field) {
            ProfileField.NAME -> MePatchRequest(name = value)
            ProfileField.BIRTHDATE -> MePatchRequest(birthdate = value)
            ProfileField.GENDER -> MePatchRequest(gender = value)
            ProfileField.HEIGHT -> MePatchRequest(heightCm = value.toInt())
            ProfileField.WEIGHT -> MePatchRequest(weightKg = value.toInt())
        }
        _state.update { it.copy(busy = true) }
        viewModelScope.launch {
            when (val r = profile.update(patch)) {
                is ApiResult.Success -> _state.update {
                    it.copy(
                        me = r.data,
                        editing = null,
                        busy = false,
                        message = r.data.ttsMessage,
                        saved = true,
                        messageSeq =
                            it.messageSeq + 1
                    )
                }

                is ApiResult.Failure -> _state.update {
                    it.copy(busy = false, message = r.failure.ttsMessage, saved = false, messageSeq = it.messageSeq + 1)
                }
            }
        }
    }

    private companion object {
        const val MIN_BIRTH_YEAR = 1900
        val WEIGHT_RANGE = 20..300
    }
}

@Composable
fun ProfileEditRoute(onBack: () -> Unit, viewModel: ProfileEditViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    LaunchedEffect(viewModel) { viewModel.load() }
    BackHandler(enabled = state.editing != null) { viewModel.cancelEdit() }
    val talkBack = rememberTalkBackEnabled()
    ProfileEditScreen(state, viewModel, onBack, talkBack)
}

@Composable
fun ProfileEditScreen(state: ProfileEditState, vm: ProfileEditViewModel, onBack: () -> Unit, talkBackEnabled: Boolean) {
    val speak: (String) -> Unit = { vm.voice.auto(it, talkBackEnabled) }
    val editing = state.editing
    val me = state.me
    val title = if (editing ==
        null
    ) {
        stringResource(R.string.my_profile_edit_title)
    } else {
        stringResource(R.string.my_edit_field_title, fieldLabel(editing))
    }
    ScreenScaffold(title = title, onBack = if (editing == null) onBack else ({ vm.cancelEdit().let { } })) {
        val savedText = stringResource(R.string.my_saved)
        StatusMessage(state.message ?: savedText.takeIf { state.saved }, state.messageSeq, talkBackEnabled, speak)
        when {
            state.loading -> BodyText(stringResource(R.string.my_loading))
            me == null -> Unit
            editing == null -> ProfileList(me, vm)
            else -> FieldEditor(editing, me, vm, state.busy, talkBackEnabled, speak)
        }
    }
}

@Composable
private fun ProfileList(me: MeDto, vm: ProfileEditViewModel) {
    val res = LocalResources.current
    val change = stringResource(R.string.my_tap_to_edit)
    val unset = stringResource(R.string.my_unset)
    ProfileField.entries.forEach { field ->
        val value = when (field) {
            ProfileField.NAME -> me.name
            ProfileField.BIRTHDATE -> me.birthdate?.let { formatDate(res, it) }
            ProfileField.GENDER -> me.gender?.let { genderLabel(it) }
            ProfileField.HEIGHT -> me.heightCm?.let { stringResource(R.string.my_value_cm, it) }
            ProfileField.WEIGHT -> me.weightKg?.let { stringResource(R.string.my_value_kg, it) }
        }
        ListButton(
            text = stringResource(R.string.my_field_value, fieldLabel(field), value ?: unset),
            description = change,
            onClick = { vm.startEdit(field) }
        )
    }
    BodyText(stringResource(R.string.my_phone_note))
}

@Composable
private fun ColumnScope.FieldEditor(
    field: ProfileField,
    me: MeDto,
    vm: ProfileEditViewModel,
    busy: Boolean,
    talkBack: Boolean,
    speak: (String) -> Unit
) {
    val res = LocalResources.current
    val dateFormat = rememberDateFormat()
    val label = fieldLabel(field)
    val current = when (field) {
        ProfileField.NAME -> me.name
        ProfileField.BIRTHDATE -> me.birthdate?.let(dateFormat)
        ProfileField.GENDER -> me.gender?.let { genderLabel(it) }
        ProfileField.HEIGHT -> me.heightCm?.let { stringResource(R.string.my_value_cm, it) }
        ProfileField.WEIGHT -> me.weightKg?.let { stringResource(R.string.my_value_kg, it) }
    } ?: stringResource(R.string.my_unset)
    val guide = stringResource(R.string.my_edit_guide, label, current)
    BodyText(stringResource(R.string.my_edit_current, current), spoken = guide)
    AutoGuidance("edit:$field", guide, talkBack, speak)
    if (field == ProfileField.GENDER) {
        ListButton(
            stringResource(R.string.my_gender_male),
            onClick = { vm.selectGender(Genders.MALE) },
            selected =
                me.gender == Genders.MALE
        )
        ListButton(
            stringResource(R.string.my_gender_female),
            onClick = { vm.selectGender(Genders.FEMALE) },
            selected =
                me.gender == Genders.FEMALE
        )
    } else {
        val (controller, keyboard, hint) = when (field) {
            ProfileField.NAME -> Triple(vm.nameField, KeyboardType.Text, R.string.my_hint_name)
            ProfileField.BIRTHDATE -> Triple(vm.birthField, KeyboardType.Number, R.string.my_hint_birth)
            ProfileField.HEIGHT -> Triple(vm.heightField, KeyboardType.Number, R.string.my_hint_height)
            else -> Triple(vm.weightField, KeyboardType.Number, R.string.my_hint_weight)
        }
        EditorField(controller, label, field, keyboard, stringResource(hint), busy, talkBack, speak, onCancel = {
            vm.cancelEdit()
        }, display = { v ->
            when (field) {
                ProfileField.BIRTHDATE -> formatDate(res, v)
                ProfileField.HEIGHT -> res.getString(R.string.my_value_cm, v.toIntOrNull() ?: 0)
                ProfileField.WEIGHT -> res.getString(R.string.my_value_kg, v.toIntOrNull() ?: 0)
                else -> v
            }
        })
        return
    }
    BottomActions { SeeSunButton(text = stringResource(R.string.my_cancel), kind = ButtonKind.OUTLINE, onClick = { vm.cancelEdit() }) }
}

@Composable
private fun ColumnScope.EditorField(
    controller: VoiceFieldController,
    label: String,
    field: ProfileField,
    keyboard: KeyboardType,
    hint: String,
    busy: Boolean,
    talkBack: Boolean,
    speak: (String) -> Unit,
    onCancel: () -> Unit,
    display: (String) -> String
) {
    val fieldState by controller.state.collectAsStateWithLifecycle()
    VoiceInputField(
        controller = controller,
        label = label,
        confirmLine = { v -> "$label ${display(v)}" },
        hint = hint,
        talkBackEnabled = talkBack,
        speak = speak,
        keyboardType = keyboard,
        displayValue = display
    )
    if (field == ProfileField.BIRTHDATE) BodyText(stringResource(R.string.my_birth_note))
    // [저장](주요)·[취소]: 화면 아래, 한 줄에 들어가면 나란히(11_레이아웃_기준.md §3).
    val canSave = fieldState.canProceed && !busy
    BottomActions {
        if (!canSave) DisabledReason(stringResource(if (busy) R.string.my_saving else R.string.my_save_disabled))
        ActionRow(
            listOf(
                ActionSpec(stringResource(R.string.my_save), { controller.commitTyped() }, ButtonKind.PRIMARY, enabled = canSave),
                ActionSpec(stringResource(R.string.my_cancel), onCancel, ButtonKind.OUTLINE)
            )
        )
    }
}

@Composable
private fun fieldLabel(field: ProfileField): String = stringResource(
    when (field) {
        ProfileField.NAME -> R.string.my_field_name
        ProfileField.BIRTHDATE -> R.string.my_field_birth
        ProfileField.GENDER -> R.string.my_field_gender
        ProfileField.HEIGHT -> R.string.my_field_height
        ProfileField.WEIGHT -> R.string.my_field_weight
    }
)

/**
 * 서버가 목표 열량을 미성년 연령별 섭취기준으로 계산했는가(산식 이름 kdri…). `minor` 표시만 보면 안 된다 —
 * 그 산식이 서버에 배포되기 전에는 minor=true여도 값이 성인 공식이다.
 */
fun usesMinorStandard(me: MeDto): Boolean {
    val detail = me.targetKcalDetail ?: return false
    return detail.minor && detail.formula.orEmpty().startsWith(MINOR_FORMULA_PREFIX)
}

private const val MINOR_FORMULA_PREFIX = "kdri"
