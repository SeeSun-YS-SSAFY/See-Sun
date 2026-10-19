package kr.seesun.feature.exercise

import androidx.compose.runtime.Composable
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import kotlinx.serialization.Serializable
import kr.seesun.core.assistant.ScreenCatalog
import kr.seesun.core.ui.AssistantScreen
import kr.seesun.feature.exercise.home.ExerciseHomeRoute
import kr.seesun.feature.exercise.home.HomeDestination
import kr.seesun.feature.exercise.home.RoutineMenuRoute
import kr.seesun.feature.exercise.home.SingleMenuRoute
import kr.seesun.feature.exercise.list.ExerciseListScreenRoute
import kr.seesun.feature.exercise.play.ExercisePlayerRoute
import kr.seesun.feature.exercise.routine.CustomRoutineListRoute
import kr.seesun.feature.exercise.routine.RoutineDetailScreenRoute
import kr.seesun.feature.exercise.routine.RoutineEditorRoute
import kr.seesun.feature.exercise.routine.RoutineOrigin
import kr.seesun.feature.exercise.routine.RoutinePlayerRoute
import kr.seesun.feature.exercise.safety.SafetyReviewRoute
import kr.seesun.feature.exercise.weekly.WeeklyRoute

/**
 * 앱 내비게이션에 붙는 운동 기능 진입점(EX-01). 운동 안의 화면 이동은 [ExerciseNavHost]가 따로 맡아서
 * 앱 그래프에는 이 경로 하나만 둔다(다른 기능 작업과 내비게이션 파일이 겹치지 않게).
 *
 * 시선 비서(설계 voice_assistant_design_v1 §4.7)가 운동 안 화면으로 바로 데려갈 때는 [entry]로 처음 열 화면을 고른다.
 * 안쪽 뒤로가 비면 [exerciseScreen]의 onBack → 말하기 전 화면.
 * @param entry [kr.seesun.core.assistant.ExerciseEntry] 이름(HOME·CATEGORY·PLAY·ROUTINE_*·WEEKLY·SAFETY·SINGLE)
 * @param arg CATEGORY·ROUTINE_RECOMMENDED·ROUTINE_CATEGORY = 카테고리 ID, PLAY = 운동 ID
 * @param title 목록·루틴 제목
 */
@Serializable
data class ExerciseRoute(val entry: String = "HOME", val arg: String? = null, val title: String? = null)

fun NavGraphBuilder.exerciseScreen(onBack: () -> Unit) {
    composable<ExerciseRoute> { e -> ExerciseNavHost(onExit = onBack, start = e.toRoute<ExerciseRoute>().startDestination()) }
}

/** [ExerciseRoute.entry] → 안쪽 그래프의 첫 화면. 인자가 빠졌으면 운동 홈. 분기가 곧 경로표다. */
@Suppress("CyclomaticComplexMethod")
internal fun ExerciseRoute.startDestination(): Any = when (entry) {
    "SINGLE" -> SingleMenuRoute

    "CATEGORY" -> arg?.let { ExerciseListRoute(it, title ?: "운동 목록") } ?: ExerciseHomeRoute

    "PLAY" -> arg?.let { ExercisePlayRoute(it) } ?: ExerciseHomeRoute

    "ROUTINE_MENU" -> RoutineMenuRoute

    "ROUTINE_RECOMMENDED" -> arg?.let { RoutinePlayRoute(title ?: "추천 루틴", categoryId = it, origin = RoutineOrigin.HOME.name) }
        ?: RoutineMenuRoute

    "ROUTINE_CATEGORY" -> arg?.let { RoutinePlayRoute(title ?: "루틴", categoryId = it, origin = RoutineOrigin.MENU.name) }
        ?: RoutineMenuRoute

    "ROUTINE_CUSTOM" -> CustomRoutineListRoute

    "WEEKLY" -> ExerciseWeeklyRoute

    "SAFETY" -> ExerciseSafetyRoute

    else -> ExerciseHomeRoute
}

/** 마이페이지 [운동](EX-07)에서 주간 운동 기록부터 열 때 쓰는 진입점. 앱 그래프에 [exerciseWeeklyScreen]으로 붙인다. */
@Serializable
data object ExerciseWeeklyEntryRoute

fun NavGraphBuilder.exerciseWeeklyScreen(onBack: () -> Unit) {
    composable<ExerciseWeeklyEntryRoute> { ExerciseNavHost(onExit = onBack, startAtWeekly = true) }
}

// ── 운동 안 화면 경로 ─────────────────────────────────────────────────
@Serializable
data object ExerciseHomeRoute

@Serializable
data object SingleMenuRoute

@Serializable
data object RoutineMenuRoute

@Serializable
data class ExerciseListRoute(val source: String, val title: String)

@Serializable
data class ExercisePlayRoute(val exerciseId: String)

/** @param origin 들어온 곳([kr.seesun.feature.exercise.routine.RoutineOrigin] 이름) — 끝난 뒤 버튼 이름·가는 곳(R-14). */
@Serializable
data class RoutinePlayRoute(val title: String, val categoryId: String? = null, val playlistId: String? = null, val origin: String = "MENU")

@Serializable
data object CustomRoutineListRoute

@Serializable
data class RoutineDetailRoute(val playlistId: String)

@Serializable
data class RoutineEditRoute(val playlistId: String? = null)

@Serializable
data object ExerciseWeeklyRoute

@Serializable
data object ExerciseSafetyRoute

/**
 * 두 번 누르기 방지: 이 화면이 아직 맨 위일 때만 이동한다(QA: 빠르게 두 번 눌러 같은 화면이 두 번 쌓이는 문제).
 * 수명 주기(RESUMED) 대신 백스택 맨 위인지로 본다 — 앱 그래프 안에 중첩된 NavHost에서는 화면 전환 중
 * RESUMED가 늦게 와서 첫 탭이 무시되는 문제가 있었다(실기동 QA에서 발견).
 */
@Composable
fun ExerciseNavHost(
    onExit: () -> Unit,
    startAtWeekly: Boolean = false,
    nav: NavHostController = rememberNavController(),
    start: Any = if (startAtWeekly) ExerciseWeeklyRoute else ExerciseHomeRoute
) {
    fun NavBackStackEntry.whenResumed(block: () -> Unit) {
        if (nav.currentBackStackEntry?.id == id) block()
    }

    fun NavHostController.up(entry: NavBackStackEntry, onExit: () -> Unit) = entry.whenResumed {
        if (!popBackStack()) onExit()
    }

    NavHost(navController = nav, startDestination = start) {
        composable<ExerciseHomeRoute> { entry ->
            AssistantScreen(ScreenCatalog.EXERCISE_HOME)
            ExerciseHomeRoute(
                viewModel = hiltViewModel(),
                onBack = { entry.whenResumed(onExit) },
                onOpen = { dest ->
                    entry.whenResumed {
                        when (dest) {
                            is HomeDestination.Category -> nav.navigate(ExerciseListRoute(dest.id, dest.name))

                            is HomeDestination.RecommendedRoutine ->
                                nav.navigate(
                                    RoutinePlayRoute(title = dest.title, categoryId = dest.categoryId, origin = RoutineOrigin.HOME.name)
                                )

                            HomeDestination.Single -> nav.navigate(SingleMenuRoute)

                            HomeDestination.Routine -> nav.navigate(RoutineMenuRoute)

                            HomeDestination.Weekly -> nav.navigate(ExerciseWeeklyRoute)

                            HomeDestination.Safety -> nav.navigate(ExerciseSafetyRoute)
                        }
                    }
                }
            )
        }
        composable<SingleMenuRoute> { entry ->
            AssistantScreen(ScreenCatalog.EXERCISE_SINGLE)
            SingleMenuRoute(
                viewModel = hiltViewModel(),
                onBack = { nav.up(entry, onExit) },
                onOpenList = { source, title -> entry.whenResumed { nav.navigate(ExerciseListRoute(source, title)) } }
            )
        }
        composable<ExerciseListRoute> { entry ->
            AssistantScreen(ScreenCatalog.EXERCISE_LIST, title = entry.toRoute<ExerciseListRoute>().title)
            ExerciseListScreenRoute(
                viewModel = hiltViewModel(),
                onBack = { nav.up(entry, onExit) },
                onOpen = { ex -> entry.whenResumed { nav.navigate(ExercisePlayRoute(ex.id)) } }
            )
        }
        composable<ExercisePlayRoute> { entry ->
            ExercisePlayerRoute(viewModel = hiltViewModel(), safety = hiltViewModel(), onClose = { nav.up(entry, onExit) })
        }
        composable<RoutineMenuRoute> { entry ->
            AssistantScreen(ScreenCatalog.ROUTINE_MENU)
            RoutineMenuRoute(
                viewModel = hiltViewModel(),
                onBack = { nav.up(entry, onExit) },
                onStartCategoryRoutine = { id, title ->
                    entry.whenResumed { nav.navigate(RoutinePlayRoute(title = title, categoryId = id, origin = RoutineOrigin.MENU.name)) }
                },
                onOpenCustom = { entry.whenResumed { nav.navigate(CustomRoutineListRoute) } }
            )
        }
        composable<CustomRoutineListRoute> { entry ->
            CustomRoutineListRoute(
                viewModel = hiltViewModel(),
                onBack = { nav.up(entry, onExit) },
                onOpen = { r -> entry.whenResumed { r.playlistId?.let { nav.navigate(RoutineDetailRoute(it)) } } },
                onCreate = { entry.whenResumed { nav.navigate(RoutineEditRoute()) } }
            )
        }
        composable<RoutineDetailRoute> { entry ->
            RoutineDetailScreenRoute(
                viewModel = hiltViewModel(),
                onBack = { nav.up(entry, onExit) },
                onStart = { r ->
                    entry.whenResumed {
                        nav.navigate(RoutinePlayRoute(title = r.title, playlistId = r.playlistId, origin = RoutineOrigin.CUSTOM.name))
                    }
                },
                onEdit = { id -> entry.whenResumed { nav.navigate(RoutineEditRoute(id)) } }
            )
        }
        composable<RoutineEditRoute> { entry ->
            RoutineEditorRoute(viewModel = hiltViewModel(), onClose = { nav.up(entry, onExit) })
        }
        composable<RoutinePlayRoute> { entry ->
            val route = entry.toRoute<RoutinePlayRoute>()
            val origin = RoutineOrigin.of(route.origin)
            RoutinePlayerRoute(
                viewModel = hiltViewModel(),
                safety = hiltViewModel(),
                title = route.title,
                origin = origin,
                onClose = { nav.up(entry, onExit) },
                // R-14: 버튼 이름과 가는 곳을 맞춘다. 나만의 루틴은 루틴 상세를 지나 목록으로.
                onDone = {
                    entry.whenResumed {
                        val popped = origin == RoutineOrigin.CUSTOM && nav.popBackStack(CustomRoutineListRoute, inclusive = false)
                        if (!popped && !nav.popBackStack()) onExit()
                    }
                }
            )
        }
        composable<ExerciseSafetyRoute> { entry ->
            AssistantScreen(ScreenCatalog.EXERCISE_SAFETY)
            SafetyReviewRoute(viewModel = hiltViewModel(), onClose = { nav.up(entry, onExit) })
        }
        composable<ExerciseWeeklyRoute> { entry ->
            AssistantScreen(ScreenCatalog.EXERCISE_WEEKLY)
            WeeklyRoute(viewModel = hiltViewModel(), onBack = { nav.up(entry, onExit) })
        }
    }
}
