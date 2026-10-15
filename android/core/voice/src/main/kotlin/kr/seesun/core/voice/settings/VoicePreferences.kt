package kr.seesun.core.voice.settings

import android.content.Context
import androidx.core.content.edit
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** 기기에만 두는 음성 설정(서버 설정과 별개). */
interface VoicePreferences {
    /** 「음성 자동 인식」: 안내가 끝나면 마이크를 저절로 켠다. 기본 켜짐(소유자 결정 2026-10-15). */
    val autoListen: StateFlow<Boolean>

    fun setAutoListen(enabled: Boolean)
}

@Singleton
class SharedVoicePreferences @Inject constructor(@ApplicationContext context: Context) : VoicePreferences {
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val _autoListen = MutableStateFlow(prefs.getBoolean(KEY_AUTO_LISTEN, DEFAULT_AUTO_LISTEN))
    override val autoListen: StateFlow<Boolean> = _autoListen.asStateFlow()

    override fun setAutoListen(enabled: Boolean) {
        prefs.edit { putBoolean(KEY_AUTO_LISTEN, enabled) }
        _autoListen.value = enabled
    }

    companion object {
        const val DEFAULT_AUTO_LISTEN = true
        private const val PREFS = "seesun_voice"
        private const val KEY_AUTO_LISTEN = "auto_listen"
    }
}

/** 시험용(메모리에만). */
class InMemoryVoicePreferences(initial: Boolean = SharedVoicePreferences.DEFAULT_AUTO_LISTEN) : VoicePreferences {
    private val _autoListen = MutableStateFlow(initial)
    override val autoListen: StateFlow<Boolean> = _autoListen.asStateFlow()

    override fun setAutoListen(enabled: Boolean) {
        _autoListen.value = enabled
    }
}
