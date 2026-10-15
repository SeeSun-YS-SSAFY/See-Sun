package kr.seesun.core.ui

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource

class HealthVoiceParserTest {

    private val names = mapOf(
        "EGG" to "알류", "MILK" to "우유", "BUCKWHEAT" to "메밀", "PEANUT" to "땅콩", "SOYBEAN" to "대두",
        "WHEAT" to "밀", "PINE_NUT" to "잣", "WALNUT" to "호두", "CRAB" to "게", "SHRIMP" to "새우",
        "SQUID" to "오징어", "MACKEREL" to "고등어", "SHELLFISH" to "조개류", "PEACH" to "복숭아", "TOMATO" to "토마토",
        "CHICKEN" to "닭고기", "PORK" to "돼지고기", "BEEF" to "쇠고기", "SULFITE" to "아황산류"
    )

    @ParameterizedTest
    @CsvSource(
        "당뇨랑 고혈압 있어요, DIABETES|HYPERTENSION",
        "당뇨요, DIABETES",
        "혈압이 높아요, HYPERTENSION",
        "당뇨는 없고 고혈압이 있어요, HYPERTENSION",
        "없어요, ''",
        "해당 없음, ''",
        "당뇨 아니에요, ''"
    )
    fun `질환 음성 해석`(text: String, expected: String) {
        assertEquals(expected.split('|').filter { it.isNotEmpty() }.toSet(), HealthVoiceParser.diseases(text))
    }

    @Test
    fun `질환을 알아들을 수 없으면 null`() {
        assertNull(HealthVoiceParser.diseases("음 잘 모르겠는데"))
    }

    @ParameterizedTest
    @CsvSource(
        "땅콩이랑 우유, PEANUT|MILK",
        "땅콩, PEANUT",
        "계란하고 새우요, EGG|SHRIMP",
        "소고기 돼지고기, BEEF|PORK",
        "게, CRAB",
        "밀가루, WHEAT",
        "조개, SHELLFISH",
        "알레르기 없어요, ''"
    )
    fun `알레르기 음성 해석 - 다른 이름과 조사를 알아듣고 땅콩의 콩은 대두로 오인하지 않는다`(text: String, expected: String) {
        assertEquals(expected.split('|').filter { it.isNotEmpty() }.toSet(), HealthVoiceParser.allergens(text, names))
    }

    @Test
    fun `한 글자 이름은 낱말 일부로는 인정하지 않는다`() {
        assertNull(HealthVoiceParser.allergens("주게요", names))
    }

    @ParameterizedTest
    @CsvSource(
        "살 빼고 싶어요, LOSE_WEIGHT",
        "체중 감량, LOSE_WEIGHT",
        "근육 키우기, GAIN_MUSCLE",
        "건강 유지요, MAINTAIN"
    )
    fun `목표 음성 해석`(text: String, expected: String) {
        assertEquals(expected, HealthVoiceParser.goal(text))
    }
}
