package kr.seesun.feature.update

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 자체 업데이트를 끈 빌드(Play·일반 release, `-Pseesun.selfUpdate` 없음). 백그라운드 확인·업데이트 알림이 없다.
 * 실제 구현은 src/selfUpdateOn/kotlin(가운영 빌드에서만 컴파일).
 */
@Singleton
class NoUpdateCheckScheduler @Inject constructor() : UpdateCheckScheduler {
    override fun schedule() = Unit
}

/** 자체 업데이트를 끈 빌드는 업데이트 푸시 토픽을 구독하지 않는다(Play 빌드에 FCM 업데이트 코드가 들어가지 않는다). */
@Singleton
class NoUpdatePushSubscriber @Inject constructor() : UpdatePushSubscriber {
    override fun ensureSubscribed() = Unit
}

@Module
@InstallIn(SingletonComponent::class)
internal abstract class UpdateCheckBindings {
    @Binds
    abstract fun scheduler(impl: NoUpdateCheckScheduler): UpdateCheckScheduler

    @Binds
    abstract fun pushSubscriber(impl: NoUpdatePushSubscriber): UpdatePushSubscriber
}
