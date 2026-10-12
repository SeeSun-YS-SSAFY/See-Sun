package kr.seesun.core.data.di

import android.content.Context
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import java.time.Clock
import javax.inject.Provider
import javax.inject.Singleton
import kr.seesun.core.auth.security.SecretStore
import kr.seesun.core.data.db.MealDao
import kr.seesun.core.data.db.SeeSunDatabase
import kr.seesun.core.data.fake.FakeAuthRepository
import kr.seesun.core.data.fake.FakeFoodRepository
import kr.seesun.core.data.fake.FakeMealRepository
import kr.seesun.core.data.repository.AuthRepository
import kr.seesun.core.data.repository.DefaultAuthRepository
import kr.seesun.core.data.repository.FoodRepository
import kr.seesun.core.data.repository.MealRepository
import kr.seesun.core.data.repository.NetworkFoodRepository
import kr.seesun.core.data.repository.OfflineFirstMealRepository
import kr.seesun.core.data.sync.SyncScheduler
import kr.seesun.core.data.sync.WorkManagerSyncScheduler

/**
 * 데이터 계층 설정. app 모듈이 제공한다.
 * @param useFakeRepositories true면 서버 없이 메모리 가짜 저장소를 쓴다(gradle 속성 `seesun.fakeData`).
 */
data class DataConfig(val useFakeRepositories: Boolean)

@Module
@InstallIn(SingletonComponent::class)
object DataModule {
    @Provides
    @Singleton
    fun database(@ApplicationContext context: Context, secrets: SecretStore): SeeSunDatabase = SeeSunDatabase.create(context, secrets)

    @Provides
    fun mealDao(db: SeeSunDatabase): MealDao = db.mealDao()

    @Provides
    @Singleton
    fun clock(): Clock = Clock.systemDefaultZone()

    @Provides
    @Singleton
    fun mealRepository(config: DataConfig, real: Provider<OfflineFirstMealRepository>, fake: Provider<FakeMealRepository>): MealRepository =
        if (config.useFakeRepositories) fake.get() else real.get()

    @Provides
    @Singleton
    fun foodRepository(config: DataConfig, real: Provider<NetworkFoodRepository>, fake: Provider<FakeFoodRepository>): FoodRepository =
        if (config.useFakeRepositories) fake.get() else real.get()

    @Provides
    @Singleton
    fun authRepository(config: DataConfig, real: Provider<DefaultAuthRepository>, fake: Provider<FakeAuthRepository>): AuthRepository =
        if (config.useFakeRepositories) fake.get() else real.get()
}

@Module
@InstallIn(SingletonComponent::class)
internal abstract class SyncModule {
    @Binds
    abstract fun syncScheduler(impl: WorkManagerSyncScheduler): SyncScheduler
}
