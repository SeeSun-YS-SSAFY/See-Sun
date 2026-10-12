package kr.seesun.core.auth

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/** release: 가짜 인증은 포함되지 않는다. Firebase가 꺼져 있으면 스텁이 "미설정" 오류를 돌려준다. */
@Module
@InstallIn(SingletonComponent::class)
internal abstract class AuthBindingModule {
    @Binds
    abstract fun authProvider(impl: FirebasePhoneAuthProvider): AuthProvider
}
