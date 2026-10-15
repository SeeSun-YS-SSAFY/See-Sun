package kr.seesun.core.data.di

import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.Multibinds
import javax.inject.Provider
import javax.inject.Singleton
import kr.seesun.core.data.fake.FakeAccountBackend
import kr.seesun.core.data.fake.FakeSessionRepository
import kr.seesun.core.data.repository.AccountScopedStore
import kr.seesun.core.data.repository.ConsentRepository
import kr.seesun.core.data.repository.DataResetRepository
import kr.seesun.core.data.repository.DefaultSessionRepository
import kr.seesun.core.data.repository.DeviceDataWiper
import kr.seesun.core.data.repository.HealthRepository
import kr.seesun.core.data.repository.LocalDataWiper
import kr.seesun.core.data.repository.NetworkConsentRepository
import kr.seesun.core.data.repository.NetworkDataResetRepository
import kr.seesun.core.data.repository.NetworkHealthRepository
import kr.seesun.core.data.repository.NetworkProfileRepository
import kr.seesun.core.data.repository.ProfileRepository
import kr.seesun.core.data.repository.QueueUnsentRecords
import kr.seesun.core.data.repository.SessionRepository
import kr.seesun.core.data.repository.UnsentRecords

/** 계정(동의·내 정보·건강·세션) 저장소. [DataConfig.useFakeRepositories]면 메모리 가짜 백엔드를 쓴다. */
@Module
@InstallIn(SingletonComponent::class)
object AccountDataModule {
    @Provides
    @Singleton
    fun consentRepository(
        config: DataConfig,
        real: Provider<NetworkConsentRepository>,
        fake: Provider<FakeAccountBackend>
    ): ConsentRepository = if (config.useFakeRepositories) fake.get() else real.get()

    @Provides
    @Singleton
    fun profileRepository(
        config: DataConfig,
        real: Provider<NetworkProfileRepository>,
        fake: Provider<FakeAccountBackend>
    ): ProfileRepository = if (config.useFakeRepositories) fake.get() else real.get()

    @Provides
    @Singleton
    fun healthRepository(
        config: DataConfig,
        real: Provider<NetworkHealthRepository>,
        fake: Provider<FakeAccountBackend>
    ): HealthRepository = if (config.useFakeRepositories) fake.get() else real.get()

    @Provides
    @Singleton
    fun sessionRepository(
        config: DataConfig,
        real: Provider<DefaultSessionRepository>,
        fake: Provider<FakeSessionRepository>
    ): SessionRepository = if (config.useFakeRepositories) fake.get() else real.get()

    @Provides
    @Singleton
    fun dataResetRepository(
        config: DataConfig,
        real: Provider<NetworkDataResetRepository>,
        fake: Provider<FakeAccountBackend>
    ): DataResetRepository = if (config.useFakeRepositories) fake.get() else real.get()
}

@Module
@InstallIn(SingletonComponent::class)
internal abstract class WiperModule {
    @Binds
    abstract fun localDataWiper(impl: DeviceDataWiper): LocalDataWiper

    @Binds
    abstract fun unsentRecords(impl: QueueUnsentRecords): UnsentRecords

    /** 등록한 기능이 없어도 빈 집합으로 주입되게 한다. */
    @Multibinds
    abstract fun accountScopedStores(): Set<AccountScopedStore>
}
