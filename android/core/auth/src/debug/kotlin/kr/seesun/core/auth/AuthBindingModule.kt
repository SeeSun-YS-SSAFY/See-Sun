package kr.seesun.core.auth

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Provider
import javax.inject.Singleton

/** debug: Firebase를 켜지 않았으면 가짜 인증을 쓴다. */
@Module
@InstallIn(SingletonComponent::class)
internal object AuthBindingModule {
    @Provides
    @Singleton
    fun authProvider(fake: Provider<FakeAuthProvider>, firebase: Provider<FirebasePhoneAuthProvider>): AuthProvider =
        if (BuildConfig.FIREBASE_ENABLED) firebase.get() else fake.get()
}
