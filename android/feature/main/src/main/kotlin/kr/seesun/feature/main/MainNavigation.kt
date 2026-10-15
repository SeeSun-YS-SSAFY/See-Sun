package kr.seesun.feature.main

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.Clock
import java.time.LocalDate
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kr.seesun.core.a11y.rememberTalkBackEnabled
import kr.seesun.core.data.repository.ConsentRepository
import kr.seesun.core.data.repository.MealRepository
import kr.seesun.core.data.repository.ProfileRepository
import kr.seesun.core.data.repository.UnsentRecords
import kr.seesun.core.designsystem.R as DsR
import kr.seesun.core.designsystem.component.BodyText
import kr.seesun.core.designsystem.component.BottomActions
import kr.seesun.core.designsystem.component.ButtonKind
import kr.seesun.core.designsystem.component.IconTile
import kr.seesun.core.designsystem.component.LiveStatusText
import kr.seesun.core.designsystem.component.LocalBodyViewport
import kr.seesun.core.designsystem.component.ScreenScaffold
import kr.seesun.core.designsystem.component.SeeSunButton
import kr.seesun.core.designsystem.component.SeeSunLogoRow
import kr.seesun.core.designsystem.theme.LocalCompactHeight
import kr.seesun.core.designsystem.theme.SeeSunDimens
import kr.seesun.core.designsystem.theme.SeeSunSpace
import kr.seesun.core.designsystem.theme.SeeSunTheme
import kr.seesun.core.network.ApiResult
import kr.seesun.core.ui.AutoGuidance
import kr.seesun.core.ui.GuidanceVoice
import kr.seesun.core.ui.VoiceMenuButton
import kr.seesun.core.ui.VoiceMenuOption
import kr.seesun.core.voice.tts.Speaker

/**
 * MAIN-01 메인(Figma 1031:3818): 로고 + 노랑 큰 타일 2×2(식단·운동·약·마이페이지) + 아래 [설정].
 * 남는 높이에 맞춰 타일 높이를 정하고(최대 210dp), 큰 글꼴이면 타일이 높아지며 스크롤된다.
 */
@Serializable
data object MainRoute

/** 메인에서 갈 수 있는 곳. */
enum class MainDestination(@StringRes val label: Int, @StringRes val description: Int) {
    DIET(R.string.main_diet, R.string.main_diet_desc),
    EXERCISE(R.string.main_exercise, R.string.main_exercise_desc),
    MY_PAGE(R.string.main_mypage, R.string.main_mypage_desc),
    SETTINGS(R.string.main_settings, R.string.main_settings_desc),

    /** 동의 문서가 바뀌어 다시 동의가 필요할 때 안내 띠에서만 간다. */
    CONSENTS(R.string.main_reconsent_action, R.string.main_reconsent_body),

    /** 개발용: 디자인 시스템 점검(debug 빌드에서만 보인다). */
    COMPONENTS(R.string.main_components, R.string.main_components_desc);

    companion object {
        /** 메뉴 순서(Figma): 식단·운동 → (약, 3차 준비 중) → 마이페이지 → 설정. */
        val MENU = listOf(DIET, EXERCISE, MY_PAGE, SETTINGS)
    }
}

fun NavGraphBuilder.mainScreen(onNavigate: (MainDestination) -> Unit, showDeveloperMenu: Boolean = false) {
    composable<MainRoute> { MainRoute(onNavigate = onNavigate, showDeveloperMenu = showDeveloperMenu) }
}

/** 오늘 요약(메인 상단). */
data class TodaySummary(
    val name: String? = null,
    val targetKcal: Int? = null,
    val mealsToday: Int = 0,
    val pending: Int = 0,
    val loaded: Boolean = false,
    val failed: Boolean = false,
    /** 동의 문서가 바뀌었거나 필수 동의가 빠졌다(다시 동의할 때까지 기록 API가 403). */
    val needsReconsent: Boolean = false
)

@HiltViewModel
class MainViewModel @Inject constructor(
    private val profile: ProfileRepository,
    private val consents: ConsentRepository,
    meals: MealRepository,
    unsent: UnsentRecords,
    speaker: Speaker,
    clock: Clock
) : ViewModel() {

    val voice = GuidanceVoice(speaker)
    private val remote = MutableStateFlow(TodaySummary())

    val summary: StateFlow<TodaySummary> = combine(
        remote,
        meals.observeMeals(LocalDate.now(clock)),
        unsent.observeCount()
    ) { r, todayMeals, pending -> r.copy(mealsToday = todayMeals.size, pending = pending) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), TodaySummary())

    /** 화면에 돌아올 때마다(마이페이지에서 수정 후 등) 다시 읽는다. */
    fun refresh() {
        viewModelScope.launch {
            val me = profile.me()
            val status = consents.status()
            remote.update {
                it.copy(
                    name = (me as? ApiResult.Success)?.data?.name,
                    targetKcal = (me as? ApiResult.Success)?.data?.targetKcal,
                    loaded = true,
                    failed = me is ApiResult.Failure,
                    needsReconsent = (status as? ApiResult.Success)?.data?.let { s ->
                        !s.allRequiredAgreed || s.items.any { item -> item.needsReconsent }
                    } ?: it.needsReconsent
                )
            }
        }
    }

    private companion object {
        const val STOP_TIMEOUT_MS = 5_000L
    }
}

@Composable
fun MainRoute(onNavigate: (MainDestination) -> Unit, showDeveloperMenu: Boolean, viewModel: MainViewModel = hiltViewModel()) {
    val summary by viewModel.summary.collectAsStateWithLifecycle()
    LifecycleResumeEffect(viewModel) {
        viewModel.refresh()
        onPauseOrDispose { viewModel.voice.stop() }
    }
    val talkBack = rememberTalkBackEnabled()
    MainScreen(
        summary = summary,
        onNavigate = {
            viewModel.voice.stop()
            onNavigate(it)
        },
        onRetry = viewModel::refresh,
        showDeveloperMenu = showDeveloperMenu,
        talkBackEnabled = talkBack,
        speak = { viewModel.voice.auto(it, talkBack) }
    )
}

/**
 * 메인 화면. 포커스 순서: 로고("시선", 제목) → (불러오기 실패·재동의 안내) → 식단 → 운동 → 약 → 마이페이지 → 설정.
 * 예전 「오늘 요약」 카드는 Figma에 없어 화면에서 뺐다. 인사 한 줄만 보이고, 목표 열량·식사 끼니·미전송 건수는 그 줄의 TalkBack 설명,
 * 진입 안내 낭독(TalkBack이 꺼져 있을 때)과 [화면 요약 듣기]로 듣는다. 목표 열량은 마이페이지, 식사 끼니·미전송 건수는 식단 화면에도 보인다.
 */
@Composable
fun MainScreen(
    summary: TodaySummary,
    onNavigate: (MainDestination) -> Unit,
    onRetry: () -> Unit = {},
    showDeveloperMenu: Boolean = false,
    talkBackEnabled: Boolean = false,
    speak: (String) -> Unit = {}
) {
    val summaryLines = summaryLines(summary)
    val guide = stringResource(R.string.main_guide, summaryLines.joinToString(" "))
    var notice by remember { mutableStateOf("") }
    val comingSoon = stringResource(R.string.main_medicine_soon)
    ScreenScaffold(
        title = stringResource(R.string.main_title),
        showTitle = false,
        onSpeakSummary = if (talkBackEnabled) null else ({ speak(guide) })
    ) {
        SeeSunLogoRow(heading = true, modifier = Modifier.padding(vertical = SeeSunSpace.S))
        // 인사 한 줄만 보이고(Figma에는 없음), TalkBack은 이 줄에서 오늘 요약 전체(목표 열량·식사 끼니·미전송)를 읽는다.
        BodyText(
            text = summary.name?.let { stringResource(R.string.main_hello, it) } ?: stringResource(R.string.main_today),
            spoken = summaryLines.joinToString(" ")
        )
        LiveStatusText(notice)
        if (summary.failed) {
            BodyText(stringResource(R.string.main_load_failed), emphasis = true)
            SeeSunButton(text = stringResource(R.string.main_retry), onClick = onRetry, kind = ButtonKind.OUTLINE)
        }
        if (summary.needsReconsent) {
            SeeSunButton(
                text = stringResource(R.string.main_reconsent_action),
                onClick = { onNavigate(MainDestination.CONSENTS) },
                kind = ButtonKind.SECONDARY,
                supportingText = stringResource(R.string.main_reconsent_body)
            )
        }
        // Figma처럼 큰 타일이 화면 가운데를 채우고(남는 높이에 맞춤), [말로 고르기]는 화면 아래 누르는 영역(11_레이아웃_기준.md §2).
        MainTiles(
            extra = (if (summary.failed) FAILED_ROWS else 0.dp) + (if (summary.needsReconsent) RECONSENT_ROWS else 0.dp) +
                (if (notice.isEmpty()) 0.dp else NOTICE_ROWS),
            onNavigate = onNavigate,
            onMedicine = {
                notice = if (notice == comingSoon) comingSoon + ZERO_WIDTH else comingSoon
                speak(comingSoon)
            }
        )
        BottomActions(pushDown = LocalDensity.current.fontScale > 1f) {
            // 말로 고르기(흐름 문서 P0-4): 안내가 「…중에서 골라 주세요」로 끝나므로 같은 말로 고른다(질문 뒤 듣기·말하면 듣기).
            VoiceMenuButton(
                options = listOf(
                    VoiceMenuOption(listOf("식단", "식사", "밥")) { onNavigate(MainDestination.DIET) },
                    VoiceMenuOption(listOf("운동")) { onNavigate(MainDestination.EXERCISE) },
                    VoiceMenuOption(listOf("약", "복약")) {
                        notice = if (notice == comingSoon) comingSoon + ZERO_WIDTH else comingSoon
                        speak(comingSoon)
                    },
                    VoiceMenuOption(listOf("마이페이지", "마이 페이지", "내 정보")) { onNavigate(MainDestination.MY_PAGE) },
                    VoiceMenuOption(listOf("설정")) { onNavigate(MainDestination.SETTINGS) }
                ),
                hint = stringResource(R.string.main_voice_hint)
            )
            // 하단 큰 [설정]은 없앴다(소유자 msg 1029): 설정은 오른쪽 위 톱니바퀴 하나, 말로 「설정」도 된다.
            if (showDeveloperMenu) {
                SeeSunButton(
                    text = stringResource(R.string.main_components),
                    onClick = { onNavigate(MainDestination.COMPONENTS) },
                    kind = ButtonKind.OUTLINE
                )
            }
        }
    }
    // 요약을 읽은 뒤에 한 번 안내한다(TalkBack이 꺼져 있을 때만).
    if (summary.loaded) AutoGuidance(key = "main", message = guide, talkBackEnabled = talkBackEnabled, speak = speak)
}

/**
 * 2×2 그림 타일. 남는 높이를 모두 받아 타일 높이를 정한다(3차, 스크롤 0): 보통 폰은 Figma 그대로 210dp(남는 높이가 더 크면 그 아래 빈 공간),
 * 낮은 화면은 남는 높이의 반(96~210dp, 128dp 미만이면 그림 없이 글자만). 글꼴을 키우면 예전처럼 추정 높이로 두고 본문이 스크롤된다.
 */
@Composable
private fun ColumnScope.MainTiles(extra: Dp, onNavigate: (MainDestination) -> Unit, onMedicine: () -> Unit) {
    if (LocalDensity.current.fontScale <= 1f) {
        BoxWithConstraints(Modifier.fillMaxWidth().weight(1f)) {
            val compact = LocalCompactHeight.current
            val gap = if (compact) SeeSunSpace.M else SeeSunSpace.L
            val tile = ((maxHeight - gap) / 2).coerceIn(SeeSunDimens.MainTileMinHeight, SeeSunDimens.MainTileHeight)
            TileGrid(tile, onNavigate, onMedicine)
        }
        return
    }
    val viewport = LocalBodyViewport.current
    val compact = LocalCompactHeight.current
    // 안내 줄(재동의·불러오기 실패)이 함께 뜨면 보통 폰에서도 타일을 남는 높이에 맞춰 줄인다.
    val tileHeight = if ((!compact && extra == 0.dp) || viewport == Dp.Unspecified) {
        SeeSunDimens.MainTileHeight
    } else {
        // 로고·[설정]·간격을 뺀 나머지의 반. 안내 줄이 있으면 그만큼 더 뺀다.
        ((viewport - (if (compact) RESERVED else RESERVED_REGULAR) - extra) / 2).coerceIn(
            SeeSunDimens.MainTileMinHeight,
            SeeSunDimens.MainTileHeight
        )
    }
    TileGrid(tileHeight, onNavigate, onMedicine)
}

@Composable
private fun TileGrid(tileHeight: Dp, onNavigate: (MainDestination) -> Unit, onMedicine: () -> Unit) {
    val compact = LocalCompactHeight.current
    // Figma 1031:3818 타일 간격: 가로 18 · 세로 16. 작은 폰은 12.
    val hGap = if (compact) SeeSunSpace.M else TILE_GAP_H
    val vGap = if (compact) SeeSunSpace.M else SeeSunSpace.L
    val tiles = listOf(
        Tile(MainDestination.DIET.label, DsR.drawable.ic_ss_restaurant) { onNavigate(MainDestination.DIET) },
        Tile(MainDestination.EXERCISE.label, DsR.drawable.ic_ss_fitness) { onNavigate(MainDestination.EXERCISE) },
        Tile(R.string.main_medicine, DsR.drawable.ic_ss_pill, onClick = onMedicine),
        Tile(MainDestination.MY_PAGE.label, DsR.drawable.ic_ss_person, display = R.string.main_mypage_tile) {
            onNavigate(MainDestination.MY_PAGE)
        }
    )
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(vGap)) {
        tiles.chunked(2).forEach { row ->
            Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(hGap)) {
                row.forEach { t ->
                    IconTile(
                        text = stringResource(t.display ?: t.label),
                        // 타일이 아주 낮으면(재동의·불러오기 실패 안내가 함께 있을 때) 그림을 빼고 글자만(한 화면 유지).
                        iconRes = if (tileHeight < NO_ICON_BELOW) null else t.icon,
                        onClick = t.onClick,
                        minHeight = tileHeight,
                        iconSize = if (tileHeight < SeeSunDimens.MainTileHeight) SMALL_ICON else LARGE_ICON,
                        spoken = stringResource(t.label),
                        // 그림 없이 글자만 보이는 낮은 타일은 28sp(「마이/페이지」 두 줄이 타일 안에 들어가게, 3차).
                        textStyle = if (tileHeight < NO_ICON_BELOW) {
                            SeeSunTheme.typography.tile.copy(fontSize = LOW_TILE_SP.sp, lineHeight = LOW_TILE_LINE_SP.sp)
                        } else {
                            SeeSunTheme.typography.tile
                        },
                        modifier = Modifier.weight(1f).fillMaxHeight()
                    )
                }
            }
        }
    }
}

private class Tile(
    @param:StringRes val label: Int,
    @param:DrawableRes val icon: Int,
    @param:StringRes val display: Int? = null,
    val onClick: () -> Unit
)

private val RESERVED = 256.dp

/** 보통 폰(묶음 간격 24)에서 로고·인사·[설정]·간격이 쓰는 높이. */
private val RESERVED_REGULAR = 320.dp
private val TILE_GAP_H = 18.dp

/** 안내가 더 보일 때 빼는 높이: 불러오기 실패(두 줄 + 버튼), 재동의(버튼 + 보조 문구), 「준비 중」 한 줄. */
private val FAILED_ROWS = 266.dp
private val RECONSENT_ROWS = 222.dp
private val NOTICE_ROWS = 40.dp
private val LARGE_ICON = 72.dp
private val SMALL_ICON = 52.dp
private val NO_ICON_BELOW = 128.dp
private const val LOW_TILE_SP = 28
private const val LOW_TILE_LINE_SP = 32
private const val ZERO_WIDTH = "\u200B"

@Composable
private fun summaryLines(s: TodaySummary): List<String> = listOfNotNull(
    s.name?.let { stringResource(R.string.main_hello, it) },
    when {
        s.targetKcal != null -> stringResource(R.string.main_target_kcal, "%,d".format(s.targetKcal))
        s.failed -> stringResource(R.string.main_load_failed)
        !s.loaded -> stringResource(R.string.main_loading)
        else -> stringResource(R.string.main_target_unknown)
    },
    stringResource(R.string.main_meals_today, s.mealsToday),
    if (s.pending > 0) stringResource(R.string.main_pending, s.pending) else null
)
