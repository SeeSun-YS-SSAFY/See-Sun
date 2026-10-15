package kr.seesun.core.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.DrawableRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.core.net.toUri
import kr.seesun.core.a11y.rememberTalkBackEnabled
import kr.seesun.core.designsystem.R as DsR
import kr.seesun.core.designsystem.component.BottomActions
import kr.seesun.core.designsystem.component.ScreenScaffold
import kr.seesun.core.designsystem.component.SeeSunButton
import kr.seesun.core.designsystem.theme.SeeSunDimens
import kr.seesun.core.designsystem.theme.SeeSunSpace
import kr.seesun.core.designsystem.theme.SeeSunTheme
import kr.seesun.core.voice.tts.Speaker

/**
 * 앱 권한을 한 번에 묻는 규칙(소유자 결정 msg 946). 순수 함수라 기기 없이 시험한다.
 *
 * - 처음 실행할 때(온보딩보다 먼저) 권한 안내 화면 하나를 보여 주고, 시스템 권한 창을 한 번에 띄운다.
 * - 이미 설치해 쓰던 사람(v4 이하)도 빠진 권한이 있으면 다음 실행 때 같은 화면을 한 번 본다.
 * - 거절해도 앱은 버튼·키보드로 다 쓸 수 있다. 그 뒤로는 시스템 권한 창을 다시 띄우지 않는다 —
 *   음성 입력 화면은 「마이크 권한이 꺼져 있어요 [허용하기]」(앱 설정 열기)만 보여 준다([MicPermissionHint]).
 */
object AppPermissions {
    const val RECORD_AUDIO = Manifest.permission.RECORD_AUDIO

    /** API 33(Android 13)부터 런타임 권한. 그 아래는 설치 때 허용된다. */
    const val POST_NOTIFICATIONS = "android.permission.POST_NOTIFICATIONS"
    private const val NOTIFICATIONS_SINCE_SDK = 33

    /** 이 기기에서 물어야 하는 권한(마이크 + API 33+ 알림). */
    fun required(sdkInt: Int): List<String> = buildList {
        add(RECORD_AUDIO)
        if (sdkInt >= NOTIFICATIONS_SINCE_SDK) add(POST_NOTIFICATIONS)
    }

    fun missing(sdkInt: Int, granted: (String) -> Boolean): List<String> = required(sdkInt).filterNot(granted)

    /** 권한 안내 화면을 보여 줄까: 아직 한 번도 묻지 않았고 빠진 권한이 있을 때만. */
    fun shouldShowPrimer(alreadyAsked: Boolean, missing: List<String>): Boolean = !alreadyAsked && missing.isNotEmpty()

    /** 시스템 창 결과 요약(로그·시험용): 허용된 것, 거절된 것. */
    fun summarize(results: Map<String, Boolean>): Pair<List<String>, List<String>> =
        results.filterValues { it }.keys.sorted() to results.filterValues { !it }.keys.sorted()
}

/** 권한 안내를 이미 보여 줬는지(기기에만, 한 번). */
class PermissionPrimerStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    val asked: Boolean get() = prefs.getBoolean(KEY_ASKED, false)

    fun markAsked() = prefs.edit { putBoolean(KEY_ASKED, true) }

    private companion object {
        const val PREFS = "seesun_permissions"
        const val KEY_ASKED = "primer_asked"
    }
}

internal fun Context.isGranted(permission: String): Boolean =
    ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED

/** 앱 설정(권한 켜기) 화면을 연다. 열 수 없으면 false. */
fun openAppSettings(context: Context): Boolean = runCatching {
    context.startActivity(
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, "package:${context.packageName}".toUri())
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    )
}.isSuccess

/**
 * 앱 맨 앞의 권한 안내 관문. 물을 권한이 있으면 안내 화면을, 아니면(또는 끝나면) [content]를 그린다.
 * @param speaker 화면 안내 음성(TalkBack이 켜져 있으면 말하지 않음, A9)
 * @param updateNotices 가운영 자체 업데이트 빌드인가(알림 설명에 「새 버전 안내」를 넣는다)
 */
@Composable
fun PermissionPrimerGate(speaker: Speaker, updateNotices: Boolean, content: @Composable () -> Unit) {
    val context = LocalContext.current
    val inspection = LocalInspectionMode.current
    val store = remember(context) { PermissionPrimerStore(context) }
    val missing = remember(context) { AppPermissions.missing(Build.VERSION.SDK_INT) { context.isGranted(it) } }
    var show by rememberSaveable { mutableStateOf(!inspection && AppPermissions.shouldShowPrimer(store.asked, missing)) }
    if (!show) {
        content()
        return
    }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { results ->
        val (granted, denied) = AppPermissions.summarize(results)
        Log.i(TAG, "permission primer result granted=$granted denied=$denied")
        store.markAsked()
        show = false
    }
    PermissionPrimerScreen(
        missing = missing,
        updateNotices = updateNotices,
        speaker = speaker,
        onAllow = {
            store.markAsked()
            launcher.launch(missing.toTypedArray())
        },
        onSkip = {
            Log.i(TAG, "permission primer skipped")
            store.markAsked()
            show = false
        }
    )
}

/**
 * 권한 안내 화면(소유자 msg 974: 줄인 약관 동의 화면만큼 짧게). 제목 「앱 사용 권한」 + 권한마다 아이콘과 몇 글자 한 줄
 * + [허용하기](시스템 권한 창 한 번) + 작은 [나중에]. 문단·권한별 카드 없음. 안내 음성도 한 문장.
 */
@Composable
fun PermissionPrimerScreen(missing: List<String>, updateNotices: Boolean, speaker: Speaker, onAllow: () -> Unit, onSkip: () -> Unit) {
    val talkBack = rememberTalkBackEnabled()
    val voice = remember(speaker) { GuidanceVoice(speaker) }
    val guide = stringResource(R.string.ui_perm_guide)
    ScreenScaffold(
        title = stringResource(R.string.ui_perm_title),
        modifier = Modifier.testTag("permission-primer"),
        onSpeakSummary = if (talkBack) null else ({ voice.onDemand(guide, talkBack) })
    ) {
        if (AppPermissions.RECORD_AUDIO in missing) {
            PermissionLine(DsR.drawable.ic_mic, stringResource(R.string.ui_perm_mic_title), stringResource(R.string.ui_perm_mic_short))
        }
        if (AppPermissions.POST_NOTIFICATIONS in missing) {
            PermissionLine(
                R.drawable.ic_ui_notifications,
                stringResource(R.string.ui_perm_notify_title),
                stringResource(if (updateNotices) R.string.ui_perm_notify_short_update else R.string.ui_perm_notify_short)
            )
        }
        BottomActions {
            SeeSunButton(text = stringResource(R.string.ui_perm_allow), onClick = onAllow)
            // 작은 글자 버튼(누르는 곳은 48dp 이상).
            Text(
                text = stringResource(R.string.ui_perm_skip),
                style = SeeSunTheme.typography.body,
                color = SeeSunTheme.colors.onBackground,
                textAlign = TextAlign.Center,
                textDecoration = TextDecoration.Underline,
                modifier = Modifier
                    .heightIn(min = SeeSunDimens.MinTouchTarget)
                    .widthIn(min = SeeSunDimens.MinTouchTarget)
                    .clickable(role = Role.Button, onClick = onSkip)
                    .wrapContentHeight()
                    .padding(horizontal = SeeSunSpace.M)
            )
        }
    }
    AutoGuidance(key = "permission-primer", message = guide, talkBackEnabled = talkBack, speak = { voice.auto(it, talkBack) })
}

/** 권한 한 줄: 아이콘(장식) + 「마이크 · 말로 입력」. TalkBack은 「마이크, 말로 입력」 한 번에 읽는다. */
@Composable
private fun PermissionLine(@DrawableRes icon: Int, name: String, use: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = SeeSunDimens.MinTouchTarget)
            .clearAndSetSemantics { contentDescription = "$name, $use" },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(SeeSunSpace.M)
    ) {
        Icon(painterResource(icon), contentDescription = null, tint = SeeSunTheme.colors.emphasis, modifier = Modifier.size(ICON))
        Text(
            text = "$name · $use",
            style = SeeSunTheme.typography.listItem,
            color = SeeSunTheme.colors.onBackground
        )
    }
}

private val ICON = 36.dp

private const val TAG = "SeesunPermissions"
