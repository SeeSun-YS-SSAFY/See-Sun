package kr.seesun.core.assistant

/** 시험 공용: 내장 운동 사전, 화면 문맥(앱 화면의 말로 고르기 낱말과 같음), 가짜 사실값. */
object AssistantFixtures {
    val catalog: ExerciseCatalog by lazy {
        val stream = requireNotNull(javaClass.classLoader?.getResourceAsStream("corpus/exercises.tsv")) { "exercises.tsv 없음" }
        val items = stream.reader(Charsets.UTF_8).readLines()
            .filter { it.isNotBlank() && !it.startsWith("#") }
            .map { it.split("\t").let { (id, name) -> id to name } }
        ExerciseCatalog.of(items)
    }

    fun engine() = AssistantEngine { catalog }

    /** 메인(feature:main MainScreen의 VoiceMenuOption과 같은 낱말). */
    val main = ScreenVoiceContext(
        ScreenCatalog.MAIN,
        local = listOf(
            LocalCommand(listOf("식단", "식사", "밥"), IntentId.DIET_HOME),
            LocalCommand(listOf("운동"), IntentId.EXERCISE_HOME),
            LocalCommand(listOf("약", "복약"), IntentId.MEDICINE_SOON),
            LocalCommand(listOf("마이페이지", "마이 페이지", "내 정보"), IntentId.MY_PAGE),
            LocalCommand(listOf("설정"), IntentId.SETTINGS)
        )
    )

    /** 운동 방식(feature:exercise homeVoiceOptions와 같은 낱말, 카테고리는 기본 순서). */
    val exerciseHome = ScreenVoiceContext(
        ScreenCatalog.EXERCISE_HOME,
        local = listOf("근력", "유산소", "유연성", "균형").map { LocalCommand(listOf(it), IntentId.EXERCISE_CATEGORY) } + listOf(
            LocalCommand(listOf("추천", "추천 루틴"), IntentId.ROUTINE),
            LocalCommand(listOf("루틴", "이어서"), IntentId.ROUTINE),
            LocalCommand(listOf("기록", "이번 주", "이번주"), IntentId.EXERCISE_WEEKLY),
            LocalCommand(listOf("안전", "건강 확인"), IntentId.EXERCISE_SAFETY),
            LocalCommand(listOf("전체", "즐겨찾기", "자주", "한 가지", "한가지"))
        )
    )

    /** 마이페이지(feature:mypage MyPageScreen의 VoiceMenuOption과 같은 낱말). */
    val myPage = ScreenVoiceContext(
        ScreenCatalog.MY_PAGE,
        local = listOf(
            LocalCommand(listOf("내 정보", "정보 수정", "프로필"), IntentId.PROFILE_EDIT),
            LocalCommand(listOf("건강"), IntentId.HEALTH_EDIT),
            LocalCommand(listOf("운동 기록", "운동"), IntentId.EXERCISE_WEEKLY),
            LocalCommand(listOf("식단 기록", "식단"), IntentId.DIET_HOME),
            LocalCommand(listOf("설정"), IntentId.SETTINGS)
        )
    )

    val unknown = ScreenVoiceContext()
    val value = ScreenVoiceContext(ScreenCatalog.PROFILE_EDIT, mode = ScreenMode.VALUE_CAPTURE)
    val playback = ScreenVoiceContext(ScreenCatalog.EXERCISE_PLAY, mode = ScreenMode.PLAYBACK)

    fun screen(name: String): ScreenVoiceContext = when (name) {
        "main" -> main
        "exercise.home" -> exerciseHome
        "mypage" -> myPage
        "value" -> value
        "playback" -> playback
        else -> unknown
    }

    class FakeFacts(
        var weeklyFact: WeeklyFact? = WeeklyFact(3, 75),
        var goalFact: GoalFact? = GoalFact("체중 감량", 1800),
        var speedNow: String = "NORMAL",
        var auto: Boolean = true,
        var spoken: String? = "운동 방식 화면이에요.",
        var origin: Boolean = true,
        var kcal: KcalFact? = KcalFact(1240, 2000, meals = 2),
        var mealFacts: List<MealFact>? = listOf(MealFact("점심", listOf("김치찌개 한 그릇", "밥 반 공기"))),
        var foodFact: (String) -> FoodFact = { FoodFact.Found(it, "180그램", 259, carbG = 50, sugarG = 9) },
        var checkFact: (String) -> FoodCheckFact = { FoodCheckFact(it, found = true) }
    ) : AssistantFacts {
        val mealQueries = mutableListOf<Pair<String?, Boolean>>()

        override suspend fun todayKcal(): KcalFact? = kcal

        override suspend fun meals(meal: String?, yesterday: Boolean): List<MealFact>? {
            mealQueries += meal to yesterday
            return mealFacts
        }

        override suspend fun food(name: String): FoodFact = foodFact(name)

        override suspend fun foodCheck(name: String): FoodCheckFact = checkFact(name)

        override suspend fun weekly(lastWeek: Boolean): WeeklyFact? = weeklyFact

        override suspend fun goal(): GoalFact? = goalFact

        override fun speed(): String = speedNow

        override fun autoListenOn(): Boolean = auto

        override fun lastSpoken(): String? = spoken

        override fun hasOrigin(): Boolean = origin
    }
}
