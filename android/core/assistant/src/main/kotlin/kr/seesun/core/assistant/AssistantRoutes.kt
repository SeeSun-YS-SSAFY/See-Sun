package kr.seesun.core.assistant

/** 운동 그래프 안에서 처음 열 화면(§4.7 「ExerciseRoute(entry=…)」). */
enum class ExerciseEntry {
    HOME,
    SINGLE,
    CATEGORY,
    PLAY,
    ROUTINE_MENU,
    ROUTINE_RECOMMENDED,
    ROUTINE_CATEGORY,
    ROUTINE_CUSTOM,
    WEEKLY,
    SAFETY
}

/**
 * 비서가 데려갈 곳(§5.1 경로표). 앱(app 모듈)이 타입 지정 경로로 바꾼다 — 이 모듈은 화면 모듈을 모른다.
 * [label]은 「○○로 갈게요」에 끼우는 이름이다.
 */
sealed interface Destination {
    val label: String

    /** 건강 정보를 바꾸는 확인 화면인가(값이 채워진 채로 열리고, 화면의 [확인]을 눌러야 저장 — §3.1 원칙). */
    val confirmScreen: Boolean get() = false

    data object Main : Destination {
        override val label = "처음 화면"
    }

    /** @param arg CATEGORY·ROUTINE_CATEGORY = 카테고리 짧은 이름(「근력」), PLAY = 운동 ID */
    data class Exercise(val entry: ExerciseEntry, override val label: String, val arg: String? = null, val title: String? = null) :
        Destination

    data object MyPage : Destination {
        override val label = "마이페이지"
    }

    /** MY-02 내 정보 수정. [value]가 있으면 그 항목의 되읽기 확인을 값이 채워진 채로 연다(A-01). */
    data class ProfileEdit(val field: String? = null, val value: String? = null) : Destination {
        override val label = "내 정보 수정"
        override val confirmScreen: Boolean get() = value != null
    }

    data object HealthEdit : Destination {
        override val label = "건강 정보 수정"
    }

    data object Settings : Destination {
        override val label = "설정"
    }

    /** 설정 갈래(VOICE·ACCOUNT …, feature:mypage SettingsGroup 이름). */
    data class SettingsGroup(val group: String, override val label: String) : Destination

    data object VoiceAuto : Destination {
        override val label = "음성 자동 인식 설정"
    }

    data object AppVoice : Destination {
        override val label = "앱 음성 안내 설정"
    }

    data object HardwareButton : Destination {
        override val label = "물리 버튼 설정"
    }

    data object Contact : Destination {
        override val label = "문의하기"
    }

    data object DeleteAccount : Destination {
        override val label = "회원 탈퇴"
    }

    data object DataReset : Destination {
        override val label = "데이터 초기화"
    }

    data object AppVersion : Destination {
        override val label = "앱 버전"
    }

    data object HowTo : Destination {
        override val label = "사용법"
    }

    data object Diet : Destination {
        override val label = "식단"
    }

    /**
     * 음성 식사 기록 흐름(v2.0, DREC-01~04). [prefill]이 있으면(A-02 「점심에 김치찌개 먹었어」) 흐름이 그 말을 다시 해석해 빈 단계만 묻고
     * 확인 화면으로 간다. 저장은 늘 확인 화면의 되읽기와 [이대로 등록]·「네」 뒤에만 한다.
     */
    data class DietRecord(val prefill: String? = null) : Destination {
        override val label = "식사 기록"
        override val confirmScreen: Boolean get() = true
    }

    /** 오늘 식사 기록 목록(삭제할 수 있다). */
    data object DietToday : Destination {
        override val label = "오늘 식사 기록"
    }
}

/** 의도 → 데려갈 곳(§5.1). 답만 하는 의도(Q-01·Q-02 등)·안전·준비 중은 null. */
object AssistantRoutes {
    fun of(match: IntentMatch): Destination? {
        val s = match.slots
        return when (match.id) {
            IntentId.HOME -> Destination.Main

            IntentId.EXERCISE_HOME -> Destination.Exercise(ExerciseEntry.HOME, "운동 화면")

            IntentId.EXERCISE_CATEGORY -> s[Slot.CATEGORY]?.let { Destination.Exercise(ExerciseEntry.CATEGORY, "$it 운동", it) }

            IntentId.EXERCISE_BY_NAME -> s[Slot.EXERCISE_ID]?.let { id ->
                val name = s[Slot.EXERCISE_NAME].orEmpty()
                Destination.Exercise(ExerciseEntry.PLAY, name, id, name)
            }

            IntentId.ROUTINE -> routine(s)

            IntentId.EXERCISE_SAFETY -> Destination.Exercise(ExerciseEntry.SAFETY, "운동 안전 안내")

            IntentId.EXERCISE_WEEKLY -> Destination.Exercise(ExerciseEntry.WEEKLY, "운동 기록")

            IntentId.MY_PAGE -> Destination.MyPage

            IntentId.PROFILE_EDIT -> Destination.ProfileEdit(s[Slot.FIELD])

            IntentId.HEALTH_VALUE -> Destination.ProfileEdit(s[Slot.FIELD], s[Slot.VALUE])

            IntentId.HEALTH_EDIT -> Destination.HealthEdit

            IntentId.SETTINGS -> Destination.Settings

            IntentId.SETTINGS_VOICE -> Destination.SettingsGroup("VOICE", "음성 안내 설정")

            IntentId.APP_VOICE -> Destination.AppVoice

            IntentId.HARDWARE_BUTTON -> Destination.HardwareButton

            IntentId.CONTACT -> Destination.Contact

            IntentId.ACCOUNT_RISK -> when (s[Slot.TARGET]) {
                "DELETE" -> Destination.DeleteAccount
                "LOGOUT" -> Destination.SettingsGroup("ACCOUNT", "계정 설정")
                else -> Destination.DataReset
            }

            IntentId.APP_VERSION -> Destination.AppVersion

            IntentId.HOW_TO -> Destination.HowTo

            IntentId.DIET_HOME -> Destination.Diet

            IntentId.DIET_RECORD -> Destination.DietRecord(s[Slot.UTTERANCE])

            IntentId.DIET_RECORD_START -> Destination.DietRecord()

            IntentId.DIET_TODAY -> Destination.DietToday

            else -> null
        }
    }

    private fun routine(s: Map<String, String>): Destination = when (s[Slot.KIND]) {
        "RECOMMENDED" -> Destination.Exercise(ExerciseEntry.ROUTINE_RECOMMENDED, "추천 루틴")
        "CUSTOM" -> Destination.Exercise(ExerciseEntry.ROUTINE_CUSTOM, "내가 만든 루틴")
        "CATEGORY" -> Destination.Exercise(ExerciseEntry.ROUTINE_CATEGORY, "${s[Slot.CATEGORY]} 루틴", s[Slot.CATEGORY])
        else -> Destination.Exercise(ExerciseEntry.ROUTINE_MENU, "루틴")
    }

    /** 운동 기록 화면(Q-01 「기록 화면을 열까요?」에 「네」). */
    val WEEKLY: Destination = Destination.Exercise(ExerciseEntry.WEEKLY, "운동 기록")
    val EXERCISE_HOME: Destination = Destination.Exercise(ExerciseEntry.HOME, "운동 화면")
}
