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
import kr.seesun.core.voice.lifecycle.AppAudioGuard
import kr.seesun.feature.update.UpdateCheckScheduler
import kr.seesun.feature.update.UpdateConfig
import kr.seesun.feature.update.UpdatePushSubscriber

@HiltAndroidApp
class SeeSunApplication :
    Application(),
    Configuration.Provider {

    @Inject lateinit var workerFactory: HiltWorkerFactory

    @Inject lateinit var syncScheduler: SyncScheduler

    @Inject lateinit var audioGuard: AppAudioGuard

    @Inject lateinit var updateCheckScheduler: UpdateCheckScheduler

    @Inject lateinit var updatePushSubscriber: UpdatePushSubscriber

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder().setWorkerFactory(workerFactory).build()

    override fun onCreate() {
        super.onCreate()
        // 지난번에 보내지 못한 식사 기록이 있으면 네트워크가 연결될 때 보낸다.
        syncScheduler.scheduleMealSync()
        // 홈·다른 앱으로 나가면 어느 화면이든 안내 음성·듣기를 바로 멈춘다(v4 실기기 버그 msg 943). 첫 화면보다 먼저 시작해야 센다.
        audioGuard.start()
        // 가운영 빌드만: 6시간마다·앱 시작 때 새 버전 확인 → 알림. 끈 빌드는 아무것도 하지 않는 구현이 묶인다.
        if (BuildConfig.SELF_UPDATE) updateCheckScheduler.schedule()
        // 가운영 빌드만: 새 버전 게시 즉시 알림(FCM 토픽). 처음 실행·앱 업데이트 뒤 첫 실행 때 구독하고, 실패하면 다시 한다.
        if (BuildConfig.SELF_UPDATE) updatePushSubscriber.ensureSubscribed()
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
        installedVersionName = BuildConfig.VERSION_NAME,
        pushTopic = BuildConfig.SELF_UPDATE_PUSH_TOPIC
    )
}
