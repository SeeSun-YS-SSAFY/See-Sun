package kr.seesun.core.feedback

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

@Module
@InstallIn(SingletonComponent::class)
internal abstract class FeedbackModule {
    @Binds
    abstract fun bindHaptics(impl: AndroidHaptics): Haptics
}
