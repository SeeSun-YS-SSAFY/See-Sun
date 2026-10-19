package kr.seesun.feature.diet.record

import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kr.seesun.core.assistant.AssistantText
import kr.seesun.core.data.model.MealType
import kr.seesun.core.designsystem.component.WarningKind
import kr.seesun.core.network.model.DietWarningDto
import kr.seesun.core.network.model.RecommendationResponseDto
import kr.seesun.core.voice.parse.Answer
import kr.seesun.core.voice.parse.AnswerParser
import kr.seesun.core.voice.parse.MealTypeParser
import kr.seesun.core.voice.parse.SaveAnswerParser

/**
 * 서버 경고 → 팝업 순서(DREC-08: **알레르기가 늘 먼저**, 그다음 당류·탄수화물 지표). 서버가 다른 순서로 보내도 앱이 다시 세운다.
 * 알레르기 「확인 불가」(level INFO)는 진동 없는 안내라 팝업으로 띄우지 않고 확인 화면 메모로 읽는다(API §6.2).
 */
object DietWarnings {
    const val ALLERGY = "ALLERGY"
    const val SUGAR_INDEX = "SUGAR_INDEX"
    const val INFO = "INFO"
    const val ALTERNATIVE_DIET = "ALTERNATIVE_DIET"
    const val TYPE_ALLERGY = "allergy"
    const val TYPE_DISEASE = "disease"
    private const val DETAIL_ITEMS = 3

    fun order(warnings: List<DietWarningDto>): List<WarningUi> {
        val allergy = warnings.filter { it.type == ALLERGY && it.level != INFO }.map {
            WarningUi(WarningKind.ALLERGY, it.ttsMessage.orEmpty(), it.actions, it.allergenCodes)
        }
        val sugar = warnings.filter { it.type == SUGAR_INDEX }.map {
            val kind = if (it.vibration == WarningKind.SUGAR_VERY_HIGH.vibrationCode || it.grade == "VERY_HIGH") {
                WarningKind.SUGAR_VERY_HIGH
            } else {
                WarningKind.SUGAR_HIGH
            }
            WarningUi(kind, it.ttsMessage.orEmpty(), it.actions)
        }
        return allergy + sugar
    }

    /** [대체 식단 보기]·[추천 음식 보기]에서 읽을 한 줄(서버 문구 그대로, 없으면 음식 이름 3개). */
    fun detailOf(kind: WarningKind, r: RecommendationResponseDto): String {
        if (kind == WarningKind.ALLERGY) {
            (r.alternativeDiet?.get("tts_message") as? JsonPrimitive)?.contentOrNull?.let { return it }
        }
        val names = r.items.take(DETAIL_ITEMS).map { (it.food.displayName ?: it.food.name).replace('_', ' ') }
        return if (names.isEmpty()) DietRecordText.ALTERNATIVE_FAILED else DietRecordText.RECOMMENDED_LINE.format(names.joinToString(", "))
    }
}

/** 단계별 말 해석(값 입력 칸 — 말 전체가 값). 처리했으면 true. */
internal object DietSpeech {
    private val DONE = listOf("없어", "없어요", "없습니다", "다됐", "다먹", "그만", "끝", "됐어", "이게다", "그게다")
    private val AGAIN = listOf("다시", "다른거", "틀렸", "아니야")
    private val NAME_ONLY = listOf("없어", "목록에없", "이름만", "이름으로", "그대로")
    private val ALT = listOf("대체", "다른음식", "대신")
    private val RECOMMEND = listOf("추천")
    private val RECORD_ANYWAY = listOf("이대로기록", "이대로저장", "그냥기록", "그냥저장", "기록해", "저장해")
    private val MORE_YES = listOf("있어", "있어요", "더있어", "더있어요", "있습니다", "더먹었어", "더있지")
    private val OK = listOf("알겠", "확인", "닫기", "닫아", "네", "응", "예", "오케이", "넘어가")

    fun meal(vm: DietRecordViewModel, candidates: List<String>, default: MealType): Boolean {
        val found = candidates.firstNotNullOfOrNull { MealTypeParser.parse(it) }?.let { MealType.valueOf(it.name) }
        val meal = found ?: default.takeIf { AnswerParser.parse(candidates.first()) == Answer.YES } ?: return false
        vm.chooseMeal(meal)
        return true
    }

    fun food(vm: DietRecordViewModel, text: String, s: RecordUi): Boolean {
        val c = AssistantText.compact(text)
        if (s.entries.isNotEmpty() && DONE.any { c.startsWith(it) }) {
            vm.more(false)
            return true
        }
        return vm.submitFood(text)
    }

    fun pick(vm: DietRecordViewModel, candidates: List<String>, s: RecordUi): Boolean {
        val c = AssistantText.compact(candidates.first())
        if (s.candidates.isNotEmpty()) {
            candidates.firstNotNullOfOrNull { AssistantText.ordinal(it) }?.takeIf { it < s.candidates.size }?.let {
                vm.pick(it)
                return true
            }
            val byName = s.candidates.indexOfFirst { ch ->
                val n = DietRecordViewModel.normalize(ch.name)
                candidates.any { DietRecordViewModel.normalize(it) == n }
            }.takeIf { it >= 0 }
                ?: s.candidates.indexOfFirst { ch -> c.contains(DietRecordViewModel.normalize(ch.name)) }.takeIf { it >= 0 }
            if (byName != null) {
                vm.pick(byName)
                return true
            }
        }
        return when {
            NAME_ONLY.any { c.contains(it) } || (s.candidates.isEmpty() && AnswerParser.parse(candidates.first()) == Answer.YES) -> {
                vm.pickNameOnly()
                true
            }

            AGAIN.any { c.contains(it) } -> {
                vm.retryFood()
                true
            }

            else -> false
        }
    }

    fun amount(vm: DietRecordViewModel, candidates: List<String>): Boolean {
        val q = candidates.firstNotNullOfOrNull { DietRecordViewModel.quantityOf(it) } ?: return false
        vm.chooseAmount(q)
        return true
    }

    fun more(vm: DietRecordViewModel, text: String): Boolean {
        val c = AssistantText.compact(text)
        if (DONE.any { c.startsWith(it) }) {
            vm.more(false)
            return true
        }
        if (MORE_YES.any { c == it }) {
            vm.more(true)
            return true
        }
        return when (AnswerParser.parse(text)) {
            Answer.NO -> {
                vm.more(false)
                true
            }

            Answer.YES -> {
                // 「응, 사과 하나」처럼 음식까지 말했으면 바로 그 음식으로.
                if (!vm.submitFood(text.replaceFirst(Regex("""^\s*(네|응|예|어|있어|있어요)[\s,.]*"""), ""))) vm.more(true)
                true
            }

            else -> vm.submitFood(text)
        }
    }

    fun confirm(vm: DietRecordViewModel, text: String, s: RecordUi): Boolean {
        if (s.evaluating) return true
        return when (SaveAnswerParser.parse(text)) {
            Answer.YES -> {
                vm.confirmSave()
                true
            }

            Answer.NO, Answer.EDIT -> {
                vm.edit()
                true
            }

            Answer.REPEAT -> {
                vm.repeat()
                true
            }

            null -> false
        }
    }

    fun edit(vm: DietRecordViewModel, text: String): Boolean {
        val c = AssistantText.compact(text)
        val target = when {
            c.contains("날짜") || c.contains("언제") -> EditTarget.DATE
            c.contains("끼니") || MealTypeParser.parse(text) != null -> EditTarget.MEAL
            c.contains("더하") || c.contains("추가") || c.contains("더있") || c.contains("빠졌") -> EditTarget.ADD
            c.contains("음식") || c.contains("다시") || c.contains("메뉴") -> EditTarget.FOODS
            else -> return false
        }
        vm.editTarget(target)
        return true
    }

    /** 경고 팝업 화면 명령(설계 §3.4 L-01): 「대체 식단 보여 줘」「추천 음식 보여 줘」「확인」「이대로 기록해」. */
    fun warning(vm: DietRecordViewModel, text: String): Boolean {
        val c = AssistantText.compact(text)
        when {
            RECORD_ANYWAY.any { c.contains(it) } -> vm.acknowledgeAll()
            ALT.any { c.contains(it) } || RECOMMEND.any { c.contains(it) } -> vm.showWarningDetail()
            OK.any { c.startsWith(it) } -> vm.acknowledgeWarning()
            else -> return false
        }
        return true
    }
}
