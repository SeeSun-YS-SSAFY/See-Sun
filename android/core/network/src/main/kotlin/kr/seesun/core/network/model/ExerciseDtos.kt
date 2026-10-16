package kr.seesun.core.network.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

// 운동(API 계약 §10, openapi.yaml v1.0.1) — feature:exercise 전용 DTO.
// 식단 쪽 DTO 파일(DietDtos.kt)과 분리해 두어 다른 작업 브랜치와 겹치지 않게 한다.

/** GET /exercises/category/ 한 건 */
@Serializable
data class ExerciseCategoryDto(@SerialName("category_id") val categoryId: String, @SerialName("display_name") val displayName: String)

/** 목록 항목(카테고리별 목록·자주 하는 운동 공통). 자주 하는 운동은 count·category_name이 더 온다. */
@Serializable
data class ExerciseSummaryDto(
    @SerialName("exercise_id") val exerciseId: String,
    @SerialName("exercise_name") val exerciseName: String,
    @SerialName("pictogram_url") val pictogramUrl: String? = null,
    @SerialName("name_audio_url") val nameAudioUrl: String? = null,
    @SerialName("category_name") val categoryName: String? = null,
    val count: Int? = null,
    @SerialName("last_performed_at") val lastPerformedAt: String? = null
)

/** GET /exercises/category/{id}/ */
@Serializable
data class ExerciseCategoryDetailDto(
    @SerialName("category_id") val categoryId: String,
    @SerialName("category_name") val categoryName: String,
    val exercises: List<ExerciseSummaryDto> = emptyList()
)

/** GET /exercises/frequent/ */
@Serializable
data class FrequentExercisesDto(val exercises: List<ExerciseSummaryDto> = emptyList())

/** 구간별 안내 음성. section = exercise_description, first_description, main_form, stay_form, fixed_form, exercise_guide_text */
@Serializable
data class ExerciseAudioDto(val type: String? = null, val section: String? = null, val url: String? = null)

/** GET /exercises/{id}/ */
@Serializable
data class ExerciseDetailDto(
    @SerialName("exercise_id") val exerciseId: String,
    @SerialName("exercise_name") val exerciseName: String,
    @SerialName("category_name") val categoryName: String? = null,
    @SerialName("exercise_description") val exerciseDescription: String? = null,
    @SerialName("first_description") val firstDescription: String? = null,
    @SerialName("main_form") val mainForm: String? = null,
    @SerialName("form_description") val formDescription: String? = null,
    @SerialName("stay_form") val stayForm: String? = null,
    @SerialName("fixed_form") val fixedForm: String? = null,
    @SerialName("exercise_guide") val exerciseGuide: String? = null,
    val pictograms: List<String> = emptyList(),
    @SerialName("pictogram_frames") val pictogramFrames: List<PictogramFrameDto> = emptyList(),
    @SerialName("pictogram_cycle") val pictogramCycle: PictogramCycleDto? = null,
    val audios: List<ExerciseAudioDto> = emptyList(),
    /** 대본을 고친 운동(의뢰인 확인 필요). */
    @SerialName("script_revised") val scriptRevised: Boolean = false,
    /** 고친 대본의 녹음을 아직 다시 만들지 않음: 운동 전체를 기기 음성으로 읽는다(구간마다 목소리가 바뀌지 않게). */
    @SerialName("audio_stale") val audioStale: Boolean = false
)

/**
 * 픽토그램 한 장과 대본 위치(2026-10-14 육안 검토 연결표). [section]·[sentenceIndex]는 이 그림이 보이기 시작하는 대본 위치,
 * [label]은 그림 이름(낭독용), [displayUrl]은 여백·인쇄 글자를 잘라 그림을 크게 한 보기용 사본(없으면 [url]),
 * [redrawn]은 대본에 맞게 다시 그린 그림(의뢰인 확인 필요). 연결이 없으면 null(앱은 모든 그림을 1초마다 돌림).
 */
@Serializable
data class PictogramFrameDto(
    val url: String,
    val sequence: Int,
    val section: String? = null,
    @SerialName("sentence_index") val sentenceIndex: Int? = null,
    val label: String? = null,
    @SerialName("needs_client") val needsClient: Boolean = false,
    @SerialName("display_url") val displayUrl: String? = null,
    val redrawn: Boolean = false
)

/** 반복·유지 구간: [section]의 [startSentenceIndex]번째 문장부터 [sequences] 그림을 1초마다 되풀이(같은 번호가 여러 번 올 수 있음). */
@Serializable
data class PictogramCycleDto(
    val section: String,
    val sequences: List<Int> = emptyList(),
    val kind: String? = null,
    @SerialName("start_sentence_index") val startSentenceIndex: Int = 0,
    @SerialName("hold_sequence") val holdSequence: Int? = null
)

/** 루틴(플레이리스트) 항목 */
@Serializable
data class PlaylistItemDto(
    @SerialName("playlist_item_id") val playlistItemId: String,
    @SerialName("exercise_id") val exerciseId: String,
    @SerialName("exercise_name") val exerciseName: String,
    @SerialName("sequence_no") val sequenceNo: Int,
    @SerialName("set_count") val setCount: Int = 1,
    @SerialName("reps_count") val repsCount: Int? = null,
    @SerialName("duration_sec") val durationSec: Int? = null,
    @SerialName("rest_sec") val restSec: Int = 10,
    val audios: List<ExerciseAudioDto> = emptyList(),
    val pictograms: List<String> = emptyList(),
    @SerialName("pictogram_frames") val pictogramFrames: List<PictogramFrameDto> = emptyList(),
    @SerialName("pictogram_cycle") val pictogramCycle: PictogramCycleDto? = null,
    @SerialName("script_revised") val scriptRevised: Boolean = false,
    @SerialName("audio_stale") val audioStale: Boolean = false
)

/** GET /exercises/playlist/{id}/ · 목록 */
@Serializable
data class PlaylistDto(
    @SerialName("playlist_id") val playlistId: String,
    val title: String,
    val mode: String? = null,
    val status: String? = null,
    val items: List<PlaylistItemDto> = emptyList()
)

@Serializable
data class PlaylistItemInputDto(
    @SerialName("exercise_id") val exerciseId: String,
    @SerialName("sequence_no") val sequenceNo: Int,
    @SerialName("set_count") val setCount: Int,
    @SerialName("reps_count") val repsCount: Int? = null
)

/** POST /exercises/playlist/create/ */
@Serializable
data class PlaylistCreateRequest(val title: String, val items: List<PlaylistItemInputDto>)

/** POST /exercises/playlist/{id}/items/ (sequence_no를 빼면 서버가 마지막+1) */
@Serializable
data class PlaylistItemAddRequest(
    @SerialName("exercise_id") val exerciseId: String,
    @SerialName("set_count") val setCount: Int,
    @SerialName("reps_count") val repsCount: Int? = null
)

/** PATCH /exercises/playlist/{id}/ */
@Serializable
data class PlaylistTitleRequest(val title: String)

/**
 * PATCH /exercises/playlist/{id}/items/{itemId}/ (서버 PlaylistItemUpdateSerializer: 순서·세트·횟수, 부분 수정).
 * 빈 값(null)은 보내지 않는다(explicitNulls = false). 순서(sequence_no)는 루틴 안에서 겹치면 안 된다(unique_together).
 */
@Serializable
data class PlaylistItemUpdateRequest(
    @SerialName("sequence_no") val sequenceNo: Int? = null,
    @SerialName("set_count") val setCount: Int? = null,
    @SerialName("reps_count") val repsCount: Int? = null
)

/** 운동 기록 항목(§10). skip_reason: LOAD_FAIL|USER_SKIP|UNKNOWN */
@Serializable
data class ExerciseLogItemDto(
    @SerialName("exercise_id") val exerciseId: String,
    @SerialName("sequence_no") val sequenceNo: Int,
    @SerialName("started_at") val startedAt: String? = null,
    @SerialName("ended_at") val endedAt: String? = null,
    @SerialName("duration_ms") val durationMs: Long? = null,
    @SerialName("is_skipped") val isSkipped: Boolean = false,
    @SerialName("skip_reason") val skipReason: String? = null,
    @SerialName("rest_sec") val restSec: Int? = null
)

/**
 * POST /exercise-sessions/bulk/ 한 건. exercise_name 또는 본인 playlist_id 중 하나는 있어야 한다.
 * mode: MANUAL(단일 운동)|CURRICULUM(루틴), status: COMPLETED|ABORTED|IN_PROGRESS,
 * abnormal_end_reason: NORMAL|APP_KILLED|CRASH|UNKNOWN
 */
@Serializable
data class ExerciseLogDto(
    @SerialName("client_uuid") val clientUuid: String,
    @SerialName("exercise_name") val exerciseName: String? = null,
    @SerialName("playlist_id") val playlistId: String? = null,
    val mode: String,
    @SerialName("started_at") val startedAt: String,
    @SerialName("ended_at") val endedAt: String? = null,
    val status: String,
    @SerialName("abnormal_end_reason") val abnormalEndReason: String? = null,
    val items: List<ExerciseLogItemDto> = emptyList()
)

@Serializable
data class ExerciseLogBulkRequest(val sessions: List<ExerciseLogDto>)

@Serializable
data class ExerciseLogResultDto(
    @SerialName("client_uuid") val clientUuid: String? = null,
    val status: String,
    @SerialName("session_id") val sessionId: String? = null,
    @SerialName("is_valid") val isValid: Boolean? = null,
    val errors: JsonElement? = null
)

@Serializable
data class ExerciseLogBulkResponse(val results: List<ExerciseLogResultDto> = emptyList())

@Serializable
data class ExerciseWeekDayDto(
    val date: String,
    val weekday: String? = null,
    @SerialName("session_count") val sessionCount: Int = 0,
    val seconds: Long = 0
)

/** GET /exercise/weekly/?from= */
@Serializable
data class ExerciseWeekDto(
    @SerialName("week_start") val weekStart: String,
    @SerialName("week_end") val weekEnd: String,
    @SerialName("session_count") val sessionCount: Int = 0,
    @SerialName("total_seconds") val totalSeconds: Long = 0,
    @SerialName("total_minutes") val totalMinutes: Int = 0,
    val days: List<ExerciseWeekDayDto> = emptyList(),
    @SerialName("tts_message") val ttsMessage: String? = null
)
