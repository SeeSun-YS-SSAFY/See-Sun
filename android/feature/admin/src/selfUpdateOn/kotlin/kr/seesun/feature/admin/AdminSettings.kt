package kr.seesun.feature.admin

import android.content.SharedPreferences
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kr.seesun.core.voice.recognition.OnsetTuning
import kr.seesun.core.voice.recognition.OnsetTuningSource
import kr.seesun.core.voice.recognition.VadMode

/** 기기에 두는 작은 키-값 저장소(시험에서는 메모리로 바꾼다). */
interface KeyValueStore {
    fun getInt(key: String): Int?

    fun getString(key: String): String?

    fun getBoolean(key: String, default: Boolean): Boolean

    fun put(values: Map<String, Any?>)
}

class SharedPrefsKeyValue(private val prefs: SharedPreferences) : KeyValueStore {
    override fun getInt(key: String): Int? = if (prefs.contains(key)) prefs.getInt(key, 0) else null

    override fun getString(key: String): String? = prefs.getString(key, null)

    override fun getBoolean(key: String, default: Boolean): Boolean = prefs.getBoolean(key, default)

    override fun put(values: Map<String, Any?>) = prefs.edit {
        values.forEach { (k, v) ->
            when (v) {
                null -> remove(k)
                is Int -> putInt(k, v)
                is Boolean -> putBoolean(k, v)
                else -> putString(k, v.toString())
            }
        }
    }
}

/** 음성 감지 시험 기록: 말 시작 감지 수, 인식까지 시험한 수, 그중 쓸 수 있는 결과가 없던 헛감지 수. */
data class LabCounters(val onsets: Int = 0, val recognitionTests: Int = 0, val falseWakes: Int = 0)

/**
 * 관리자 모드 저장값(이 기기에만, 서버로 보내지 않음).
 * - 관리자 모드 켜짐 여부
 * - 음성 감지 시험에서 맞춘 말 시작 판정 값([savedTuning], 기본값과 같으면 지운다)
 * - 시험 기록([counters])
 *
 * 실제 듣기에 쓰는 값([tuning], [OnsetTuningSource])은 관리자 모드가 켜져 있고 맞춘 값이 있을 때만이다.
 * 관리자 모드를 끄면 코드 기본값으로 돌아가고, 맞춘 값은 남아 있다가 다시 켜면 이어 쓴다.
 */
class AdminSettings(private val store: KeyValueStore) : OnsetTuningSource {
    private val _enabled = MutableStateFlow(store.getBoolean(KEY_ENABLED, false))
    val enabled: StateFlow<Boolean> = _enabled.asStateFlow()

    private val _savedTuning = MutableStateFlow(readTuning())
    val savedTuning: StateFlow<OnsetTuning?> = _savedTuning.asStateFlow()

    private val _tuning = MutableStateFlow(applied())
    override val tuning: StateFlow<OnsetTuning?> = _tuning.asStateFlow()

    private val _counters = MutableStateFlow(
        LabCounters(store.getInt(KEY_ONSETS) ?: 0, store.getInt(KEY_TESTS) ?: 0, store.getInt(KEY_FALSE) ?: 0)
    )
    val counters: StateFlow<LabCounters> = _counters.asStateFlow()

    /** 시험 화면이 보여 주고 바꾸는 값(맞춘 값, 없으면 기본값). */
    val labTuning: OnsetTuning get() = _savedTuning.value ?: OnsetTuning.DEFAULT

    fun setEnabled(on: Boolean) {
        store.put(mapOf(KEY_ENABLED to on))
        _enabled.value = on
        _tuning.value = applied()
    }

    /** 값을 저장한다(범위로 맞춤). 기본값과 같으면 맞춘 값을 지운다(「조정값 사용 중」이 사라진다). */
    fun setTuning(value: OnsetTuning) {
        val t = value.clamped()
        if (t == OnsetTuning.DEFAULT) {
            store.put(mapOf(KEY_MARGIN to null, KEY_ONSET to null, KEY_MODE to null, KEY_CALIB to null))
            _savedTuning.value = null
        } else {
            store.put(mapOf(KEY_MARGIN to t.marginDb, KEY_ONSET to t.onsetMs, KEY_MODE to t.mode.name, KEY_CALIB to t.calibrationMs))
            _savedTuning.value = t
        }
        _tuning.value = applied()
    }

    fun resetTuning() = setTuning(OnsetTuning.DEFAULT)

    fun recordOnset() = updateCounters { it.copy(onsets = it.onsets + 1) }

    /** 인식까지 시험 한 번. [usable]이 false면 헛감지로도 센다. */
    fun recordRecognitionTest(usable: Boolean) =
        updateCounters { it.copy(recognitionTests = it.recognitionTests + 1, falseWakes = it.falseWakes + if (usable) 0 else 1) }

    fun clearCounters() = updateCounters { LabCounters() }

    private fun updateCounters(change: (LabCounters) -> LabCounters) {
        val c = change(_counters.value)
        store.put(mapOf(KEY_ONSETS to c.onsets, KEY_TESTS to c.recognitionTests, KEY_FALSE to c.falseWakes))
        _counters.value = c
    }

    private fun applied(): OnsetTuning? = if (_enabled.value) _savedTuning.value else null

    private fun readTuning(): OnsetTuning? {
        val margin = store.getInt(KEY_MARGIN) ?: return null
        val d = OnsetTuning.DEFAULT
        val mode = VadMode.entries.firstOrNull { it.name == store.getString(KEY_MODE) } ?: d.mode
        return OnsetTuning(margin, store.getInt(KEY_ONSET) ?: d.onsetMs, mode, store.getInt(KEY_CALIB) ?: d.calibrationMs)
            .clamped()
            .takeIf { it != d }
    }

    companion object {
        /** 기기 저장 파일 이름(Play 빌드에 이 이름이 없어야 한다 — app의 checkReleaseAdminMode가 확인). */
        const val PREFS = "seesun_admin"
        private const val KEY_ENABLED = "enabled"
        private const val KEY_MARGIN = "vad_margin_db"
        private const val KEY_ONSET = "vad_onset_ms"
        private const val KEY_MODE = "vad_mode"
        private const val KEY_CALIB = "vad_calibration_ms"
        private const val KEY_ONSETS = "lab_onsets"
        private const val KEY_TESTS = "lab_recognition_tests"
        private const val KEY_FALSE = "lab_false_wakes"
    }
}
