package kr.seesun.app

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavHostController
import java.time.Clock
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.TemporalAdjusters
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kr.seesun.core.a11y.TalkBackMonitor
import kr.seesun.core.assistant.AssistantAction
import kr.seesun.core.assistant.AssistantEngine
import kr.seesun.core.assistant.AssistantFacts
import kr.seesun.core.assistant.AssistantReply
import kr.seesun.core.assistant.AssistantSession
import kr.seesun.core.assistant.AssistantText
import kr.seesun.core.assistant.Decision
import kr.seesun.core.assistant.Destination
import kr.seesun.core.assistant.ExerciseCatalog
import kr.seesun.core.assistant.ExerciseEntry
import kr.seesun.core.assistant.GoalFact
import kr.seesun.core.assistant.LocalCommand
import kr.seesun.core.assistant.ScreenMode
import kr.seesun.core.assistant.ScreenVoiceContext
import kr.seesun.core.assistant.StripButton
import kr.seesun.core.assistant.WeeklyFact
import kr.seesun.core.data.repository.ExerciseContentRepository
import kr.seesun.core.data.repository.ProfileRepository
import kr.seesun.core.data.repository.SessionRepository
import kr.seesun.core.feedback.Haptics
import kr.seesun.core.network.ApiResult
import kr.seesun.core.ui.AssistantHost
import kr.seesun.core.ui.AssistantScreenEntry
import kr.seesun.core.ui.AssistantStrip
import kr.seesun.core.ui.AutoListenEnvironment
import kr.seesun.core.ui.GuidanceVoice
import kr.seesun.core.voice.recognition.ScreenSpeechLog
import kr.seesun.core.voice.settings.AutoListenMode
import kr.seesun.core.voice.settings.VoicePreferences
import kr.seesun.core.voice.tts.Speaker
import kr.seesun.core.voice.tts.VoiceSpeed
import kr.seesun.feature.diet.DietRoute
import kr.seesun.feature.exercise.ExerciseRoute
import kr.seesun.feature.exercise.home.ExerciseOrder
import kr.seesun.feature.main.MainRoute
import kr.seesun.feature.mypage.AppVersionRoute
import kr.seesun.feature.mypage.AppVoiceRoute
import kr.seesun.feature.mypage.ContactRoute
import kr.seesun.feature.mypage.DataResetRoute
import kr.seesun.feature.mypage.DeleteAccountRoute
import kr.seesun.feature.mypage.HardwareButtonRoute
import kr.seesun.feature.mypage.HealthEditRoute
import kr.seesun.feature.mypage.HowToRoute
import kr.seesun.feature.mypage.MyPageRoute
import kr.seesun.feature.mypage.ProfileEditRoute
import kr.seesun.feature.mypage.ProfileValueRoute
import kr.seesun.feature.mypage.SettingsGroupRoute
import kr.seesun.feature.mypage.SettingsRoute
import kr.seesun.feature.mypage.VoiceAutoRoute

/**
 * 시선 비서의 앱 쪽(설계 voice_assistant_design_v1): 규칙 엔진([AssistantSession])이 낸 일을 실제 화면 이동·설정·전화 앱 열기로 바꾼다.
 * - 같은 마이크·같은 처리 순서를 모든 화면에 둔다(§2.3 혼합형). 화면은 [kr.seesun.core.ui.AssistantScreen] 한 줄로 자기 ID를 알린다.
 * - 이동은 지금 화면 위에 쌓고, 이동 전 화면 하나만 기억한다(§4.7 「아까 화면으로」).
 * - 건강 정보는 저장하지 않는다 — 값이 채워진 확인 화면을 열 뿐이다. 전화는 「네」를 받은 뒤 전화 앱에 번호만 채운다(통화 버튼은 사용자가).
 * - 발화 원문은 로그에 남기지 않는다(의도 ID·행동 종류만, §4.5).
 * 모든 호출은 메인 스레드(화면·인식 콜백)에서 온다.
 */
@Suppress("LongParameterList", "TooManyFunctions")
class AppAssistant(
    private val activity: ComponentActivity,
    private val speaker: Speaker,
    private val prefs: VoicePreferences,
    private val profile: ProfileRepository,
    private val content: ExerciseContentRepository,
    private val haptics: Haptics,
    private val env: AutoListenEnvironment,
    private val session: SessionRepository,
    private val clock: Clock
) : AssistantHost {
    private val scope = activity.lifecycleScope
    private val talkBack = TalkBackMonitor(activity)
    private val voice = GuidanceVoice(speaker)

    @Volatile private var catalog: ExerciseCatalog = ExerciseCatalog.EMPTY
    private val engine = AssistantEngine { catalog }
    private val brain = AssistantSession(engine, Facts(), clock::millis)

    private val _strip = MutableStateFlow<AssistantStrip?>(null)
    override val strip: StateFlow<AssistantStrip?> = _strip.asStateFlow()

    private val screens = ArrayDeque<AssistantScreenEntry>()
    private val listeners = ArrayDeque<Pair<Any, (List<String>) -> Boolean>>()
    private var nav: NavHostController? = null
    private var origin: NavBackStackEntry? = null
    private var screenTitle: String? = null

    /** 가입을 마친 사용자만 이동·설정을 받는다. 그 전(온보딩)에는 안전 안내만. */
    private val enabled: Boolean get() = !session.state.value.needsOnboarding

    fun attach(controller: NavHostController) {
        nav = controller
        if (catalog.isEmpty) loadCatalog()
    }

    fun detach(controller: NavHostController) {
        if (nav === controller) nav = null
        origin = null
    }

    /** 화면 틀이 제목을 알린다(표에 없는 화면의 「여기 어디야」). */
    fun onScreenTitle(title: String) {
        screenTitle = title
    }

    /**
     * 기기 시험용 통로: 받아쓴 말([text])을 맨 위 듣기 입구에 넣는다(마이크 없이 같은 처리 순서). 입구가 없으면(재생 중 등)
     * 지금 화면 문맥으로 바로 처리한다 — 사용자가 마이크를 눌러 말한 것과 같다.
     */
    fun hearText(text: String): Boolean {
        val candidates = listOf(text)
        listeners.lastOrNull()?.let { (_, handler) -> return handler(candidates) }
        return before(candidates, this) || after(candidates, this)
    }

    override fun hearMenu(candidates: List<String>, local: List<LocalCommand>, runLocal: (Int) -> Unit, owner: Any): Boolean {
        val screen = current().copy(local = local)
        brain.preempt(candidates, screen)?.let {
            apply(it, owner)
            return true
        }
        if (!enabled) return false
        return when (val d = engine.decide(candidates, screen)) {
            Decision.None -> false

            is Decision.Local -> {
                _strip.value = null
                haptics.tick()
                runLocal(d.index)
                true
            }

            else -> resolveLater(candidates, screen, owner)
        }
    }

    override fun before(candidates: List<String>, owner: Any, valueCapture: Boolean): Boolean {
        val base = current()
        val screen = if (valueCapture) base.copy(mode = ScreenMode.VALUE_CAPTURE) else base
        brain.preempt(candidates, screen)?.let {
            apply(it, owner)
            return true
        }
        if (!enabled) return false
        val wake = candidates.any { AssistantText.stripWake(it).second }
        if (!valueCapture && !wake) return false
        if (engine.decide(candidates, screen.copy(local = emptyList())) is Decision.None) return false
        return resolveLater(candidates, screen.copy(local = emptyList()), owner)
    }

    override fun after(candidates: List<String>, owner: Any): Boolean {
        if (!enabled) return false
        val screen = current().copy(local = emptyList())
        if (engine.decide(candidates, screen) is Decision.None) return false
        return resolveLater(candidates, screen, owner)
    }

    override fun press(button: StripButton) {
        val owner = _strip.value?.owner ?: this
        val candidates = listOf(button.answer)
        if (!before(candidates, owner)) after(candidates, owner)
    }

    override fun enter(entry: AssistantScreenEntry): () -> Unit {
        val previous = screens.lastOrNull()?.context?.screenId
        screens.addLast(entry)
        if (previous != entry.context.screenId) onScreenChanged()
        return {
            screens.remove(entry)
            onScreenChanged()
        }
    }

    override fun listen(owner: Any, handler: (List<String>) -> Boolean): () -> Unit {
        val item = owner to handler
        listeners.addLast(item)
        // 마이크 입구 없이 받은 말(재생 중 안전 표현)의 결과 띠는 새로 생긴 입구(멈춘 동안 듣기) 옆에 그린다.
        _strip.value?.let { s -> if (s.owner === this) _strip.value = s.copy(owner = owner) }
        return { listeners.remove(item) }
    }

    private fun onScreenChanged() {
        brain.onScreenChanged()
        val s = _strip.value ?: return
        // 묻는 중인 안전 질문(119·109)은 화면이 바뀌어도 남긴다. 그 밖의 결과 띠는 화면을 떠나면 지운다(§4.5).
        if (!brain.asking) {
            _strip.value = null
        } else if (s.owner !== this) {
            _strip.value = s.copy(owner = this)
        }
    }

    private fun current(): ScreenVoiceContext = screens.lastOrNull()?.context ?: ScreenVoiceContext(title = screenTitle)

    private fun resolveLater(candidates: List<String>, screen: ScreenVoiceContext, owner: Any): Boolean {
        scope.launch { brain.resolve(candidates, screen)?.let { apply(it, owner) } }
        return true
    }

    private fun apply(reply: AssistantReply, owner: Any) {
        // 말 원문은 남기지 않는다(§4.5) — 의도 ID와 행동 종류만.
        Log.i(TAG, "intent=${reply.intent?.code} actions=${reply.actions.map { it::class.simpleName }}")
        _strip.value = AssistantStrip(reply.heard, reply.say, reply.buttons, owner)
        haptics.tick()
        reply.actions.filterNot { it.leavesScreen() }.forEach(::act)
        reply.say?.let { voice.auto(it, talkBack.isEnabled()) }
        reply.actions.filter { it.leavesScreen() }.forEach(::act)
    }

    private fun AssistantAction.leavesScreen() = this is AssistantAction.Navigate || this == AssistantAction.Home ||
        this == AssistantAction.Back || this == AssistantAction.ToOrigin || this is AssistantAction.Dial

    private fun act(action: AssistantAction) {
        when (action) {
            is AssistantAction.Navigate -> navigate(action.destination)

            AssistantAction.Back -> activity.onBackPressedDispatcher.onBackPressed()

            AssistantAction.Home -> home()

            AssistantAction.ToOrigin -> toOrigin()

            AssistantAction.StopListening -> {
                speaker.stop()
                env.requestRest()
            }

            AssistantAction.PausePlayback -> screens.lastOrNull()?.onSafetyStop?.invoke()

            is AssistantAction.RunLocal -> Unit

            is AssistantAction.Dial -> dial(action.number)

            is AssistantAction.SetSpeed -> VoiceSpeed.entries.firstOrNull { it.name == action.speed }?.let(prefs::setSpeed)

            is AssistantAction.SetAutoListen ->
                prefs.setListenMode(if (action.on) AutoListenMode.AFTER_QUESTION_AND_VOICE else AutoListenMode.OFF)
        }
    }

    /** 전화 앱에 번호만 채워 연다(ACTION_DIAL — 통화 권한 없음, 통화 버튼은 사용자가 누른다). */
    private fun dial(number: String) {
        try {
            activity.startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:$number")))
        } catch (e: ActivityNotFoundException) {
            Log.w(TAG, "no dialer: ${e.javaClass.simpleName}")
        }
    }

    private fun home() {
        val nav = nav ?: return
        origin = null
        if (!nav.popBackStack(MainRoute, inclusive = false)) nav.navigate(MainRoute) { popUpTo(0) { inclusive = true } }
    }

    private fun toOrigin() {
        val nav = nav ?: return
        val target = origin ?: return
        while (nav.currentBackStackEntry !== target) {
            if (!nav.popBackStack()) break
        }
        origin = null
    }

    private fun originAlive(): Boolean = origin?.lifecycle?.currentState?.isAtLeast(Lifecycle.State.CREATED) == true

    /** 목적지를 지금 화면 위에 쌓는다. 똑같은 화면이 이미 뒤에 있으면 그 화면까지 걷어낸다(§4.7 중복 방지). */
    private fun go(route: Any) {
        val nav = nav ?: return
        origin = nav.currentBackStackEntry
        nav.navigate(route) {
            popUpTo(route) { inclusive = true }
            launchSingleTop = true
        }
    }

    private fun navigate(d: Destination) {
        when (d) {
            Destination.Main -> home()
            is Destination.Exercise -> scope.launch { go(exerciseRoute(d)) }
            Destination.MyPage -> go(MyPageRoute)
            is Destination.ProfileEdit -> go(d.field?.let { ProfileValueRoute(it, d.value) } ?: ProfileEditRoute)
            Destination.HealthEdit -> go(HealthEditRoute)
            Destination.Settings -> go(SettingsRoute)
            is Destination.SettingsGroup -> go(SettingsGroupRoute(d.group))
            Destination.VoiceAuto -> go(VoiceAutoRoute)
            Destination.AppVoice -> go(AppVoiceRoute)
            Destination.HardwareButton -> go(HardwareButtonRoute)
            Destination.Contact -> go(ContactRoute)
            Destination.DeleteAccount -> go(DeleteAccountRoute)
            Destination.DataReset -> go(DataResetRoute)
            Destination.AppVersion -> go(AppVersionRoute)
            Destination.HowTo -> go(HowToRoute)
            Destination.Diet -> go(DietRoute)
        }
    }

    /** 운동 그래프 경로(§5.1): 카테고리 이름 → ID, 추천 루틴 → 목표별 첫 카테고리(v1.8). */
    private suspend fun exerciseRoute(d: Destination.Exercise): ExerciseRoute = when (d.entry) {
        ExerciseEntry.CATEGORY -> categoryOf(d.arg).let { (id, name) -> ExerciseRoute(d.entry.name, id, name) }
        ExerciseEntry.ROUTINE_CATEGORY -> categoryOf(d.arg).let { (id, name) -> ExerciseRoute(d.entry.name, id, "$name 루틴") }
        ExerciseEntry.ROUTINE_RECOMMENDED -> ExerciseRoute(d.entry.name, recommendedCategory(), "추천 루틴")
        ExerciseEntry.PLAY -> ExerciseRoute(d.entry.name, d.arg, d.title)
        else -> ExerciseRoute(d.entry.name)
    }

    private suspend fun categoryOf(short: String?): Pair<String, String> {
        val word = short.orEmpty()
        val list = (content.categories() as? ApiResult.Success)?.data?.data.orEmpty()
        val hit = list.firstOrNull { ExerciseOrder.shortName(it.name) == word || it.name.contains(word) }
        return hit?.let { it.id to it.name } ?: ((BUNDLED_CATEGORY_IDS[word] ?: "1") to "$word 운동")
    }

    private suspend fun recommendedCategory(): String {
        val me = (profile.me() as? ApiResult.Success)?.data
        return ExerciseOrder.order(me?.goal, me?.recommendedExerciseCategories.orEmpty()).first()
    }

    private fun loadCatalog() {
        scope.launch {
            val list = (content.exercises(null) as? ApiResult.Success)?.data?.data ?: return@launch
            catalog = ExerciseCatalog.of(list.map { it.id to it.name })
        }
    }

    private inner class Facts : AssistantFacts {
        override suspend fun weekly(lastWeek: Boolean): WeeklyFact? {
            val monday = LocalDate.now(clock).with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)).let {
                if (lastWeek) it.minusWeeks(1) else it
            }
            val week = (content.weekly(monday.toString()) as? ApiResult.Success)?.data ?: return null
            return WeeklyFact(week.sessionCount, week.totalMinutes)
        }

        override suspend fun goal(): GoalFact? {
            val me = (profile.me() as? ApiResult.Success)?.data ?: return null
            return GoalFact(GOAL_LABELS[me.goal], me.targetKcal)
        }

        override fun speed(): String = prefs.speed.value.name

        override fun autoListenOn(): Boolean = prefs.listenMode.value != AutoListenMode.OFF

        override fun lastSpoken(): String? = ScreenSpeechLog.lastSpoken()

        override fun hasOrigin(): Boolean = originAlive()
    }

    private companion object {
        const val TAG = "SeesunAssistant"
        val GOAL_LABELS = mapOf("LOSE_WEIGHT" to "체중 감량", "GAIN_MUSCLE" to "근육 증진", "MAINTAIN" to "건강 유지")

        /** 서버에 닿지 못할 때 기기 내장 운동 정보의 카테고리 ID(1 근력, 2 유산소, 3 유연성, 4 균형). */
        val BUNDLED_CATEGORY_IDS = mapOf("근력" to "1", "유산소" to "2", "유연성" to "3", "균형" to "4")
    }
}
