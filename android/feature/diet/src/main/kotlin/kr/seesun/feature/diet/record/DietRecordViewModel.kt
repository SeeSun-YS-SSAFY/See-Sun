package kr.seesun.feature.diet.record

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.Clock
import java.time.LocalDate
import java.time.LocalTime
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kr.seesun.core.assistant.AssistantText
import kr.seesun.core.assistant.Josa
import kr.seesun.core.data.model.InputMethod
import kr.seesun.core.data.model.MealDraft
import kr.seesun.core.data.model.MealItem
import kr.seesun.core.data.model.MealType
import kr.seesun.core.data.model.Nutrition
import kr.seesun.core.data.repository.FoodRepository
import kr.seesun.core.data.repository.HealthRepository
import kr.seesun.core.data.repository.MealRepository
import kr.seesun.core.designsystem.component.WarningKind
import kr.seesun.core.feedback.HapticPattern
import kr.seesun.core.feedback.Haptics
import kr.seesun.core.feedback.WarningSound
import kr.seesun.core.feedback.WarningSounds
import kr.seesun.core.network.ApiFailure
import kr.seesun.core.network.ApiResult
import kr.seesun.core.network.model.DietEvaluateResponse
import kr.seesun.core.network.model.Diseases
import kr.seesun.core.network.model.FoodSummaryDto
import kr.seesun.core.voice.parse.Answer
import kr.seesun.core.voice.parse.AnswerParser
import kr.seesun.core.voice.parse.DateParser
import kr.seesun.core.voice.parse.FoodMention
import kr.seesun.core.voice.parse.MealTypeParser
import kr.seesun.core.voice.parse.MealUtteranceParser
import kr.seesun.core.voice.parse.Quantity
import kr.seesun.core.voice.parse.QuantityParser
import kr.seesun.feature.diet.DietFoodCache
import kr.seesun.feature.diet.DietHealthSnapshot
import kr.seesun.feature.diet.DietHealthStore

/**
 * 음성 식사 기록(v2.0, DREC-01~04·08·09·11, COM-15). 화면과 무관한 상태 기계 — JVM 시험(`DietRecordViewModelTest`)으로 지킨다.
 *
 * - 단계: 날짜 → 끼니 → 음식(후보 최대 3개 낭독) → 양 → 「더 있나요」 → 확인. 비서가 한 문장(A-02)을 넘기면 채운 값으로 시작해 빈 단계만 묻는다.
 * - 확인 화면에 들어서면 `diet/evaluate`로 영양값·경고를 받는다. **알레르기 경고를 늘 먼저**, 그다음 당류·탄수화물 지표 경고(DREC-08·09).
 *   경고마다 진동·소리가 다르다(알레르기 = 짧게 3회 + 높은 두 음, 당류 = 길게 1·2회 + 낮은 한 음).
 * - 끊겼으면 기기 안 알레르기 키워드 대조만 하고(N-05), 당류 지표는 지금 알 수 없다고 말한다.
 * - **저장은 확인 화면에서만**: 경고를 모두 닫고 되읽기를 보인 뒤 [이대로 등록 완료]·「네」를 받아야 기기에 저장(오프라인 큐)한다. 두 번 눌러도 한 번만.
 */
@HiltViewModel
@Suppress("TooManyFunctions", "LongParameterList")
class DietRecordViewModel @Inject constructor(
    private val foods: FoodRepository,
    private val meals: MealRepository,
    private val health: HealthRepository,
    private val healthStore: DietHealthStore,
    private val foodCache: DietFoodCache,
    private val haptics: Haptics,
    private val sounds: WarningSounds,
    private val clock: Clock
) : ViewModel() {
    private val parser = MealUtteranceParser()
    private val _ui = MutableStateFlow(RecordUi())
    val ui: StateFlow<RecordUi> = _ui.asStateFlow()

    private var started = false
    private var queue: List<FoodMention> = emptyList()
    private var askMore = true
    private var editing = false
    private val history = ArrayDeque<RecordStep>()
    private var snapshot: DietHealthSnapshot = DietHealthSnapshot()

    private fun today(): LocalDate = LocalDate.now(clock)

    /** 화면에 처음 들어올 때 한 번. [prefill]은 비서가 받은 한 문장(A-02). */
    fun start(prefill: String?) {
        if (started) return
        started = true
        snapshot = healthStore.load() ?: DietHealthSnapshot()
        viewModelScope.launch { refreshHealth() }
        val defaultMeal = MealType.valueOf(MealTypeParser.defaultFor(LocalTime.now(clock)).name)
        _ui.update { it.copy(defaultMeal = defaultMeal) }
        val parsed = prefill?.takeIf { it.isNotBlank() }?.let { parser.parse(it, today()) }
        if (parsed != null) {
            val filled = parsed.foods.isNotEmpty() || parsed.mealType != null
            val date = parsed.date ?: today().takeIf { filled }
            val future = date != null && date.isAfter(today())
            _ui.update {
                it.copy(
                    date = date.takeUnless { future },
                    meal = parsed.mealType?.let { m -> MealType.valueOf(m.name) },
                    status = if (future) DietRecordText.DATE_FUTURE else ""
                )
            }
            queue = parsed.foods
            askMore = parsed.foods.isEmpty()
        }
        next()
    }

    /** 민감정보 동의가 있으면 알레르기·질환을 받아 둔다(끊겼을 때 대조용). 동의가 없으면 비운다. */
    private suspend fun refreshHealth() {
        when (val r = health.get()) {
            is ApiResult.Success -> {
                snapshot = snapshot.copy(allergens = r.data.allergens, diabetes = Diseases.DIABETES in r.data.diseases)
                healthStore.save(snapshot)
            }

            is ApiResult.Failure -> if (!r.failure.isNetwork && r.failure.httpStatus == FORBIDDEN) {
                snapshot = DietHealthSnapshot()
                healthStore.clear()
            }
        }
    }

    // ── 단계 이동 ─────────────────────────────────────────────

    private fun next() {
        val s = _ui.value
        when {
            s.date == null -> go(RecordStep.DATE)
            s.meal == null -> go(RecordStep.MEAL)
            queue.isNotEmpty() -> resolveNext()
            editing || (s.entries.isNotEmpty() && !askMore) -> openConfirm()
            s.entries.isEmpty() -> go(RecordStep.FOOD)
            else -> go(RecordStep.MORE)
        }
    }

    private fun go(step: RecordStep, status: String? = null) {
        val current = _ui.value.step
        if (current != step && current != RecordStep.LOADING && current != RecordStep.SAVED) history.addLast(current)
        val (question, help) = promptFor(step)
        _ui.update {
            it.copy(
                step = step,
                question = question,
                help = help,
                speakKey = it.speakKey + 1,
                status = status ?: it.status
            )
        }
    }

    private fun promptFor(step: RecordStep): Pair<String, String> {
        val s = _ui.value
        return when (step) {
            RecordStep.DATE -> DietRecordText.DATE_Q to DietRecordText.DATE_HELP
            RecordStep.MEAL -> DietRecordText.MEAL_Q to DietRecordText.MEAL_HELP.format(s.defaultMeal.label)
            RecordStep.FOOD -> DietRecordText.FOOD_Q to DietRecordText.FOOD_HELP
            RecordStep.PICK -> DietRecordText.PICK_Q to pickHelp(s)
            RecordStep.AMOUNT -> DietRecordText.AMOUNT_Q.format(s.pendingName.orEmpty()) to DietRecordText.AMOUNT_HELP
            RecordStep.MORE -> DietRecordText.MORE_Q to DietRecordText.MORE_HELP
            RecordStep.CONFIRM -> DietRecordText.CONFIRM_Q to DietRecordText.CONFIRM_HELP
            RecordStep.EDIT -> DietRecordText.EDIT_Q to DietRecordText.EDIT_HELP
            RecordStep.SAVED -> DietRecordText.SAVED_TITLE to s.savedMessage.orEmpty()
            RecordStep.LOADING -> "" to ""
        }
    }

    private fun pickHelp(s: RecordUi): String = if (s.candidates.isEmpty()) {
        DietRecordText.PICK_NONE.format(Josa.eun("「${s.pendingName.orEmpty()}」"))
    } else {
        s.candidates.mapIndexed { i, c -> "${i + 1}번 ${c.name}" }.joinToString(", ") + ". " + DietRecordText.PICK_HELP
    }

    /** 이전 단계로(「뒤로」, 화면의 뒤로 가기). 첫 단계면 false — 화면이 흐름을 닫는다. */
    fun back(): Boolean {
        val s = _ui.value
        if (s.saving || s.step == RecordStep.SAVED) return false
        if (s.warnings.isNotEmpty()) return true
        val previous = history.removeLastOrNull() ?: return false
        if (previous == RecordStep.CONFIRM || s.step == RecordStep.CONFIRM) editing = false
        val (question, help) = promptFor(previous)
        _ui.update { it.copy(step = previous, question = question, help = help, speakKey = it.speakKey + 1, status = "") }
        return true
    }

    // ── 버튼·키보드 입력 ───────────────────────────────────────

    fun chooseDate(daysAgo: Long) = setDate(today().minusDays(daysAgo))

    private fun setDate(date: LocalDate): Boolean {
        if (date.isAfter(today())) { // DREC-11 미래 날짜 차단
            _ui.update { it.copy(status = DietRecordText.DATE_FUTURE) }
            return true
        }
        _ui.update { it.copy(date = date, status = "") }
        next()
        return true
    }

    fun chooseMeal(meal: MealType) {
        _ui.update { it.copy(meal = meal, status = "") }
        next()
    }

    /** 음식 단계의 키보드 입력·말(여러 음식을 한 번에 말해도 된다: 「김치찌개 한 그릇이랑 밥 반 공기」). */
    fun submitFood(text: String): Boolean {
        val mentions = parser.parse(text, today()).foods
        if (mentions.isEmpty()) return false
        askMore = true
        queue = queue + mentions
        _ui.update { it.copy(status = "") }
        next()
        return true
    }

    fun pick(index: Int) {
        val choice = _ui.value.candidates.getOrNull(index) ?: return
        _ui.update { it.copy(status = "") }
        accept(choice)
    }

    /** 목록에 없음 → 이름만 기록(영양 정보 없음, API food_name). */
    fun pickNameOnly() {
        _ui.update { it.copy(status = "") }
        accept(FoodChoice(_ui.value.pendingName.orEmpty()))
    }

    /** 음식 이름을 다시 말하기. */
    fun retryFood() {
        _ui.update { it.copy(pendingName = null, pendingQuantity = null, candidates = emptyList()) }
        go(RecordStep.FOOD)
    }

    fun chooseAmount(quantity: Quantity) {
        val s = _ui.value
        val choice = pendingChoice ?: return
        if (s.step != RecordStep.AMOUNT) return
        _ui.update { it.copy(status = "") }
        addEntry(choice, quantity)
    }

    fun more(yes: Boolean) {
        _ui.update { it.copy(status = "") }
        if (yes) go(RecordStep.FOOD) else openConfirm()
    }

    /** [음성으로 수정] → 고칠 곳 고르기. */
    fun edit() {
        if (_ui.value.step == RecordStep.CONFIRM) go(RecordStep.EDIT)
    }

    fun editTarget(target: EditTarget) {
        editing = true
        _ui.update { it.copy(status = "") }
        when (target) {
            EditTarget.DATE -> go(RecordStep.DATE)

            EditTarget.MEAL -> go(RecordStep.MEAL)

            EditTarget.FOODS -> {
                editing = false
                askMore = true
                _ui.update { it.copy(entries = emptyList(), confirm = null) }
                go(RecordStep.FOOD)
            }

            EditTarget.ADD -> {
                editing = false
                askMore = true
                go(RecordStep.FOOD)
            }
        }
    }

    /** 저장 뒤 [더 기록하기]: 같은 날짜·끼니로 새로 시작. */
    fun recordAnother() {
        val s = _ui.value
        queue = emptyList()
        askMore = true
        editing = false
        history.clear()
        _ui.value = RecordUi(date = s.date, meal = s.meal, defaultMeal = s.defaultMeal, speakKey = s.speakKey)
        next()
    }

    // ── 음식 찾기(후보 최대 3개, UIUX §2.4 P0) ──────────────────────

    private var pendingChoice: FoodChoice? = null

    private fun resolveNext() {
        val mention = queue.first()
        queue = queue.drop(1)
        _ui.update { it.copy(pendingName = mention.name, pendingQuantity = mention.quantity, candidates = emptyList()) }
        viewModelScope.launch { resolve(mention) }
    }

    private suspend fun resolve(mention: FoodMention) {
        val term = FoodSearchTerms.of(mention.name)
        val first = search(term)
        val result = if (first is ApiResult.Success && first.data.isEmpty() && mention.spoken != term) search(mention.spoken) else first
        val online = result as? ApiResult.Success
        val found = online?.data ?: foodCache.search(term).ifEmpty { foodCache.search(mention.spoken) }
        if (online == null) _ui.update { it.copy(offline = true) }
        val list = FoodSearchTerms.relevant(term, found).ifEmpty { FoodSearchTerms.relevant(mention.spoken, found) }
        val choices = list.map(FoodChoice::of).distinctBy { it.name }.take(MAX_CANDIDATES)
        val names = setOf(normalize(term), normalize(mention.name), normalize(mention.spoken))
        val exact = choices.firstOrNull()?.takeIf { normalize(it.name) in names }
        when {
            exact != null -> {
                _ui.update { it.copy(status = DietRecordText.FOOD_FOUND.format(Josa.ro(exact.name))) }
                accept(exact)
            }

            choices.isNotEmpty() -> {
                _ui.update { it.copy(candidates = choices) }
                go(RecordStep.PICK)
            }

            online == null -> {
                _ui.update { it.copy(status = DietRecordText.OFFLINE_NAME_ONLY.format(Josa.eun(mention.name))) }
                accept(FoodChoice(mention.name))
            }

            else -> go(RecordStep.PICK)
        }
    }

    private suspend fun search(query: String): ApiResult<List<FoodSummaryDto>> = foods.search(query, SEARCH_LIMIT)

    private fun accept(choice: FoodChoice) {
        val quantity = _ui.value.pendingQuantity
        pendingChoice = choice
        if (quantity == null || quantity.needsConfirmation) {
            go(RecordStep.AMOUNT)
        } else {
            addEntry(choice, quantity)
        }
    }

    private fun addEntry(choice: FoodChoice, quantity: Quantity) {
        pendingChoice = null
        _ui.update {
            it.copy(
                entries = it.entries + RecordEntry(choice, quantity, offlineKcal(choice, quantity)),
                pendingName = null,
                pendingQuantity = null,
                candidates = emptyList(),
                confirm = null
            )
        }
        next()
    }

    /** 기기 안 열량(영양 DB 1인분 × 배수, 그램이면 1회 제공량으로 나눔). 확인 화면 평가가 오면 그 값으로 바꾼다. */
    private fun offlineKcal(choice: FoodChoice, q: Quantity): Double? {
        val perServing = choice.kcalPerServing ?: return null
        val grams = q.grams
        val serving = choice.food?.serving?.amount
        return when {
            grams != null && serving != null && serving > 0 -> perServing * grams / serving
            grams != null -> null
            else -> perServing * (q.ratio ?: 1.0)
        }
    }

    // ── 확인 화면·경고(DREC-08·09) ─────────────────────────────────

    private fun openConfirm() {
        editing = false
        _ui.update { it.copy(evaluating = true, confirm = null, warnings = emptyList(), warningDetail = null) }
        go(RecordStep.CONFIRM)
        viewModelScope.launch { evaluate() }
    }

    private suspend fun evaluate() {
        val entries = _ui.value.entries
        val result = foods.evaluate(entries.map { it.toItem() })
        val (updated, warnings, notes) = when (result) {
            is ApiResult.Success -> fromServer(entries, result.data)
            is ApiResult.Failure -> fromDevice(entries, result.failure)
        }
        val confirm = confirmOf(updated, notes)
        _ui.update { it.copy(entries = updated, evaluating = false, confirm = confirm, warnings = warnings, speakKey = it.speakKey + 1) }
        warnings.firstOrNull()?.let(::signal)
    }

    private fun fromServer(entries: List<RecordEntry>, r: DietEvaluateResponse): Triple<List<RecordEntry>, List<WarningUi>, List<String>> {
        val updated = entries.mapIndexed { i, e ->
            val item = r.items.getOrNull(i) ?: return@mapIndexed e
            // DREC-09: 당뇨를 선택한 사용자에게만 음식별 지표 등급을 확인 화면에서 읽는다.
            val indicator = item.sugarIndex?.takeIf { snapshot.diabetes && it.computable }?.label
            e.copy(kcal = item.nutrition.kcal ?: e.kcal, indicator = indicator)
        }
        val warnings = DietWarnings.order(r.warnings)
        val infos = r.warnings.filter { it.type == DietWarnings.ALLERGY && it.level == DietWarnings.INFO }.mapNotNull { it.ttsMessage }
        return Triple(updated, warnings, infos)
    }

    /** 끊김·서버 오류: 기기 안 알레르기 대조만(N-05). 당류 지표는 당뇨 선택자에게 「지금 알 수 없어요」. */
    private fun fromDevice(entries: List<RecordEntry>, failure: ApiFailure): Triple<List<RecordEntry>, List<WarningUi>, List<String>> {
        val warnings = entries.mapNotNull { e ->
            val hits = AllergenKeywords.match(e.choice.name, snapshot.allergens)
            if (hits.isEmpty()) {
                null
            } else {
                WarningUi(
                    WarningKind.ALLERGY,
                    DietRecordText.OFFLINE_ALLERGY.format(e.choice.name, hits.joinToString(", ") { it.nameKo }),
                    listOf(DietWarnings.ALTERNATIVE_DIET),
                    hits.map { it.code }
                )
            }
        }
        val notes = buildList {
            add(if (failure.isNetwork) DietRecordText.OFFLINE_CHECKED else DietRecordText.CHECK_FAILED)
            if (snapshot.diabetes) add(DietRecordText.OFFLINE_SUGAR)
        }
        if (failure.isNetwork) _ui.update { it.copy(offline = true) }
        return Triple(entries, warnings, notes)
    }

    private fun confirmOf(entries: List<RecordEntry>, notes: List<String>): ConfirmUi {
        val s = _ui.value
        val head = "${dateLabel(requireNotNull(s.date), today())} ${requireNotNull(s.meal).label}"
        val total = entries.mapNotNull { it.kcal }.takeIf { it.size == entries.size && it.isNotEmpty() }?.sum()
        val lines = listOf(head) + entries.map { it.line } + listOfNotNull(total?.let { DietRecordText.CONFIRM_TOTAL.format(kcalText(it)) })
        val readBack = (lines + DietRecordText.CONFIRM_Q).joinToString(". ")
        return ConfirmUi(lines, notes + DietRecordText.NOTICE, readBack)
    }

    /** 경고가 맨 앞에 설 때: 종류별 진동 + 소리. */
    private fun signal(w: WarningUi) {
        when (w.kind) {
            WarningKind.ALLERGY -> {
                haptics.vibrate(HapticPattern.ALLERGY_WARNING)
                sounds.play(WarningSound.ALLERGY)
            }

            WarningKind.SUGAR_HIGH -> {
                haptics.vibrate(HapticPattern.SUGAR_HIGH)
                sounds.play(WarningSound.SUGAR)
            }

            WarningKind.SUGAR_VERY_HIGH -> {
                haptics.vibrate(HapticPattern.SUGAR_VERY_HIGH)
                sounds.play(WarningSound.SUGAR)
            }
        }
    }

    /** 경고 팝업 [알겠어요]·[확인]: 다음 경고, 없으면 확인 화면 되읽기. */
    fun acknowledgeWarning() {
        val rest = _ui.value.warnings.drop(1)
        _ui.update { it.copy(warnings = rest, warningDetail = null, speakKey = it.speakKey + 1) }
        rest.firstOrNull()?.let(::signal)
    }

    /** 「이대로 기록해」(팝업 화면 명령): 남은 경고를 닫고 확인 화면으로 — 저장은 확인 화면의 「네」로만. */
    fun acknowledgeAll() {
        _ui.update { it.copy(warnings = emptyList(), warningDetail = null, speakKey = it.speakKey + 1) }
    }

    /** 경고 팝업의 [대체 식단 보기](알레르기)·[추천 음식 보기](당류) — 팝업 안에 읽어 준다. */
    fun showWarningDetail() {
        val w = _ui.value.warnings.firstOrNull() ?: return
        _ui.update { it.copy(warningDetail = DietRecordText.ALTERNATIVE_LOADING) }
        viewModelScope.launch {
            val r = if (w.kind == WarningKind.ALLERGY) {
                foods.recommendations(DietWarnings.TYPE_ALLERGY, w.allergenCodes.firstOrNull())
            } else {
                foods.recommendations(DietWarnings.TYPE_DISEASE, Diseases.DIABETES)
            }
            val detail = (r as? ApiResult.Success)?.data?.let { DietWarnings.detailOf(w.kind, it) } ?: DietRecordText.ALTERNATIVE_FAILED
            _ui.update { if (it.warnings.firstOrNull() == w) it.copy(warningDetail = detail) else it }
        }
    }

    /** [이대로 등록 완료]·「네」: 경고를 모두 닫고 되읽기를 보인 확인 화면에서만, 한 번만 저장한다. */
    fun confirmSave() {
        val s = _ui.value
        if (!s.canSave) return
        _ui.update { it.copy(saving = true, status = DietRecordText.SAVING) }
        viewModelScope.launch {
            val draft =
                MealDraft(requireNotNull(s.date), requireNotNull(s.meal), InputMethod.VOICE, s.entries.map { it.toItem(withKcal = true) })
            val saved = runCatching { meals.record(draft) }
            if (saved.isFailure) {
                _ui.update { it.copy(saving = false, status = DietRecordText.SAVE_FAILED) }
                return@launch
            }
            foodCache.remember(s.entries.mapNotNull { it.choice.food })
            haptics.vibrate(HapticPattern.SAVED)
            val message = if (s.offline) DietRecordText.SAVED_OFFLINE else DietRecordText.SAVED
            _ui.update { it.copy(saving = false, savedMessage = message, status = message) }
            go(RecordStep.SAVED, message)
        }
    }

    private fun RecordEntry.toItem(withKcal: Boolean = false) = MealItem(
        foodName = choice.name,
        foodId = choice.id,
        ratio = quantity.ratio.takeIf { quantity.grams == null },
        grams = quantity.grams,
        amountText = quantity.text.ifBlank { RecordEntry.DEFAULT_AMOUNT },
        // 기기 목록·「오늘 몇 칼로리」용 열량. nutrition_source가 없으니 서버로는 보내지 않는다(서버가 DB로 다시 계산).
        nutrition = if (withKcal) kcal?.let { Nutrition(kcal = it) } else null
    )

    // ── 말 ──────────────────────────────────────────────────

    /** 이 화면의 말(값 입력). 처리했으면 true — 아니면 비서가 어디서나 명령으로 본다. */
    fun onSpeech(candidates: List<String>): Boolean {
        val text = candidates.firstOrNull()?.trim().orEmpty()
        if (text.isEmpty()) return false
        val s = _ui.value
        if (s.saving || s.step == RecordStep.LOADING) return true
        if (AssistantText.compact(text) in BACK_WORDS) return back()
        if (s.warnings.isNotEmpty()) return DietSpeech.warning(this, text)
        val handled = when (s.step) {
            RecordStep.DATE -> candidates.firstNotNullOfOrNull { DateParser.parse(it, today()) }?.let(::setDate)
                ?: (AnswerParser.parse(text) == Answer.YES).also { if (it) setDate(today()) }

            RecordStep.MEAL -> DietSpeech.meal(this, candidates, s.defaultMeal)

            RecordStep.FOOD -> DietSpeech.food(this, text, s)

            RecordStep.PICK -> DietSpeech.pick(this, candidates, s)

            RecordStep.AMOUNT -> DietSpeech.amount(this, candidates)

            RecordStep.MORE -> DietSpeech.more(this, text)

            RecordStep.CONFIRM -> DietSpeech.confirm(this, text, s)

            RecordStep.EDIT -> DietSpeech.edit(this, text)

            RecordStep.SAVED, RecordStep.LOADING -> false
        }
        if (!handled) _ui.update { it.copy(status = DietRecordText.HEARD_NOT.format(text, it.help)) }
        return handled
    }

    /** 확인 화면의 「다시 들려줘」: 되읽기 다시. */
    internal fun repeat() = _ui.update { it.copy(speakKey = it.speakKey + 1) }

    companion object {
        const val MAX_CANDIDATES = 3
        private const val SEARCH_LIMIT = 10
        private const val FORBIDDEN = 403
        private val BACK_WORDS = setOf("뒤로", "뒤로가", "뒤로가줘", "이전", "이전단계", "전단계", "앞단계", "이전으로")

        internal fun normalize(name: String) = name.replace(Regex("""[\s_·,()]"""), "")

        internal fun quantityOf(answer: String): Quantity? {
            if (AnswerParser.parse(answer) == Answer.YES) return Quantity(ratio = 1.0, text = RecordEntry.DEFAULT_AMOUNT)
            return QuantityParser.parse(answer).takeUnless { it.needsConfirmation }
        }
    }
}

/** 확인 화면 [음성으로 수정]의 고칠 곳. */
enum class EditTarget { DATE, MEAL, FOODS, ADD }
