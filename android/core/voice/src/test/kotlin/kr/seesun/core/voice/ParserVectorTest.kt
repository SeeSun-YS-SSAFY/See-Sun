package kr.seesun.core.voice

import java.time.LocalDate
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kr.seesun.core.voice.parse.AnswerParser
import kr.seesun.core.voice.parse.DateParser
import kr.seesun.core.voice.parse.FoodMatcher
import kr.seesun.core.voice.parse.MealTypeParser
import kr.seesun.core.voice.parse.MealUtteranceParser
import kr.seesun.core.voice.parse.PhoneNumberParser
import kr.seesun.core.voice.parse.ProfileParsers
import kr.seesun.core.voice.parse.Quantity
import kr.seesun.core.voice.parse.QuantityParser
import kr.seesun.core.voice.parse.Token
import kr.seesun.core.voice.parse.Tokenizer
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestFactory

/**
 * 규칙 파서 테스트 벡터(src/test/resources/vectors 폴더의 JSON) — T3 3-4 완료 기준: **100% 통과**.
 * 벡터 파일은 앱·서버가 함께 쓰는 명세 성격이므로, 파서를 바꿀 때는 벡터를 먼저 고친다.
 */
class ParserVectorTest {

    private val json = Json { ignoreUnknownKeys = true }
    private val matcher = FoodMatcher()
    private val mealParser = MealUtteranceParser(matcher)

    private fun load(name: String): JsonObject {
        val stream = requireNotNull(javaClass.classLoader?.getResourceAsStream("vectors/$name")) { "벡터 파일 없음: $name" }
        return json.parseToJsonElement(stream.reader(Charsets.UTF_8).readText()).jsonObject
    }

    private fun today(obj: JsonObject): LocalDate = LocalDate.parse(obj["today"]?.jsonPrimitive?.content ?: "2026-10-12")

    /** 한 파일의 모든 사례를 실행해 (입력, 기대, 실제) 불일치 목록을 돌려준다. */
    private fun run(file: String, eval: (JsonObject, LocalDate) -> Pair<String, String>): List<String> {
        val root = load(file)
        val today = today(root)
        return root.getValue("cases").jsonArray.mapNotNull { case ->
            val obj = case.jsonObject
            val (expected, actual) = eval(obj, today)
            if (expected == actual) null else "[$file] \"${obj.str("input")}\" 기대=$expected 실제=$actual"
        }
    }

    private fun JsonObject.str(key: String): String = this[key]?.jsonPrimitive?.content.orEmpty()

    private fun JsonElement?.numText(): String = when {
        this == null || this is JsonNull -> "null"
        else -> jsonPrimitive.doubleOrNull?.toString() ?: jsonPrimitive.content
    }

    private fun Double?.numText(): String = this?.toString() ?: "null"

    private fun JsonElement?.text(): String = if (this == null || this is JsonNull) "null" else jsonPrimitive.contentOrNull ?: "null"

    private fun quantityText(q: Quantity): String = "ratio=${q.ratio} grams=${q.grams} confirm=${q.needsConfirmation}"

    private fun expectedQuantityText(e: JsonObject): String =
        "ratio=${e["ratio"]?.jsonPrimitive?.doubleOrNull} grams=${e["grams"]?.jsonPrimitive?.doubleOrNull} " +
            "confirm=${e["confirm"]?.jsonPrimitive?.booleanOrNull ?: false}"

    private val suites: Map<String, (JsonObject, LocalDate) -> Pair<String, String>> = linkedMapOf(
        "numbers.json" to { c, _ ->
            val nums = Tokenizer.tokenize(c.str("input"))
            val actual = (nums.singleOrNull() as? Token.Num)?.takeIf { it.unit == null }?.value
            c["expected"].numText() to actual.numText()
        },
        "phones.json" to { c, _ -> c["expected"].text() to (PhoneNumberParser.parse(c.str("input")) ?: "null") },
        "dates.json" to { c, today -> c["expected"].text() to (DateParser.parse(c.str("input"), today)?.toString() ?: "null") },
        "meal_types.json" to { c, _ -> c["expected"].text() to (MealTypeParser.parse(c.str("input"))?.name ?: "null") },
        "quantities.json" to { c, _ ->
            expectedQuantityText(c.getValue("expected").jsonObject) to quantityText(QuantityParser.parse(c.str("input")))
        },
        "profile.json" to { c, _ ->
            val input = c.str("input")
            when (c.str("field")) {
                "height" -> c["expected"].numText() to ProfileParsers.heightCm(input).numText()
                "weight" -> c["expected"].numText() to ProfileParsers.weightKg(input).numText()
                "gender" -> c["expected"].text() to (ProfileParsers.gender(input)?.name ?: "null")
                else -> c["expected"].text() to (ProfileParsers.name(input) ?: "null")
            }
        },
        "answers.json" to { c, _ -> c["expected"].text() to (AnswerParser.parse(c.str("input"))?.name ?: "null") },
        "foods.json" to { c, _ -> c["expected"].text() to (matcher.best(c.str("input"))?.name ?: "null") },
        "meals.json" to { c, today ->
            val e = c.getValue("expected").jsonObject
            val r = mealParser.parse(c.str("input"), today)
            val expectedFoods = (e["foods"] as JsonArray).joinToString("; ") { f ->
                val o = f.jsonObject
                "${o.str("name")}(${o["matched"]?.jsonPrimitive?.booleanOrNull ?: true}) ${expectedQuantityText(o)}"
            }
            val actualFoods = r.foods.joinToString("; ") { "${it.name}(${it.matched}) ${quantityText(it.quantity)}" }
            "${e["date"].text()} ${e["meal_type"].text()} [$expectedFoods]" to
                "${r.date ?: "null"} ${r.mealType?.name ?: "null"} [$actualFoods]"
        }
    )

    @TestFactory
    fun 벡터_파일별_결과(): List<DynamicTest> = suites.map { (file, eval) ->
        DynamicTest.dynamicTest(file) {
            val failures = run(file, eval)
            assertTrue(failures.isEmpty(), failures.joinToString("\n"))
        }
    }

    @Test
    fun 전체_벡터_통과율은_100퍼센트다() {
        var total = 0
        val failures = mutableListOf<String>()
        suites.forEach { (file, eval) ->
            total += load(file).getValue("cases").jsonArray.size
            failures += run(file, eval)
        }
        val passed = total - failures.size
        println("파서 벡터: $passed / $total 통과")
        assertTrue(total >= 150, "벡터가 너무 적음: $total")
        assertEquals(total, passed, failures.joinToString("\n"))
    }
}
