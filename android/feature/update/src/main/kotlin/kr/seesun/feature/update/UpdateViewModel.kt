package kr.seesun.feature.update

import android.content.Context
import androidx.core.content.edit
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kr.seesun.core.voice.tts.Speaker

/**
 * 자체 업데이트 설정. app이 빌드 속성(-Pseesun.selfUpdate)으로 만든다.
 * @property enabled false면 매니페스트를 확인하지 않고 아무것도 띄우지 않는다(Play·일반 빌드)
 */
data class UpdateConfig(
    val enabled: Boolean,
    val manifestUrl: String,
    val installedVersionCode: Long,
    val installedVersionName: String,
    /** FCM 업데이트 푸시 토픽(시험 빌드는 `seesun-app-updates-test`로 바꾼다 — 실제 토픽에 시험 푸시를 보내지 않게). */
    val pushTopic: String = UpdatePushPolicy.TOPIC
)

/** 업데이트 안내 창의 단계. */
enum class UpdateStep {
    /** 새 버전 안내: [업데이트] [나중에] */
    AVAILABLE,

    /** 내려받는 중 */
    DOWNLOADING,

    /** 「출처를 알 수 없는 앱 설치」 허용이 필요하다: [설정 열기] */
    NEEDS_PERMISSION,

    /** 검증을 마쳤다. 시스템 설치 화면을 연다(돌아오면 [설치 화면 열기]) */
    READY,

    /** 내려받기·검증·설치 화면 열기 실패: [다시 시도] */
    FAILED
}

/** @property manifest null이면 안내 창을 띄우지 않는다 */
data class UpdateUiState(
    val manifest: UpdateManifest? = null,
    val required: Boolean = false,
    val step: UpdateStep = UpdateStep.AVAILABLE,
    val progress: Int = 0,
    val apk: File? = null,
    val installerOpened: Boolean = false
)

@HiltViewModel
class UpdateViewModel @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val config: UpdateConfig,
    private val speaker: Speaker
) : ViewModel() {

    private val client = UpdateClient()
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val _state = MutableStateFlow(UpdateUiState())
    val state: StateFlow<UpdateUiState> = _state.asStateFlow()
    val enabled: Boolean get() = config.enabled
    val installedVersionName: String get() = config.installedVersionName
    private var checking: Job? = null
    private var downloading: Job? = null

    /** 화면이 다시 보일 때마다. 처음 열 때는 바로, 그 뒤로는 6시간에 한 번 확인한다. 설정에서 돌아오면 설치를 이어 간다. */
    fun onResume() = check(force = false)

    /** 업데이트 알림을 눌러 앱을 열었다: 간격과 관계없이 바로 확인해 안내 창을 띄운다([나중에]로 닫았어도). */
    fun onOpenRequested() = check(force = true)

    private fun check(force: Boolean) {
        if (!config.enabled) return
        val current = _state.value
        if (current.manifest != null) {
            if (current.step == UpdateStep.NEEDS_PERMISSION && ApkInstaller.canInstall(context)) {
                _state.update { it.copy(step = UpdateStep.READY, installerOpened = false) }
            }
            return
        }
        val now = System.currentTimeMillis()
        val last = prefs.getLong(KEY_LAST_CHECK, 0L).takeIf { it > 0L }
        if (checking?.isActive == true) return
        if (!force && !UpdateRules.shouldCheck(now, last, coldStart = !checkedInProcess)) return
        checking = viewModelScope.launch {
            val manifest = client.fetchManifest(config.manifestUrl) ?: return@launch
            checkedInProcess = true
            prefs.edit { putLong(KEY_LAST_CHECK, System.currentTimeMillis()) }
            val need = UpdateRules.need(config.installedVersionCode, manifest)
            if (need == UpdateNeed.NONE) return@launch
            _state.value = UpdateUiState(manifest = manifest, required = need == UpdateNeed.REQUIRED)
            // 앱 안에서 안내했으면 같은 버전 알림은 하루 동안 내지 않는다(UpdateNotifyPolicy).
            prefs.edit {
                putLong(UpdateNotifyPolicy.KEY_NOTIFIED_CODE, manifest.versionCode)
                putLong(UpdateNotifyPolicy.KEY_NOTIFIED_AT, System.currentTimeMillis())
            }
            speaker.speak(context.getString(R.string.update_available_voice, manifest.versionName))
        }
    }

    /** [업데이트]·[다시 시도]: 내려받고 검증한 뒤 설치 화면으로. */
    fun onUpdate() {
        val manifest = _state.value.manifest ?: return
        if (downloading?.isActive == true) return
        speaker.stop()
        _state.update { it.copy(step = UpdateStep.DOWNLOADING, progress = 0, apk = null, installerOpened = false) }
        downloading = viewModelScope.launch {
            val result = client.download(manifest, ApkInstaller.downloadDir(context)) { percent ->
                _state.update { it.copy(progress = percent) }
            }
            when (result) {
                is DownloadResult.Done -> {
                    val next = if (ApkInstaller.canInstall(context)) UpdateStep.READY else UpdateStep.NEEDS_PERMISSION
                    _state.update { it.copy(step = next, apk = result.file, progress = PERCENT) }
                    if (next == UpdateStep.NEEDS_PERMISSION) speaker.speak(context.getString(R.string.update_permission_voice))
                }

                is DownloadResult.Failed -> fail()
            }
        }
    }

    /** 설치 화면을 열었다. 사용자가 설치하지 않고 돌아오면 [설치 화면 열기]를 다시 보여 준다. */
    fun onInstallerOpened() = _state.update { it.copy(installerOpened = true) }

    /** 설치 화면·설정 화면을 열 수 없었다. */
    fun onOpenFailed() = fail()

    /** [나중에]. 꼭 해야 하는 업데이트에서는 닫지 않는다. */
    fun onLater() {
        if (_state.value.required) return
        speaker.stop()
        downloading?.cancel()
        _state.value = UpdateUiState()
    }

    private fun fail() {
        _state.update { it.copy(step = UpdateStep.FAILED, installerOpened = false) }
        speaker.speak(context.getString(R.string.update_failed_voice))
    }

    private companion object {
        const val PREFS = UpdateNotifyPolicy.PREFS
        const val KEY_LAST_CHECK = "last_check_millis"
        const val PERCENT = 100

        /** 이 프로세스에서 매니페스트를 한 번이라도 확인했는가(앱을 새로 열면 6시간과 관계없이 확인). */
        @Volatile var checkedInProcess = false
    }
}
