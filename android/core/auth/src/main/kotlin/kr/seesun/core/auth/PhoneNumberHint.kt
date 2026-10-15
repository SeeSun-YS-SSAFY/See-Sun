package kr.seesun.core.auth

import android.content.Context
import android.content.Intent
import android.content.IntentSender
import com.google.android.gms.auth.api.identity.GetPhoneNumberHintIntentRequest
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability
import com.google.android.gms.tasks.Task
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * 이 휴대폰의 번호 가져오기(소유자 피드백 2026-10-13: "휴대폰 번호는 바로 받아올 수 있지 않나").
 *
 * Google Play 서비스의 Phone Number Hint API를 쓴다. 앱 권한(READ_PHONE_NUMBERS 등)이 필요 없고,
 * 사용자가 시스템 창에서 번호를 고를 때만 앱에 번호가 넘어온다. Play 서비스가 없거나 SIM 번호를 모르면
 * [request]가 null을 돌려주고, 화면은 기존 말·키보드 입력으로 돌아간다.
 */
interface PhoneNumberHintSource {
    /** 번호 고르기 창을 띄울 IntentSender. Play 서비스 없음·힌트 없음·오류면 null. */
    suspend fun request(): IntentSender?

    /** 창 결과에서 번호(보통 E.164 "+821012345678")를 꺼낸다. 취소·실패면 null. */
    fun parse(data: Intent?): String?
}

@Singleton
class PlayPhoneNumberHintSource @Inject constructor(@ApplicationContext private val context: Context) : PhoneNumberHintSource {

    override suspend fun request(): IntentSender? {
        val available = GoogleApiAvailability.getInstance().isGooglePlayServicesAvailable(context) == ConnectionResult.SUCCESS
        if (!available) return null
        return runCatching {
            Identity.getSignInClient(context)
                .getPhoneNumberHintIntent(GetPhoneNumberHintIntentRequest.builder().build())
                .awaitOrNull()
                ?.intentSender
        }.getOrNull()
    }

    override fun parse(data: Intent?): String? {
        if (data == null) return null
        // 취소·힌트 없음이면 ApiException을 던진다 → null(화면이 직접 입력으로 안내).
        return runCatching { Identity.getSignInClient(context).getPhoneNumberFromIntent(data) }.getOrNull()
    }

    private suspend fun <T> Task<T>.awaitOrNull(): T? = suspendCancellableCoroutine { cont ->
        addOnCompleteListener { task -> cont.resume(if (task.isSuccessful) task.result else null) }
    }
}

@Module
@InstallIn(SingletonComponent::class)
abstract class PhoneNumberHintModule {
    @Binds
    abstract fun hintSource(impl: PlayPhoneNumberHintSource): PhoneNumberHintSource
}

/** 번호 힌트를 쓰지 않는 구현(시험·Play 서비스 없는 환경). 화면은 바로 직접 입력으로 간다. */
object NoPhoneNumberHint : PhoneNumberHintSource {
    override suspend fun request(): IntentSender? = null

    override fun parse(data: Intent?): String? = null
}
