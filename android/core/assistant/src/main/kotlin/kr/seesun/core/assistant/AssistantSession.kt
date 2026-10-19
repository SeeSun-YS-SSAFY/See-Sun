package kr.seesun.core.assistant

import java.util.Locale
import kr.seesun.core.voice.parse.Answer
import kr.seesun.core.voice.parse.AnswerParser

/** 비서가 그 자리에서 답할 때 읽는 기기·서버 값(앱이 넣는다). 실패하면 null. */
interface AssistantFacts {
    suspend fun weekly(lastWeek: Boolean): WeeklyFact?

    suspend fun goal(): GoalFact?

    /** 지금 말 속도(SLOW / NORMAL / FAST). */
    fun speed(): String

    fun autoListenOn(): Boolean

    /** 앱이 마지막으로 한 말 또는 화면 안내(U-03). */
    fun lastSpoken(): String?

    /** 비서가 이동시키기 전 화면이 아직 뒤에 있는가(U-08). */
    fun hasOrigin(): Boolean
}

data class WeeklyFact(val sessions: Int, val minutes: Int)

/** @param goal 낭독용 목표 이름(「체중 감량」). null이면 목표 없음. */
data class GoalFact(val goal: String?, val targetKcal: Int?)

/** 비서가 앱에 시키는 일. 건강 정보를 저장하는 일은 없다 — 기록은 늘 화면의 확인 단계를 거친다(§3.1, R3). */
sealed interface AssistantAction {
    data class Navigate(val destination: Destination) : AssistantAction

    data object Back : AssistantAction

    data object Home : AssistantAction

    data object ToOrigin : AssistantAction

    /** 앱 말을 멈추고 대기 듣기를 쉰다(U-06). */
    data object StopListening : AssistantAction

    /** 운동 재생 중이면 멈춘다(X-02·X-03). */
    data object PausePlayback : AssistantAction

    /** 이 화면 명령 실행([ScreenVoiceContext.local]의 차례). */
    data class RunLocal(val index: Int) : AssistantAction

    /** 전화 앱에 번호를 채워 연다(사용자가 통화 버튼을 누른다). **「네」 확인 뒤에만** 나온다. */
    data class Dial(val number: String) : AssistantAction

    data class SetSpeed(val speed: String) : AssistantAction

    data class SetAutoListen(val on: Boolean) : AssistantAction
}

/** 결과 띠의 버튼(누르면 [answer]를 말한 것과 같다 — TalkBack 사용자가 말 대신 고른다). */
data class StripButton(val label: String, val answer: String)

/**
 * 비서 한 번의 결과(§4.5 결과 띠 「내 말 / 시선」).
 * @property heard 들은 말(화면에만, 로그에 남기지 않는다)
 * @property say 시선이 할 말. null이면 말 없이 화면 명령만 실행
 * @property intent 개발용 통계에 남길 의도 ID(발화 원문은 남기지 않는다)
 */
data class AssistantReply(
    val heard: String,
    val say: String?,
    val actions: List<AssistantAction> = emptyList(),
    val buttons: List<StripButton> = emptyList(),
    val intent: IntentId? = null
)

/**
 * 비서 대화 상태(처리 순서 1단계 「대기 중인 질문의 답」 + 결과 만들기). 화면·안드로이드와 무관한 순수 상태 기계라 JVM에서 시험한다.
 * 확인이 필요한 일(전화 걸기·자동 듣기 끄기·애매한 이동)은 질문만 하고, 「네」를 받은 뒤에야 [AssistantAction]을 낸다.
 */
class AssistantSession(
    private val engine: AssistantEngine,
    private val facts: AssistantFacts,
    private val clock: () -> Long = System::currentTimeMillis
) {
    private sealed interface Pending {
        data class Go(val destination: Destination) : Pending

        data class Choose(val options: List<Destination>) : Pending

        data class Dial(val number: String, val kind: SafetyKind) : Pending

        data class AutoListen(val on: Boolean) : Pending

        data class HelpMore(val examples: List<String>, val offset: Int) : Pending

        data class Undo(val previousSpeed: String) : Pending

        data object LeaveInput : Pending
    }

    private var pending: Pending? = null
    private var pendingAt = 0L

    /** 마지막으로 한 답(「방금 뭐라고 했어?」에 앱 발화 기록이 없을 때). */
    var lastReply: String? = null
        private set

    /** 묻는 중인가(결과 띠 버튼·자동 듣기 판단용). */
    val asking: Boolean get() = livePending() != null

    /** 0단계(안전) + 1단계(대기 중인 질문의 답). 해당하지 않으면 null — 이어서 화면 명령·[resolve]로. */
    fun preempt(candidates: List<String>, screen: ScreenVoiceContext): AssistantReply? {
        val heard = candidates.firstOrNull()?.trim().orEmpty()
        if (heard.isEmpty()) return null
        candidates.firstNotNullOfOrNull { SafetyRules.detect(AssistantText.stripWake(it).first, screen.mode) }?.let {
            return remember(safety(heard, it))
        }
        val p = livePending() ?: return null
        return answer(p, heard, candidates)?.let(::remember)
    }

    /** 2~4단계. 아무것도 아니면 null(화면이 「…라고 들었는데」 안내를 한다, 값 입력 칸이면 값으로 쓴다). */
    suspend fun resolve(candidates: List<String>, screen: ScreenVoiceContext): AssistantReply? {
        val heard = candidates.firstOrNull()?.trim().orEmpty()
        if (heard.isEmpty()) return null
        val reply = when (val d = engine.decide(candidates, screen)) {
            is Decision.Safety -> safety(heard, d.kind)

            is Decision.Local -> AssistantReply(heard, null, listOf(AssistantAction.RunLocal(d.index)))

            is Decision.Command -> command(heard, d.id, screen)

            is Decision.Intent -> intent(heard, d.match, d.confident)

            is Decision.Ambiguous -> choose(heard, d.options.mapNotNull(AssistantRoutes::of))

            Decision.OutOfScope -> AssistantReply(
                heard,
                "${AssistantReplies.OUT_OF_SCOPE} ${AssistantReplies.HELP_LINE}",
                intent = IntentId.OUT_OF_SCOPE
            )

            Decision.None -> null
        }
        return reply?.let { guardPlayback(it, screen) }?.let(::remember)
    }

    /** 운동 재생 중에는 다른 화면으로 데려가지 않는다(기록이 끊기지 않게). 「뒤로」만 화면의 끝내기 확인으로 넘긴다. */
    private fun guardPlayback(reply: AssistantReply, screen: ScreenVoiceContext): AssistantReply {
        if (screen.mode != ScreenMode.PLAYBACK) return reply
        val leaves = reply.actions.any { it is AssistantAction.Navigate || it == AssistantAction.Home || it == AssistantAction.ToOrigin }
        if (!leaves) return reply
        pending = null
        return AssistantReply(reply.heard, AssistantReplies.PLAYBACK_BLOCKED, intent = reply.intent)
    }

    suspend fun hear(candidates: List<String>, screen: ScreenVoiceContext): AssistantReply? =
        preempt(candidates, screen) ?: resolve(candidates, screen)

    /** 화면을 떠났다: 묻던 것(되돌리기는 빼고)을 거둔다. */
    fun onScreenChanged() {
        if (pending !is Pending.Undo) pending = null
    }

    fun clear() {
        pending = null
    }

    private fun remember(reply: AssistantReply): AssistantReply {
        reply.say?.let { lastReply = it }
        return reply
    }

    private fun livePending(): Pending? {
        val p = pending ?: return null
        val limit = if (p is Pending.Undo) UNDO_MS else PENDING_MS
        if (clock() - pendingAt > limit) {
            pending = null
            return null
        }
        return p
    }

    private fun ask(p: Pending) {
        pending = p
        pendingAt = clock()
    }

    // ── 0단계: 안전 ─────────────────────────────────────────
    private fun safety(heard: String, kind: SafetyKind): AssistantReply = when (kind) {
        SafetyKind.EMERGENCY -> {
            ask(Pending.Dial(SafetyWording.EMERGENCY_NUMBER, kind))
            AssistantReply(
                heard,
                SafetyWording.EMERGENCY,
                listOf(AssistantAction.PausePlayback),
                listOf(StripButton(SafetyWording.CALL_119, AssistantReplies.YES), StripButton(SafetyWording.NO_CALL, AssistantReplies.NO)),
                kind.intent
            )
        }

        SafetyKind.CRISIS -> {
            ask(Pending.Dial(SafetyWording.CRISIS_NUMBER, kind))
            AssistantReply(
                heard,
                SafetyWording.CRISIS,
                listOf(AssistantAction.PausePlayback),
                listOf(StripButton(SafetyWording.CALL_109, AssistantReplies.YES), StripButton(SafetyWording.NO_CALL, AssistantReplies.NO)),
                kind.intent
            )
        }

        SafetyKind.MEDICAL -> {
            pending = null
            AssistantReply(heard, SafetyWording.MEDICAL, intent = kind.intent)
        }
    }

    // ── 1단계: 대기 중인 질문의 답 ─────────────────────────────
    private fun answer(p: Pending, heard: String, candidates: List<String>): AssistantReply? {
        if (p is Pending.Undo) return undo(p, heard, candidates)
        if (p is Pending.Choose) {
            pick(p, candidates)?.let { dest ->
                pending = null
                return go(heard, dest)
            }
        }
        val a = candidates.firstNotNullOfOrNull { AnswerParser.parse(it) }
        if (a == null || a == Answer.EDIT) {
            // 답이 아닌 새 말이면 묻던 것을 거두고 보통 처리로(전화 걸기는 「네」 없이는 하지 않는다).
            pending = null
            return null
        }
        if (a == Answer.REPEAT) return AssistantReply(heard, lastReply ?: AssistantReplies.NOTHING_TO_REPEAT)
        pending = null
        val yes = a == Answer.YES
        return when (p) {
            is Pending.Dial -> dial(heard, p, yes)

            is Pending.Go -> if (yes) go(heard, p.destination) else AssistantReply(heard, AssistantReplies.KEEP)

            is Pending.Choose -> AssistantReply(heard, AssistantReplies.KEEP)

            is Pending.AutoListen -> autoListenAnswer(heard, p.on, yes)

            is Pending.HelpMore -> if (yes) helpPage(heard, p.examples, p.offset) else AssistantReply(heard, AssistantReplies.KEEP)

            Pending.LeaveInput -> if (yes) {
                AssistantReply(heard, AssistantReplies.CANCELLED, listOf(AssistantAction.Back), intent = IntentId.CANCEL)
            } else {
                AssistantReply(heard, AssistantReplies.KEEP)
            }

            is Pending.Undo -> null
        }
    }

    private fun dial(heard: String, p: Pending.Dial, yes: Boolean): AssistantReply {
        val crisis = p.kind == SafetyKind.CRISIS
        if (!yes) {
            return AssistantReply(
                heard,
                if (crisis) SafetyWording.CRISIS_DECLINED else SafetyWording.EMERGENCY_DECLINED,
                intent = p.kind.intent
            )
        }
        return AssistantReply(
            heard,
            if (crisis) SafetyWording.CRISIS_DIALED else SafetyWording.EMERGENCY_DIALED,
            listOf(AssistantAction.Dial(p.number)),
            intent = p.kind.intent
        )
    }

    private fun pick(p: Pending.Choose, candidates: List<String>): Destination? {
        candidates.firstNotNullOfOrNull { AssistantText.ordinal(it) }?.let { return p.options.getOrNull(it) }
        val c = candidates.map(AssistantText::compact)
        return p.options.firstOrNull { o -> c.any { it.contains(AssistantText.compact(o.label)) } }
    }

    private fun undo(p: Pending.Undo, heard: String, candidates: List<String>): AssistantReply? {
        val c = candidates.map(AssistantText::compact)
        if (c.none { s -> UNDO_WORDS.any { s.contains(it) } }) return null
        pending = null
        return AssistantReply(
            heard,
            AssistantReplies.UNDONE,
            listOf(AssistantAction.SetSpeed(p.previousSpeed)),
            intent = IntentId.VOICE_SPEED
        )
    }

    // ── 3단계: 어디서나 명령 ──────────────────────────────────
    private fun command(heard: String, id: IntentId, screen: ScreenVoiceContext): AssistantReply = when (id) {
        IntentId.BACK -> AssistantReply(heard, null, listOf(AssistantAction.Back), intent = id)

        IntentId.HOME -> AssistantReply(
            heard,
            AssistantReplies.GOING.fill(Josa.ro(Destination.Main.label)),
            listOf(AssistantAction.Home),
            intent = id
        )

        IntentId.REPEAT -> AssistantReply(heard, facts.lastSpoken() ?: lastReply ?: AssistantReplies.NOTHING_TO_REPEAT, intent = id)

        IntentId.HELP -> helpPage(heard, screen.resolvedExamples, 0, screen.resolvedTitle)

        IntentId.WHERE -> AssistantReply(heard, AssistantReplies.WHERE.fill(screen.resolvedTitle, whereTail(screen)).trim(), intent = id)

        IntentId.STOP -> AssistantReply(heard, AssistantReplies.STOPPED, listOf(AssistantAction.StopListening), intent = id)

        IntentId.CANCEL -> if (screen.mode == ScreenMode.VALUE_CAPTURE) {
            ask(Pending.LeaveInput)
            AssistantReply(heard, CANCEL_INPUT, buttons = yesNo(), intent = id)
        } else {
            AssistantReply(heard, AssistantReplies.NOTHING_TO_CANCEL, intent = id)
        }

        IntentId.ORIGIN -> if (facts.hasOrigin()) {
            AssistantReply(heard, AssistantReplies.BACK_TO_ORIGIN, listOf(AssistantAction.ToOrigin), intent = id)
        } else {
            AssistantReply(heard, AssistantReplies.NO_ORIGIN, intent = id)
        }

        else -> AssistantReply(heard, null, intent = id)
    }

    private fun whereTail(screen: ScreenVoiceContext): String {
        val summary = screen.resolvedSummary
        val examples = screen.resolvedExamples.take(2).joinToString(", ") { "「$it」" }
        return listOf(summary, if (examples.isEmpty()) "" else "$examples 라고 말할 수 있어요.").filter { it.isNotBlank() }.joinToString(" ")
    }

    /** 도움말(§4.4): 이 화면 예시 3개 + 어디서나 3개 → 「더 들을까요?」 → 3개씩. */
    private fun helpPage(heard: String, examples: List<String>, offset: Int, title: String? = null): AssistantReply {
        val page = examples.drop(offset).take(HELP_PAGE)
        if (page.isEmpty()) return AssistantReply(heard, AssistantReplies.HELP_END, intent = IntentId.HELP)
        val quoted = page.joinToString(", ") { "「$it」" }
        val more = examples.size > offset + HELP_PAGE
        val head = if (offset == 0) AssistantReplies.HELP_HEAD.fill(title ?: "이", quoted) else AssistantReplies.HELP_TAIL.fill(quoted)
        if (more) ask(Pending.HelpMore(examples, offset + HELP_PAGE))
        return AssistantReply(
            heard,
            if (more) "$head ${AssistantReplies.HELP_MORE}" else head,
            buttons = if (more) yesNo() else emptyList(),
            intent = IntentId.HELP
        )
    }

    // ── 4단계: 전역 의도 ──────────────────────────────────────
    private suspend fun intent(heard: String, m: IntentMatch, confident: Boolean): AssistantReply {
        if (m.id.phase >= 2) return AssistantReply(heard, AssistantReplies.DIET_SOON, intent = m.id)
        return when (m.id) {
            IntentId.MEDICINE_SOON -> AssistantReply(heard, AssistantReplies.MEDICINE_SOON, intent = m.id)

            IntentId.WEEKLY_QUESTION -> weekly(heard, m)

            IntentId.GOAL_QUESTION -> goal(heard)

            IntentId.EXERCISE_RESUME -> {
                ask(Pending.Go(AssistantRoutes.EXERCISE_HOME))
                AssistantReply(heard, AssistantReplies.RESUME_NONE, buttons = yesNo(), intent = m.id)
            }

            IntentId.VOICE_SPEED -> speed(heard, m)

            IntentId.AUTO_LISTEN -> autoListen(heard, m)

            IntentId.EXERCISE_BY_NAME -> exercise(heard, m, confident)

            IntentId.HEALTH_VALUE -> healthValue(heard, m)

            IntentId.ACCOUNT_RISK -> AssistantRoutes.of(m)?.let { d ->
                AssistantReply(heard, AssistantReplies.ACCOUNT_RISK.fill(d.label), listOf(AssistantAction.Navigate(d)), intent = m.id)
            } ?: AssistantReply(heard, null, intent = m.id)

            else -> AssistantRoutes.of(m)?.let { d -> if (confident) go(heard, d, m.id) else askGo(heard, d, m.id) }
                ?: AssistantReply(heard, null, intent = m.id)
        }
    }

    private fun go(heard: String, d: Destination, id: IntentId? = null): AssistantReply =
        AssistantReply(heard, AssistantReplies.GOING.fill(Josa.ro(d.label)), listOf(AssistantAction.Navigate(d)), intent = id)

    private fun askGo(heard: String, d: Destination, id: IntentId?): AssistantReply {
        ask(Pending.Go(d))
        return AssistantReply(
            heard,
            AssistantReplies.ASK_GO.fill(Josa.ro(d.label)),
            buttons = listOf(
                StripButton(AssistantReplies.GO, AssistantReplies.YES),
                StripButton(AssistantReplies.CANCEL, AssistantReplies.NO)
            ),
            intent = id
        )
    }

    private fun choose(heard: String, options: List<Destination>): AssistantReply? {
        if (options.size < 2) return options.firstOrNull()?.let { askGo(heard, it, null) }
        val two = options.take(2)
        ask(Pending.Choose(two))
        return AssistantReply(
            heard,
            AssistantReplies.CHOOSE_TWO.fill(two[0].label, two[1].label),
            buttons = two.mapIndexed { i, d -> StripButton(d.label, ORDINAL_WORDS[i]) }
        )
    }

    private fun exercise(heard: String, m: IntentMatch, confident: Boolean): AssistantReply {
        val main = AssistantRoutes.of(m) ?: return AssistantReply(heard, null, intent = m.id)
        val altId = m.slots[Slot.ALT_ID] ?: return if (confident) go(heard, main, m.id) else askGo(heard, main, m.id)
        val alt = AssistantRoutes.of(
            m.copy(slots = mapOf(Slot.EXERCISE_ID to altId, Slot.EXERCISE_NAME to m.slots[Slot.ALT_NAME].orEmpty()))
        )
        return choose(heard, listOfNotNull(main, alt)) ?: go(heard, main, m.id)
    }

    private suspend fun weekly(heard: String, m: IntentMatch): AssistantReply {
        val last = m.slots[Slot.PERIOD] == "LAST"
        val fact = facts.weekly(last)
        val say = when {
            fact == null -> AssistantReplies.WEEKLY_FAILED
            fact.sessions == 0 -> if (last) AssistantReplies.WEEKLY_NONE_LAST else AssistantReplies.WEEKLY_NONE_THIS
            last -> AssistantReplies.WEEKLY_LAST.fill(fact.sessions, fact.minutes)
            else -> AssistantReplies.WEEKLY_THIS.fill(fact.sessions, fact.minutes)
        }
        ask(Pending.Go(AssistantRoutes.WEEKLY))
        return AssistantReply(heard, say, buttons = yesNo(), intent = m.id)
    }

    private suspend fun goal(heard: String): AssistantReply {
        val fact = facts.goal()
        val goalName = fact?.goal
        if (fact == null || goalName == null) {
            ask(Pending.Go(Destination.MyPage))
            return AssistantReply(heard, AssistantReplies.GOAL_UNKNOWN, buttons = yesNo(), intent = IntentId.GOAL_QUESTION)
        }
        val say = fact.targetKcal?.let { AssistantReplies.GOAL.fill(goalName, String.format(Locale.ROOT, "%,d", it)) }
            ?: AssistantReplies.GOAL_NO_KCAL.fill(goalName)
        return AssistantReply(heard, say, intent = IntentId.GOAL_QUESTION)
    }

    private fun speed(heard: String, m: IntentMatch): AssistantReply {
        val target = m.slots[Slot.SPEED] ?: return AssistantReply(heard, null, intent = m.id)
        val now = facts.speed()
        if (target == now) return AssistantReply(heard, AssistantReplies.SPEED_SAME, intent = m.id)
        ask(Pending.Undo(now))
        return AssistantReply(heard, AssistantReplies.SPEED_CHANGED, listOf(AssistantAction.SetSpeed(target)), intent = m.id)
    }

    private fun autoListen(heard: String, m: IntentMatch): AssistantReply {
        val on = m.slots[Slot.MODE] == "ON"
        if (on == facts.autoListenOn()) return AssistantReply(heard, AssistantReplies.AUTO_SAME, intent = m.id)
        ask(Pending.AutoListen(on))
        return AssistantReply(
            heard,
            if (on) AssistantReplies.AUTO_ON_ASK else AssistantReplies.AUTO_OFF_ASK,
            buttons = yesNo(),
            intent = m.id
        )
    }

    private fun autoListenAnswer(heard: String, on: Boolean, yes: Boolean): AssistantReply = if (yes) {
        AssistantReply(
            heard,
            if (on) AssistantReplies.AUTO_ON_DONE else AssistantReplies.AUTO_OFF_DONE,
            listOf(AssistantAction.SetAutoListen(on)),
            intent = IntentId.AUTO_LISTEN
        )
    } else {
        AssistantReply(heard, AssistantReplies.KEEP, intent = IntentId.AUTO_LISTEN)
    }

    /** A-01: 저장하지 않는다. 값이 채워진 되읽기 확인 화면을 연다(§3.1 원칙·검수 ③). */
    private fun healthValue(heard: String, m: IntentMatch): AssistantReply {
        val d = AssistantRoutes.of(m) ?: return AssistantReply(heard, null, intent = m.id)
        val (label, unit) = if (m.slots[Slot.FIELD] == "WEIGHT") "몸무게" to "킬로그램" else "키" to "센티미터"
        val value = "${m.slots[Slot.VALUE]}$unit"
        return AssistantReply(
            heard,
            AssistantReplies.HEALTH_VALUE_OPEN.fill(label, Josa.ro(value)),
            listOf(AssistantAction.Navigate(d)),
            intent = m.id
        )
    }

    private fun yesNo() =
        listOf(StripButton(AssistantReplies.YES, AssistantReplies.YES), StripButton(AssistantReplies.NO, AssistantReplies.NO))

    companion object {
        /** 묻고 나서 이 시간이 지나면 묻던 것을 거둔다. */
        const val PENDING_MS = 60_000L

        /** 「되돌려」를 받는 시간(§4.3 설정 바꿈 10초). */
        const val UNDO_MS = 10_000L
        private const val HELP_PAGE = 3
        private const val CANCEL_INPUT = "입력을 그만두고 이전 화면으로 갈까요?"
        private val ORDINAL_WORDS = listOf("첫 번째", "두 번째")
        private val UNDO_WORDS = listOf("되돌려", "되돌리", "원래대로", "돌려놔", "돌려줘", "취소", "아까대로", "전으로")
    }
}
