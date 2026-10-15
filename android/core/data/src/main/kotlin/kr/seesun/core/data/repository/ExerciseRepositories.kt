package kr.seesun.core.data.repository

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import java.time.Clock
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kr.seesun.core.data.db.ExerciseLogDao
import kr.seesun.core.data.db.ExerciseLogEntity
import kr.seesun.core.data.db.ExerciseLogState
import kr.seesun.core.data.db.FavoriteExerciseDao
import kr.seesun.core.data.db.FavoriteExerciseEntity
import kr.seesun.core.data.model.Catalog
import kr.seesun.core.data.model.ExerciseCategory
import kr.seesun.core.data.model.ExerciseDetail
import kr.seesun.core.data.model.ExerciseSummary
import kr.seesun.core.data.model.GuideSections
import kr.seesun.core.data.model.PictogramCycle
import kr.seesun.core.data.model.PictogramFrame
import kr.seesun.core.data.model.Routine
import kr.seesun.core.data.model.RoutineItem
import kr.seesun.core.data.sync.ExerciseSyncScheduler
import kr.seesun.core.data.sync.ExerciseSyncer
import kr.seesun.core.data.sync.SyncOutcome
import kr.seesun.core.network.ApiFailure
import kr.seesun.core.network.ApiResult
import kr.seesun.core.network.ExerciseApi
import kr.seesun.core.network.NetworkConfig
import kr.seesun.core.network.apiCall
import kr.seesun.core.network.model.ExerciseDetailDto
import kr.seesun.core.network.model.ExerciseLogItemDto
import kr.seesun.core.network.model.ExerciseWeekDto
import kr.seesun.core.network.model.PlaylistCreateRequest
import kr.seesun.core.network.model.PlaylistDto
import kr.seesun.core.network.model.PlaylistItemAddRequest
import kr.seesun.core.network.model.PlaylistItemInputDto
import kr.seesun.core.network.model.PlaylistTitleRequest
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

// ── 운동 콘텐츠 ────────────────────────────────────────────────────────

/** 운동 목록·상세·루틴(서버 우선, 서버에 닿지 못하면 기기 내장 운동 정보). */
interface ExerciseContentRepository {
    suspend fun categories(): ApiResult<Catalog<List<ExerciseCategory>>>

    /** @param categoryId null이면 전체 29개 */
    suspend fun exercises(categoryId: String?): ApiResult<Catalog<List<ExerciseSummary>>>

    suspend fun detail(exerciseId: String): ApiResult<Catalog<ExerciseDetail>>

    /** 자주 하는 운동(서버 집계, 1차 B5 경로 수정). */
    suspend fun frequent(): ApiResult<List<ExerciseSummary>>

    suspend fun playlists(): ApiResult<List<Routine>>

    suspend fun playlist(playlistId: String): ApiResult<Routine>

    suspend fun createPlaylist(title: String, items: List<RoutineItem>): ApiResult<Routine>

    suspend fun renamePlaylist(playlistId: String, title: String): ApiResult<Routine>

    suspend fun deletePlaylist(playlistId: String): ApiResult<Unit>

    suspend fun addPlaylistItem(playlistId: String, exerciseId: String, setCount: Int, repsCount: Int?): ApiResult<Routine>

    suspend fun deletePlaylistItem(playlistId: String, itemId: String): ApiResult<Routine>

    suspend fun weekly(from: String?): ApiResult<ExerciseWeekDto>
}

/** 기기 내장 운동 정보(assets/exercise_catalog.json, 서버 픽스처에서 생성). */
@Serializable
internal data class BundledCatalog(val categories: List<BundledCategory>, val exercises: List<BundledExercise>)

@Serializable
internal data class BundledCategory(@SerialName("category_id") val id: String, @SerialName("display_name") val name: String)

@Serializable
internal data class BundledExercise(
    @SerialName("exercise_id") val id: String,
    @SerialName("category_id") val categoryId: String,
    @SerialName("exercise_name") val name: String,
    @SerialName("exercise_description") val exerciseDescription: String? = null,
    @SerialName("first_description") val firstDescription: String? = null,
    @SerialName("main_form") val mainForm: String? = null,
    @SerialName("form_description") val formDescription: String? = null,
    @SerialName("stay_form") val stayForm: String? = null,
    @SerialName("fixed_form") val fixedForm: String? = null,
    @SerialName("exercise_guide_text") val exerciseGuideText: String? = null
)

@Singleton
class DefaultExerciseContentRepository @Inject constructor(
    private val api: ExerciseApi,
    private val json: Json,
    private val config: NetworkConfig,
    @ApplicationContext private val context: Context
) : ExerciseContentRepository {

    private val bundled: BundledCatalog by lazy {
        context.assets.open(CATALOG_ASSET).bufferedReader(Charsets.UTF_8).use {
            json.decodeFromString(BundledCatalog.serializer(), it.readText())
        }
    }

    private val detailCache = mutableMapOf<String, ExerciseDetail>()
    private val cacheLock = Mutex()

    override suspend fun categories(): ApiResult<Catalog<List<ExerciseCategory>>> =
        withFallback(apiCall(json) { api.categories() }.map { list -> list.map { ExerciseCategory(it.categoryId, it.displayName) } }) {
            bundled.categories.map { ExerciseCategory(it.id, it.name) }
        }

    override suspend fun exercises(categoryId: String?): ApiResult<Catalog<List<ExerciseSummary>>> {
        val remote: ApiResult<List<ExerciseSummary>> = if (categoryId != null) {
            apiCall(json) { api.category(categoryId) }.map { c ->
                c.exercises.map { ExerciseSummary(it.exerciseId, it.exerciseName, c.categoryName, resolve(it.pictogramUrl)) }
            }
        } else {
            allFromServer()
        }
        return withFallback(remote) {
            val names = bundled.categories.associate { it.id to it.name }
            bundled.exercises.filter { categoryId == null || it.categoryId == categoryId }
                .map { ExerciseSummary(it.id, it.name, names[it.categoryId]) }
        }
    }

    private suspend fun allFromServer(): ApiResult<List<ExerciseSummary>> {
        val cats = when (val r = apiCall(json) { api.categories() }) {
            is ApiResult.Failure -> return r
            is ApiResult.Success -> r.data
        }
        val all = mutableListOf<ExerciseSummary>()
        for (cat in cats) {
            when (val r = apiCall(json) { api.category(cat.categoryId) }) {
                is ApiResult.Failure -> return r

                is ApiResult.Success -> r.data.exercises.mapTo(all) {
                    ExerciseSummary(it.exerciseId, it.exerciseName, r.data.categoryName, resolve(it.pictogramUrl))
                }
            }
        }
        return ApiResult.Success(all)
    }

    override suspend fun detail(exerciseId: String): ApiResult<Catalog<ExerciseDetail>> {
        cacheLock.withLock { detailCache[exerciseId] }?.let { return ApiResult.Success(Catalog(it, fromDevice = false)) }
        val remote = apiCall(json) { api.detail(exerciseId) }.map { it.toModel() }
        if (remote is ApiResult.Success) cacheLock.withLock { detailCache[exerciseId] = remote.data }
        return withFallback(remote) {
            val e = bundled.exercises.firstOrNull { it.id == exerciseId } ?: return ApiResult.Failure(NOT_FOUND)
            val category = bundled.categories.firstOrNull { it.id == e.categoryId }?.name
            ExerciseDetail(
                id = e.id,
                name = e.name,
                categoryName = category,
                sections = GuideSections.build(
                    mapOf(
                        "exercise_description" to e.exerciseDescription,
                        "first_description" to e.firstDescription,
                        "main_form" to e.mainForm,
                        "form_description" to e.formDescription,
                        "stay_form" to e.stayForm,
                        "fixed_form" to e.fixedForm,
                        "exercise_guide_text" to e.exerciseGuideText
                    ),
                    emptyList()
                ),
                pictograms = emptyList(),
                needsAssistant = GuideSections.needsAssistant(e.name)
            )
        }
    }

    private fun ExerciseDetailDto.toModel() = ExerciseDetail(
        id = exerciseId,
        name = exerciseName,
        categoryName = categoryName,
        sections = GuideSections.build(
            mapOf(
                "exercise_description" to exerciseDescription,
                "first_description" to firstDescription,
                "main_form" to mainForm,
                "form_description" to formDescription,
                "stay_form" to stayForm,
                "fixed_form" to fixedForm,
                "exercise_guide_text" to exerciseGuide
            ),
            audios.map { it.copy(url = resolve(it.url)) },
            audioStale = audioStale
        ),
        pictograms = pictograms.mapNotNull(::resolve),
        needsAssistant = GuideSections.needsAssistant(exerciseName),
        pictogramFrames = pictogramFrames.sortedBy { it.sequence }.mapNotNull { f ->
            resolve(f.url)?.let { PictogramFrame(it, f.sequence, f.section, f.sentenceIndex, f.label, resolve(f.displayUrl)) }
        },
        pictogramCycle = pictogramCycle?.let { PictogramCycle(it.section, it.sequences, it.startSentenceIndex) }
    )

    override suspend fun frequent(): ApiResult<List<ExerciseSummary>> = apiCall(json) { api.frequent() }.map { r ->
        r.exercises.map { ExerciseSummary(it.exerciseId, it.exerciseName, it.categoryName, resolve(it.pictogramUrl), it.count) }
    }

    override suspend fun playlists(): ApiResult<List<Routine>> = apiCall(json) {
        api.playlists()
    }.map { list -> list.map { it.toRoutine() } }

    override suspend fun playlist(playlistId: String): ApiResult<Routine> = apiCall(json) {
        api.playlist(playlistId)
    }.map { it.toRoutine() }

    override suspend fun createPlaylist(title: String, items: List<RoutineItem>): ApiResult<Routine> = apiCall(json) {
        api.createPlaylist(
            PlaylistCreateRequest(
                title = title,
                items = items.mapIndexed { i, item -> PlaylistItemInputDto(item.exerciseId, i + 1, item.setCount, item.repsCount) }
            )
        )
    }.map { it.toRoutine() }

    override suspend fun renamePlaylist(playlistId: String, title: String): ApiResult<Routine> =
        apiCall(json) { api.renamePlaylist(playlistId, PlaylistTitleRequest(title)) }.map { it.toRoutine() }

    override suspend fun deletePlaylist(playlistId: String): ApiResult<Unit> = apiCall(json) { api.deletePlaylist(playlistId) }

    override suspend fun addPlaylistItem(playlistId: String, exerciseId: String, setCount: Int, repsCount: Int?): ApiResult<Routine> =
        apiCall(json) { api.addPlaylistItem(playlistId, PlaylistItemAddRequest(exerciseId, setCount, repsCount)) }.map { it.toRoutine() }

    override suspend fun deletePlaylistItem(playlistId: String, itemId: String): ApiResult<Routine> =
        apiCall(json) { api.deletePlaylistItem(playlistId, itemId) }.map { it.toRoutine() }

    override suspend fun weekly(from: String?): ApiResult<ExerciseWeekDto> = apiCall(json) { api.weekly(from) }

    private fun PlaylistDto.toRoutine() = Routine(
        playlistId = playlistId,
        title = title,
        items = items.sortedBy { it.sequenceNo }.map {
            RoutineItem(it.playlistItemId, it.exerciseId, it.exerciseName, it.setCount, it.repsCount, it.restSec)
        }
    )

    /** 서버 미디어 주소: 절대 주소는 그대로, `/media/…` 상대 경로는 API 주소의 호스트 기준으로 바꾼다. */
    private fun resolve(url: String?): String? {
        if (url.isNullOrBlank()) return null
        return config.baseUrl.toHttpUrlOrNull()?.resolve(url)?.toString() ?: url
    }

    /**
     * 서버에 닿지 못했거나(네트워크) 로그인 전(401)이면 기기 내장 정보로 대신한다.
     * 서버가 준 다른 오류(404 등)는 그대로 돌려준다.
     */
    private inline fun <T> withFallback(remote: ApiResult<T>, device: () -> T): ApiResult<Catalog<T>> = when (remote) {
        is ApiResult.Success -> ApiResult.Success(Catalog(remote.data, fromDevice = false))

        is ApiResult.Failure -> if (remote.failure.isNetwork || remote.failure.httpStatus == HTTP_UNAUTHORIZED) {
            ApiResult.Success(Catalog(device(), fromDevice = true))
        } else {
            remote
        }
    }

    companion object {
        const val CATALOG_ASSET = "exercise_catalog.json"
        private const val HTTP_UNAUTHORIZED = 401
        private val NOT_FOUND = ApiFailure("NOT_FOUND", "운동 정보를 찾지 못했어요. 목록으로 돌아가 다시 골라 주세요.", 404)
    }
}

internal inline fun <T, R> ApiResult<T>.map(transform: (T) -> R): ApiResult<R> = when (this) {
    is ApiResult.Success -> ApiResult.Success(transform(data))
    is ApiResult.Failure -> this
}

// ── 즐겨찾기 ──────────────────────────────────────────────────────────

/** 운동 즐겨찾기(기기 저장). */
interface FavoriteExerciseRepository {
    fun observe(): Flow<List<ExerciseSummary>>

    suspend fun set(exercise: ExerciseSummary, favorite: Boolean)
}

@Singleton
class RoomFavoriteExerciseRepository @Inject constructor(private val dao: FavoriteExerciseDao, private val clock: Clock) :
    FavoriteExerciseRepository {
    override fun observe(): Flow<List<ExerciseSummary>> = dao.observeAll().map { list ->
        list.map { ExerciseSummary(it.exerciseId, it.exerciseName, it.categoryName) }
    }

    override suspend fun set(exercise: ExerciseSummary, favorite: Boolean) {
        if (favorite) {
            dao.add(FavoriteExerciseEntity(exercise.id, exercise.name, exercise.categoryName, clock.millis()))
        } else {
            dao.remove(exercise.id)
        }
    }
}

// ── 운동 기록(오프라인 큐) ───────────────────────────────────────────────

/** 세션 방식. 서버 mode 값과 같다. */
enum class ExerciseMode { MANUAL, CURRICULUM }

/** 세션 결과. 서버 status 값과 같다. */
enum class ExerciseEndStatus { COMPLETED, ABORTED }

/** 기록 항목(루틴이면 운동마다 한 개). */
data class ExerciseLogItem(
    val exerciseId: String,
    val sequenceNo: Int,
    val startedAt: OffsetDateTime?,
    val endedAt: OffsetDateTime?,
    val durationMs: Long?,
    val isSkipped: Boolean = false,
    val skipReason: String? = null,
    val restSec: Int? = null
)

/**
 * 운동 기록 큐(COM-15, EX-10). 시작할 때 DRAFT로 먼저 저장하고(앱이 죽어도 남게),
 * 끝나면 PENDING으로 확정해 WorkManager가 `POST /exercise-sessions/bulk/`로 보낸다(client_uuid 멱등).
 *
 * 1차 결함 대응: 시작/종료 두 번 부르던 1차 방식 대신 끝난 세션 하나를 한 번에 보낸다(B6: 루틴은 실제 playlist_id).
 */
interface ExerciseLogRepository {
    /** @return 새 client_uuid */
    suspend fun begin(exerciseName: String, playlistId: String?, mode: ExerciseMode, startedAt: OffsetDateTime): String

    suspend fun heartbeat(clientUuid: String, at: OffsetDateTime)

    /** DRAFT일 때만 반영(두 번 불러도 한 번). 확정했으면 true. */
    suspend fun finish(clientUuid: String, status: ExerciseEndStatus, endedAt: OffsetDateTime, items: List<ExerciseLogItem>): Boolean

    /** 앱이 죽어 끝나지 않은 세션(지금 진행 중인 것 제외)을 ABORTED·APP_KILLED로 확정한다. */
    suspend fun recoverInterrupted(): Int

    fun observePendingCount(): Flow<Int>

    suspend fun get(clientUuid: String): ExerciseLogEntity?

    suspend fun syncNow(): SyncOutcome
}

@Singleton
class OfflineFirstExerciseLogRepository @Inject constructor(
    private val dao: ExerciseLogDao,
    private val syncer: ExerciseSyncer,
    private val scheduler: ExerciseSyncScheduler,
    private val json: Json,
    private val clock: Clock
) : ExerciseLogRepository {

    private val active = mutableSetOf<String>()
    private val lock = Mutex()
    private val itemsSerializer = ListSerializer(ExerciseLogItemDto.serializer())

    override suspend fun begin(exerciseName: String, playlistId: String?, mode: ExerciseMode, startedAt: OffsetDateTime): String {
        val uuid = UUID.randomUUID().toString()
        lock.withLock { active += uuid }
        val at = startedAt.format(ISO)
        dao.insert(
            ExerciseLogEntity(
                clientUuid = uuid,
                exerciseName = exerciseName,
                playlistId = playlistId,
                mode = mode.name,
                startedAt = at,
                endedAt = null,
                status = "IN_PROGRESS",
                abnormalEndReason = null,
                itemsJson = "[]",
                state = ExerciseLogState.DRAFT,
                lastHeartbeatAt = at,
                createdAtMs = clock.millis()
            )
        )
        return uuid
    }

    override suspend fun heartbeat(clientUuid: String, at: OffsetDateTime) = dao.heartbeat(clientUuid, at.format(ISO))

    override suspend fun finish(
        clientUuid: String,
        status: ExerciseEndStatus,
        endedAt: OffsetDateTime,
        items: List<ExerciseLogItem>
    ): Boolean {
        val payload = json.encodeToString(itemsSerializer, items.map { it.toDto() })
        val reason = if (status == ExerciseEndStatus.COMPLETED) "NORMAL" else null
        val changed = withContext(Dispatchers.IO) { dao.finish(clientUuid, endedAt.format(ISO), status.name, reason, payload) }
        lock.withLock { active -= clientUuid }
        if (changed > 0) scheduler.scheduleExerciseSync()
        return changed > 0
    }

    override suspend fun recoverInterrupted(): Int {
        val running = lock.withLock { active.toSet() }
        var count = 0
        dao.drafts().filter { it.clientUuid !in running }.forEach { draft ->
            val endedAt = draft.lastHeartbeatAt ?: draft.startedAt
            if (dao.finish(draft.clientUuid, endedAt, ExerciseEndStatus.ABORTED.name, "APP_KILLED", draft.itemsJson) > 0) count++
        }
        if (count > 0) scheduler.scheduleExerciseSync()
        return count
    }

    override fun observePendingCount(): Flow<Int> = dao.observePendingCount()

    override suspend fun get(clientUuid: String): ExerciseLogEntity? = dao.get(clientUuid)

    override suspend fun syncNow(): SyncOutcome = syncer.syncPending()

    private fun ExerciseLogItem.toDto() = ExerciseLogItemDto(
        exerciseId = exerciseId,
        sequenceNo = sequenceNo,
        startedAt = startedAt?.format(ISO),
        endedAt = endedAt?.format(ISO),
        durationMs = durationMs,
        isSkipped = isSkipped,
        skipReason = skipReason,
        restSec = restSec
    )

    companion object {
        val ISO: DateTimeFormatter = DateTimeFormatter.ISO_OFFSET_DATE_TIME
    }
}
