package kr.seesun.feature.exercise.weekly

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.Clock
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.format.TextStyle
import java.time.temporal.TemporalAdjusters
import java.util.Locale
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kr.seesun.core.data.repository.ExerciseContentRepository
import kr.seesun.core.data.repository.ExerciseLogRepository
import kr.seesun.core.designsystem.component.ActionRow
import kr.seesun.core.designsystem.component.ActionSpec
import kr.seesun.core.designsystem.component.BodyText
import kr.seesun.core.designsystem.component.BottomActions
import kr.seesun.core.designsystem.component.ButtonKind
import kr.seesun.core.designsystem.component.LiveStatusText
import kr.seesun.core.designsystem.component.ScreenScaffold
import kr.seesun.core.designsystem.theme.SeeSunTheme
import kr.seesun.core.network.ApiResult
import kr.seesun.core.network.model.ExerciseWeekDto
import kr.seesun.core.ui.EntryGuidance
import kr.seesun.feature.exercise.ui.ActionButton
import kr.seesun.feature.exercise.ui.SectionHeading

data class WeeklyUi(
    val weekStart: LocalDate,
    val loading: Boolean = true,
    val week: ExerciseWeekDto? = null,
    val error: String? = null,
    val pending: Int = 0,
    val isCurrentWeek: Boolean = true
)

/**
 * EX-07 주간 운동 레포트: 이번 주 횟수·시간(서버 유효 세션 기준)과 요일별 목록.
 * 막대는 장식이고, 같은 내용을 요일별 글자로 함께 둔다(04 §4 "그래프는 낭독용 목록 병행").
 * 마이페이지의 [운동]도 이 화면(경로 ExerciseWeeklyRoute)을 쓸 수 있다.
 */
@HiltViewModel
class WeeklyViewModel @Inject constructor(
    private val content: ExerciseContentRepository,
    logs: ExerciseLogRepository,
    private val clock: Clock
) : ViewModel() {
    private val thisMonday = LocalDate.now(clock).with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
    private val state = MutableStateFlow(WeeklyUi(thisMonday))

    val ui: StateFlow<WeeklyUi> = combine(state, logs.observePendingCount()) { s, pending -> s.copy(pending = pending) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_MS), state.value)

    init {
        load(thisMonday)
    }

    fun load(monday: LocalDate = state.value.weekStart) {
        state.value = WeeklyUi(monday, loading = true, isCurrentWeek = monday == thisMonday)
        viewModelScope.launch {
            state.value = when (val r = content.weekly(monday.toString())) {
                is ApiResult.Success -> state.value.copy(loading = false, week = r.data)

                is ApiResult.Failure -> state.value.copy(
                    loading = false,
                    error = if (r.failure.httpStatus == HTTP_UNAUTHORIZED) "운동 기록은 로그인한 뒤에 볼 수 있어요." else r.failure.ttsMessage
                )
            }
        }
    }

    fun previousWeek() = load(state.value.weekStart.minusWeeks(1))

    fun nextWeek() {
        if (!state.value.isCurrentWeek) load(state.value.weekStart.plusWeeks(1))
    }

    private companion object {
        const val STOP_MS = 5_000L
        const val HTTP_UNAUTHORIZED = 401
    }
}

@Composable
fun WeeklyRoute(viewModel: WeeklyViewModel, onBack: () -> Unit) {
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    WeeklyScreen(ui, onBack, viewModel::previousWeek, viewModel::nextWeek, { viewModel.load() })
}

@Composable
fun WeeklyScreen(ui: WeeklyUi, onBack: () -> Unit, onPrevious: () -> Unit, onNext: () -> Unit, onRetry: () -> Unit) {
    val title = if (ui.isCurrentWeek) "이번 주 운동" else "${ui.weekStart.monthValue}월 ${ui.weekStart.dayOfMonth}일 주 운동"
    ScreenScaffold(title = title, onBack = onBack) {
        val week = ui.week
        when {
            ui.loading -> LiveStatusText("운동 기록을 불러오는 중이에요.")

            week == null -> {
                LiveStatusText(ui.error ?: "운동 기록을 불러오지 못했어요.")
                ActionButton(text = "다시 시도", onClick = onRetry)
            }

            else -> {
                LiveStatusText(week.ttsMessage ?: "운동 ${week.sessionCount}회, 총 ${week.totalMinutes}분이에요.")
                BodyText("10초 이상 끝까지 한 운동만 세요.")
                SectionHeading("요일별 기록")
                val maxSeconds = week.days.maxOfOrNull { it.seconds }?.coerceAtLeast(1) ?: 1
                week.days.forEach { day -> DayRow(day.date, day.weekday, day.sessionCount, day.seconds, maxSeconds) }
            }
        }
        if (ui.pending > 0) BodyText("기기에 있는 기록 ${ui.pending}건은 아직 서버에 보내지 않아 여기에 빠져 있어요. 인터넷에 연결되면 반영돼요.")
        BottomActions {
            ActionRow(
                listOfNotNull(
                    ActionSpec("지난주 보기", onPrevious, ButtonKind.OUTLINE),
                    if (ui.isCurrentWeek) null else ActionSpec("다음 주 보기", onNext, ButtonKind.OUTLINE)
                )
            )
        }
    }
    val week = ui.week
    if (!ui.loading) {
        EntryGuidance(
            "weekly:${ui.weekStart}:${week != null}",
            if (week == null) {
                "$title. ${ui.error ?: "운동 기록을 불러오지 못했어요."}"
            } else {
                "$title. ${week.ttsMessage ?: "운동 ${week.sessionCount}회, 총 ${week.totalMinutes}분이에요."} 지난주 보기를 누르면 앞 주 기록을 들을 수 있어요."
            }
        )
    }
}

@Composable
private fun DayRow(date: String, weekday: String?, count: Int, seconds: Long, maxSeconds: Long) {
    val d = runCatching { LocalDate.parse(date) }.getOrNull()
    // 서버는 "월요일" 형식으로 준다(seesun_rules REPORT.weekday_names).
    val dayName = weekday ?: d?.dayOfWeek?.getDisplayName(TextStyle.FULL, Locale.KOREAN).orEmpty()
    val label = if (d != null) "$dayName ${d.monthValue}월 ${d.dayOfMonth}일" else dayName
    // 서버 total_minutes와 같게 반올림
    val minutes = (seconds + SECONDS_PER_MINUTE / 2) / SECONDS_PER_MINUTE
    val summary = if (count == 0) "$label: 운동 안 함" else "$label: ${count}회, ${minutes}분"
    Column(
        modifier = Modifier.fillMaxWidth().semantics(mergeDescendants = true) { contentDescription = summary },
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Text(summary, style = SeeSunTheme.typography.body, color = SeeSunTheme.colors.onBackground)
        Box(
            modifier = Modifier
                .fillMaxWidth(fraction = (seconds.toFloat() / maxSeconds).coerceIn(0f, 1f).coerceAtLeast(MIN_BAR))
                .height(12.dp)
                .background(SeeSunTheme.colors.primary, RoundedCornerShape(6.dp))
                .padding(0.dp)
                .clearAndSetSemantics { }
        )
    }
}

private const val SECONDS_PER_MINUTE = 60
private const val MIN_BAR = 0.02f
