package kr.seesun.feature.mypage

import android.content.pm.PackageManager
import android.os.Build
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.core.content.ContextCompat
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.launch
import kr.seesun.core.a11y.rememberTalkBackEnabled
import kr.seesun.core.designsystem.component.BodyText
import kr.seesun.core.designsystem.component.ButtonKind
import kr.seesun.core.designsystem.component.ListButton
import kr.seesun.core.designsystem.component.LiveStatusText
import kr.seesun.core.designsystem.component.ScreenScaffold
import kr.seesun.core.designsystem.component.SeeSunButton
import kr.seesun.core.ui.AppPermissions
import kr.seesun.core.ui.AutoGuidance
import kr.seesun.core.ui.GuidanceVoice
import kr.seesun.core.ui.openAppSettings
import kr.seesun.core.voice.tts.Speaker

/** 업데이트 확인 결과(앱 버전 화면). */
enum class UpdateCheckOutcome { LATEST, AVAILABLE, FAILED }

/**
 * 앱 버전 정보와 업데이트 확인 창구. app이 빌드 설정으로 만든다(feature 모듈끼리 의존하지 않게 함수로 받는다).
 * @property checkUpdate null이면 앱 안 업데이트 확인이 없다(Play 빌드: 스토어가 업데이트한다)
 */
data class AppInfo(val versionName: String = "", val versionCode: Long = 0, val checkUpdate: (suspend () -> UpdateCheckOutcome)? = null)

/** 권한 상태 한 줄(시험용 순수 함수): 이 기기에서 보여 줄 권한과 켜짐 여부. */
object PermissionStatus {
    fun rows(sdkInt: Int, granted: (String) -> Boolean): List<Pair<String, Boolean>> =
        AppPermissions.required(sdkInt).map { it to granted(it) }
}

/**
 * 권한 상태(로그인 전에도 볼 수 있음, 소유자 msg 959). 마이크·알림(API 33+)이 켜졌는지 글자로 보여 주고,
 * 꺼진 것은 [허용하기]로 앱 설정을 연다(시스템 권한 창은 다시 띄우지 않는다, msg 946). 설정에서 돌아오면 다시 확인한다.
 */
@Composable
fun PermissionSettingsScreen(onBack: () -> Unit, voice: GuidanceVoice?) {
    val context = LocalContext.current
    val talkBack = rememberTalkBackEnabled()
    var refresh by remember { mutableIntStateOf(0) }
    LifecycleResumeEffect(Unit) {
        refresh++
        onPauseOrDispose { }
    }
    val rows = remember(refresh) {
        PermissionStatus.rows(Build.VERSION.SDK_INT) { ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED }
    }
    var failed by remember { mutableStateOf(false) }
    val on = stringResource(R.string.my_perm_on)
    val off = stringResource(R.string.my_perm_off)
    val lines = rows.map { (permission, granted) -> "${permissionName(permission)}: ${if (granted) on else off}" }
    ScreenScaffold(title = stringResource(R.string.my_perm_title), onBack = onBack) {
        BodyText(stringResource(R.string.my_perm_body))
        rows.forEach { (permission, granted) ->
            ListButton(
                text = permissionName(permission),
                description = if (granted) on else off + ". " + stringResource(R.string.my_perm_tap_to_allow),
                onClick = { if (!granted) failed = !openAppSettings(context) },
                descriptionVisible = true
            )
        }
        if (rows.any { !it.second }) {
            SeeSunButton(
                text = stringResource(R.string.my_perm_allow),
                onClick = { failed = !openAppSettings(context) },
                kind = ButtonKind.PRIMARY
            )
        }
        LiveStatusText(if (failed) stringResource(R.string.my_perm_settings_failed) else "")
        BodyText(stringResource(R.string.my_perm_optional))
    }
    if (voice != null) {
        val guide = (listOf(stringResource(R.string.my_perm_title)) + lines).joinToString(". ")
        AutoGuidance("permissions:${lines.joinToString()}", guide, talkBack) { voice.auto(it, talkBack) }
    }
}

@Composable
private fun permissionName(permission: String): String = stringResource(
    if (permission == AppPermissions.RECORD_AUDIO) R.string.my_perm_mic else R.string.my_perm_notifications
)

/** 앱 버전 / 업데이트 확인(로그인 전에도). 가운영 빌드는 [업데이트 확인]으로 서버를 바로 확인하고, 새 버전이면 안내 창이 뜬다. */
@Composable
fun AppVersionScreen(onBack: () -> Unit, info: AppInfo, voice: GuidanceVoice?) {
    val talkBack = rememberTalkBackEnabled()
    val scope = rememberCoroutineScope()
    var checking by remember { mutableStateOf(false) }
    var outcome by remember { mutableStateOf<UpdateCheckOutcome?>(null) }
    val version = stringResource(R.string.my_version_current, info.versionName, info.versionCode)
    ScreenScaffold(title = stringResource(R.string.my_version_title), onBack = onBack) {
        BodyText(version, emphasis = true)
        val check = info.checkUpdate
        if (check != null) {
            SeeSunButton(
                text = stringResource(if (checking) R.string.my_version_checking else R.string.my_version_check),
                enabled = !checking,
                onClick = {
                    checking = true
                    scope.launch {
                        outcome = check()
                        checking = false
                    }
                }
            )
        } else {
            BodyText(stringResource(R.string.my_version_store))
        }
        val result = when (outcome) {
            UpdateCheckOutcome.LATEST -> stringResource(R.string.my_version_latest)
            UpdateCheckOutcome.AVAILABLE -> stringResource(R.string.my_version_available)
            UpdateCheckOutcome.FAILED -> stringResource(R.string.my_version_failed)
            null -> ""
        }
        LiveStatusText(result)
        if (voice != null && result.isNotEmpty()) AutoGuidance("version:$outcome", result, talkBack) { voice.auto(it, talkBack) }
    }
    if (voice != null) AutoGuidance("version", version, talkBack) { voice.auto(it, talkBack) }
}

/** 권한 상태·앱 버전 화면의 안내 음성(TalkBack이 켜져 있으면 말하지 않음). */
@HiltViewModel
class AppSettingsViewModel @Inject constructor(speaker: Speaker) : ViewModel() {
    val voice = GuidanceVoice(speaker)
}

@Composable
fun PermissionSettingsRoute(onBack: () -> Unit, viewModel: AppSettingsViewModel = hiltViewModel()) =
    PermissionSettingsScreen(onBack = onBack, voice = viewModel.voice)

@Composable
fun AppVersionRoute(onBack: () -> Unit, info: AppInfo, viewModel: AppSettingsViewModel = hiltViewModel()) =
    AppVersionScreen(onBack = onBack, info = info, voice = viewModel.voice)
