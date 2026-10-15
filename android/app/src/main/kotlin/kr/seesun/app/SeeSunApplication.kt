package kr.seesun.app

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.HiltAndroidApp
import dagger.hilt.components.SingletonComponent
import javax.inject.Inject
import javax.inject.Singleton
import kr.seesun.core.data.di.DataConfig
import kr.seesun.core.data.sync.SyncScheduler
import kr.seesun.core.network.NetworkConfig
import kr.seesun.feature.update.UpdateConfig

@HiltAndroidApp
class SeeSunApplication :
    Application(),
    Configuration.Provider {

    @Inject lateinit var workerFactory: HiltWorkerFactory

    @Inject lateinit var syncScheduler: SyncScheduler

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder().setWorkerFactory(workerFactory).build()

    override fun onCreate() {
        super.onCreate()
        // 지난번에 보내지 못한 식사 기록이 있으면 네트워크가 연결될 때 보낸다.
        syncScheduler.scheduleMealSync()
    }
}

/** 빌드 설정(gradle 속성) → 코어 모듈 설정. */
@Module
@InstallIn(SingletonComponent::class)
object AppConfigModule {
    @Provides
    @Singleton
    fun networkConfig(): NetworkConfig = NetworkConfig(baseUrl = BuildConfig.API_BASE_URL, logRequests = BuildConfig.DEBUG)

    @Provides
    @Singleton
    fun dataConfig(): DataConfig = DataConfig(
        useFakeRepositories = BuildConfig.USE_FAKE_DATA,
        // 동의 기록 입증 정보(법적 검토 C-07). 빠지면 서버 동의 기록의 앱 버전이 비어 남는다(통합 E2E BUG-05).
        appVersion = BuildConfig.VERSION_NAME
    )

    /** 가운영 자체 업데이트(-Pseesun.selfUpdate). 꺼진 빌드에서는 매니페스트를 확인하지 않는다. */
    @Provides
    @Singleton
    fun updateConfig(): UpdateConfig = UpdateConfig(
        enabled = BuildConfig.SELF_UPDATE,
        manifestUrl = BuildConfig.SELF_UPDATE_MANIFEST_URL,
        installedVersionCode = BuildConfig.VERSION_CODE.toLong(),
        installedVersionName = BuildConfig.VERSION_NAME
    )
}
