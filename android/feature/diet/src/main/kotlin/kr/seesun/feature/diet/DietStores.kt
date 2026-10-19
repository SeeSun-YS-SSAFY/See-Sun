package kr.seesun.feature.diet

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kr.seesun.core.auth.security.SecretStore
import kr.seesun.core.data.repository.AccountScopedStore
import kr.seesun.core.network.model.FoodSummaryDto

/**
 * 끊겼을 때 경고에 쓸 마지막 건강 정보(계획 §3 N-05: 기기 안 알레르기 대조). 기기 Keystore 암호화 저장소에만 두고
 * 서버·백업으로 나가지 않는다. 로그아웃·탈퇴 때 지운다. 민감정보 동의가 없으면(서버 403) 비운다.
 * @param diabetes 당뇨 선택 — 「먹어도 돼?」(Q-07)에서 당류 지표를 읽을지만 정한다.
 */
@Serializable
data class DietHealthSnapshot(val allergens: List<String> = emptyList(), val diabetes: Boolean = false, val targetKcal: Int? = null)

@Singleton
class DietHealthStore @Inject constructor(private val secrets: SecretStore, private val json: Json) : AccountScopedStore {
    fun load(): DietHealthSnapshot? =
        secrets.get(KEY)?.let { runCatching { json.decodeFromString(DietHealthSnapshot.serializer(), it) }.getOrNull() }

    fun save(snapshot: DietHealthSnapshot) = secrets.put(KEY, json.encodeToString(DietHealthSnapshot.serializer(), snapshot))

    fun clear() = secrets.put(KEY, null)

    override fun clearOnSignOut() = clear()

    private companion object {
        const val KEY = "diet.health.v1"
    }
}

/**
 * 최근에 고른 음식(영양 DB 요약, 최대 [MAX]개). 끊겼을 때도 자주 먹는 음식은 이름 → 영양값으로 바로 찾게 한다(COM-15 오프라인 기록).
 * 음식 이름·영양값만 담고(건강 정보 없음), 계정별이라 로그아웃 때 지운다.
 */
@Singleton
class DietFoodCache @Inject constructor(private val secrets: SecretStore, private val json: Json) : AccountScopedStore {
    private val serializer = ListSerializer(FoodSummaryDto.serializer())

    fun all(): List<FoodSummaryDto> = secrets.get(KEY)?.let { runCatching { json.decodeFromString(serializer, it) }.getOrNull() }.orEmpty()

    /** 맨 앞에 넣는다(같은 음식은 하나만). */
    fun remember(foods: List<FoodSummaryDto>) {
        if (foods.isEmpty()) return
        val ids = foods.map { it.id }.toSet()
        val merged = (foods + all().filterNot { it.id in ids }).take(MAX)
        secrets.put(KEY, json.encodeToString(serializer, merged))
    }

    /** 이름으로 찾기(띄어쓰기 무시, 정확히 같은 이름 먼저). */
    fun search(query: String): List<FoodSummaryDto> {
        val q = query.replace(" ", "")
        if (q.isEmpty()) return emptyList()
        return all().filter { (it.displayName ?: it.name).replace(" ", "").contains(q) || it.name.replace(" ", "").contains(q) }
            .sortedBy { if (it.name.replace(" ", "") == q) 0 else 1 }
    }

    override fun clearOnSignOut() = secrets.put(KEY, null)

    private companion object {
        const val KEY = "diet.recent_foods.v1"
        const val MAX = 200
    }
}

/** 로그아웃·탈퇴의 기기 데이터 삭제에 식단 기기 저장값을 넣는다. */
@Module
@InstallIn(SingletonComponent::class)
abstract class DietStoreModule {
    @Binds
    @IntoSet
    abstract fun health(impl: DietHealthStore): AccountScopedStore

    @Binds
    @IntoSet
    abstract fun foods(impl: DietFoodCache): AccountScopedStore
}
