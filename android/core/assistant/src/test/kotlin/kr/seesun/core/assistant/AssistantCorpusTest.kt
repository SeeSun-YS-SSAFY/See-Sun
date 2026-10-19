package kr.seesun.core.assistant

import java.io.File
import java.util.Locale
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * 말뭉치 시험(설계 §8.2 받아들임 기준). src/test/resources/corpus의 말을 모두 돌려 지표를 찍고
 * build/reports/assistant/에 남긴다.
 * - assistant_corpus.tsv(기준 말뭉치): 목록 안 의도 1순위 정확도 ≥ 92%(의도마다 10개 이상), 범위 밖을 이동으로 오해 ≤ 3%(100개 이상),
 *   안전 재현율 100%(50개 이상), 건강 기록이 확인 화면 없이 저장 = 0, 확인 없이 전화 = 0. 값 입력 중·화면 명령 충돌(각 50개 이상)도 92%.
 * - assistant_holdout.tsv(보류 말뭉치): 규칙을 이 말에 맞춰 고치지 않고 일반화를 잰다. 안전 재현율 100%와 확인 없는 저장·전화 0만 지킨다.
 */
class AssistantCorpusTest {
    private data class Row(val bucket: String, val expected: String, val screen: String, val text: String)

    private data class Metrics(
        val report: String,
        val counts: Map<String, Int>,
        val perIntent: Map<String, Int>,
        val pct: Map<String, Double>,
        val oosMisroute: Double,
        val safetyTotal: Int,
        val safetyHit: Int,
        val unconfirmedSaves: Int,
        val unconfirmedCalls: Int
    )

    private val engine = AssistantFixtures.engine()

    private fun load(name: String): List<Row> {
        val stream = requireNotNull(javaClass.classLoader?.getResourceAsStream("corpus/$name")) { "말뭉치 없음: $name" }
        return stream.reader(Charsets.UTF_8).readLines()
            .filter { it.isNotBlank() && !it.startsWith("#") }
            .map { line -> line.split("\t").let { Row(it[0], it[1], it[2], it[3]) } }
    }

    /** 판정을 기대 열과 같은 글자로. 화면 명령은 같은 일을 하는 의도가 있으면 그 의도로 본다. */
    private fun label(d: Decision, screen: ScreenVoiceContext): String = when (d) {
        is Decision.Safety -> "SAFETY:${d.kind}"
        is Decision.Local -> screen.local[d.index].same?.code ?: "LOCAL:${d.index}"
        is Decision.Command -> d.id.code
        is Decision.Intent -> d.match.id.code
        is Decision.Ambiguous -> d.options.first().id.code
        Decision.OutOfScope -> "OOS"
        Decision.None -> "NONE"
    }

    /** 확인 없이 다른 화면으로 데려간 것(범위 밖 오해 판정). 묻고 가는 것(「○○로 갈까요?」)은 따로 센다. */
    private fun navigatesDirectly(d: Decision): Boolean = when (d) {
        is Decision.Local -> true
        is Decision.Command -> d.id in setOf(IntentId.BACK, IntentId.HOME, IntentId.ORIGIN)
        is Decision.Intent -> d.confident && AssistantRoutes.of(d.match) != null
        else -> false
    }

    private fun asks(d: Decision): Boolean =
        (d is Decision.Intent && !d.confident && AssistantRoutes.of(d.match) != null) || d is Decision.Ambiguous

    private suspend fun evaluate(name: String): Metrics {
        val rows = load(name)
        val misses = mutableListOf<String>()
        val byBucket = rows.groupBy { it.bucket }
        val score = mutableMapOf<String, Pair<Int, Int>>()
        var oosDirect = 0
        var oosAsk = 0
        var safetyHit = 0
        var safetyKindHit = 0
        rows.forEach { r ->
            val screen = AssistantFixtures.screen(r.screen)
            val d = engine.decide(listOf(r.text), screen)
            val got = label(d, screen)
            val ok = if (r.bucket == "OOS") !navigatesDirectly(d) else got == r.expected
            if (r.bucket == "OOS") {
                if (navigatesDirectly(d)) oosDirect++
                if (asks(d)) oosAsk++
            }
            if (r.bucket == "SAFETY") {
                if (d is Decision.Safety) safetyHit++
                if (got == r.expected) safetyKindHit++
            }
            val (n, k) = score[r.bucket] ?: (0 to 0)
            score[r.bucket] = (n + 1) to (k + if (ok) 1 else 0)
            if (!ok) misses += "${r.bucket}\t기대=${r.expected}\t판정=$got${if (asks(d)) "(묻기)" else ""}\t화면=${r.screen}\t${r.text}"
        }

        // 건강 기록·전화: 모든 말을 새 대화에서 한 번씩 들려 앱에 시키는 일을 본다.
        var unconfirmedSaves = 0
        var unconfirmedCalls = 0
        var healthValueReplies = 0
        rows.forEach { r ->
            val session = AssistantSession(engine, AssistantFixtures.FakeFacts())
            val reply = session.hear(listOf(r.text), AssistantFixtures.screen(r.screen)) ?: return@forEach
            reply.actions.forEach { a ->
                if (a is AssistantAction.Dial) unconfirmedCalls++
                val dest = (a as? AssistantAction.Navigate)?.destination as? Destination.ProfileEdit
                if (dest?.value != null) {
                    healthValueReplies++
                    if (!dest.confirmScreen) unconfirmedSaves++
                }
            }
            if (reply.intent == IntentId.HEALTH_VALUE && reply.actions.none { it is AssistantAction.Navigate }) unconfirmedSaves++
        }

        val pct = score.mapValues { (_, v) -> if (v.first == 0) 0.0 else v.second * 100.0 / v.first }
        val oosTotal = byBucket["OOS"].orEmpty().size
        val oosMisroute = if (oosTotal == 0) 0.0 else oosDirect * 100.0 / oosTotal
        val safetyTotal = byBucket["SAFETY"].orEmpty().size
        val safetyRecall = if (safetyTotal == 0) 0.0 else safetyHit * 100.0 / safetyTotal
        val perIntent = byBucket["IN"].orEmpty().groupBy { it.expected }.mapValues { it.value.size }
        val report = buildString {
            appendLine("시선 비서 말뭉치 지표 — $name (core:assistant AssistantCorpusTest)")
            appendLine("rows=${rows.size} " + byBucket.entries.joinToString(" ") { "${it.key}=${it.value.size}" })
            appendLine("in_scope_intents=${perIntent.size} min_per_intent=${perIntent.values.minOrNull()}")
            appendLine(fmt("in_scope_top1_accuracy", pct["IN"], "(기준 >= 92%)"))
            appendLine(fmt("oos_misrouted_as_navigation", oosMisroute, "(기준 <= 3%) direct=$oosDirect asked=$oosAsk of $oosTotal"))
            appendLine(fmt("safety_recall", safetyRecall, "(기준 100%) kind_match=$safetyKindHit/$safetyTotal"))
            appendLine("health_saves_without_confirm=$unconfirmedSaves (기준 0) health_value_replies=$healthValueReplies")
            appendLine("calls_without_confirm=$unconfirmedCalls (기준 0)")
            appendLine(fmt("value_capture_accuracy", pct["VALUE"], "(n=${byBucket["VALUE"].orEmpty().size})"))
            appendLine(fmt("screen_conflict_accuracy", pct["CONFLICT"], "(n=${byBucket["CONFLICT"].orEmpty().size})"))
            if (byBucket.containsKey("PHASE2")) appendLine(fmt("phase2_diet_intent_accuracy", pct["PHASE2"], "(참고, 답은 「준비 중」)"))
            appendLine("misses=${misses.size}")
            misses.forEach { appendLine("  $it") }
        }
        println(report)
        File("build/reports/assistant").apply { mkdirs() }.resolve(name.replace(".tsv", "_metrics.txt")).writeText(report)
        return Metrics(
            report,
            byBucket.mapValues { it.value.size },
            perIntent,
            pct,
            oosMisroute,
            safetyTotal,
            safetyHit,
            unconfirmedSaves,
            unconfirmedCalls
        )
    }

    @Test
    fun 기준_말뭉치_받아들임_기준() = runTest {
        val m = evaluate("assistant_corpus.tsv")
        assertTrue(m.perIntent.values.all { it >= MIN_PER_INTENT }, "의도마다 말 $MIN_PER_INTENT 개 이상")
        assertTrue((m.counts["OOS"] ?: 0) >= MIN_OOS && m.safetyTotal >= MIN_SAFETY, "범위 밖 $MIN_OOS·안전 $MIN_SAFETY 개 이상")
        assertTrue((m.counts["VALUE"] ?: 0) >= MIN_OTHER && (m.counts["CONFLICT"] ?: 0) >= MIN_OTHER, "값 입력·충돌 $MIN_OTHER 개 이상")
        assertTrue((m.pct["IN"] ?: 0.0) >= PASS_PCT, "목록 안 정확도 ${m.pct["IN"]}%")
        assertTrue(m.oosMisroute <= OOS_MAX_PCT, "범위 밖 오해 ${m.oosMisroute}%")
        assertEquals(m.safetyTotal, m.safetyHit, "안전 재현율 100%")
        assertEquals(0, m.unconfirmedSaves, "확인 화면 없이 건강 기록 저장")
        assertEquals(0, m.unconfirmedCalls, "확인 없이 전화")
        assertTrue((m.pct["VALUE"] ?: 0.0) >= PASS_PCT, "값 입력 중 정확도 ${m.pct["VALUE"]}%")
        assertTrue((m.pct["CONFLICT"] ?: 0.0) >= PASS_PCT, "화면 명령 충돌 정확도 ${m.pct["CONFLICT"]}%")
    }

    @Test
    fun 보류_말뭉치_안전과_확인은_지킨다() = runTest {
        val m = evaluate("assistant_holdout.tsv")
        assertEquals(m.safetyTotal, m.safetyHit, "보류 말뭉치 안전 재현율 100%")
        assertEquals(0, m.unconfirmedSaves, "확인 화면 없이 건강 기록 저장")
        assertEquals(0, m.unconfirmedCalls, "확인 없이 전화")
    }

    private fun fmt(name: String, value: Double?, note: String) = "$name=${String.format(Locale.ROOT, "%.1f", value ?: 0.0)}% $note"

    private companion object {
        const val MIN_PER_INTENT = 10
        const val MIN_OOS = 100
        const val MIN_SAFETY = 50
        const val MIN_OTHER = 50
        const val PASS_PCT = 92.0
        const val OOS_MAX_PCT = 3.0
    }
}
