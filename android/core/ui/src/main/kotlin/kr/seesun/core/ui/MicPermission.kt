package kr.seesun.core.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.res.stringResource
import androidx.core.content.ContextCompat
import kr.seesun.core.designsystem.component.BodyText
import kr.seesun.core.designsystem.component.ButtonKind
import kr.seesun.core.designsystem.component.LiveStatusText
import kr.seesun.core.designsystem.component.SeeSunButton
import kr.seesun.core.designsystem.theme.SeeSunDimens

/** 마이크 권한 요청 창구: 권한이 있으면 바로 실행, 없으면 목적 안내(T-13)를 먼저 보여 준다. */
class MicPermissionGate internal constructor(
    val request: () -> Unit,
    internal val rationaleVisible: Boolean,
    internal val allow: () -> Unit,
    internal val decline: () -> Unit
)

/**
 * 권한 요청 직전 안내(법적 검토 T-13): 왜 마이크를 쓰는지·소리를 어디서 처리하고 저장하지 않는지·거부해도 쓸 수 있는 방법을
 * 먼저 말한 뒤 [마이크 허용하기]를 눌렀을 때만 시스템 권한 창을 띄운다.
 *
 * @param onGranted 권한이 있거나 방금 허용됐을 때(듣기 시작)
 * @param onDeclined 안내에서 [키보드로 입력하기]를 골랐거나 시스템 창에서 거부했을 때
 */
@Composable
fun rememberMicPermissionGate(onGranted: () -> Unit, onDeclined: () -> Unit): MicPermissionGate {
    val context = LocalContext.current
    val inspection = LocalInspectionMode.current
    var rationale by rememberSaveable { mutableStateOf(false) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) onGranted() else onDeclined()
    }
    return MicPermissionGate(
        request = {
            val granted = inspection ||
                ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
            if (granted) onGranted() else rationale = true
        },
        rationaleVisible = rationale,
        allow = {
            rationale = false
            launcher.launch(Manifest.permission.RECORD_AUDIO)
        },
        decline = {
            rationale = false
            onDeclined()
        }
    )
}

/** 안내 글(liveRegion으로 바로 읽힘) + [마이크 허용하기] [키보드로 입력하기]. 권한이 필요할 때만 보인다. */
@Composable
fun MicPermissionRationale(gate: MicPermissionGate, modifier: Modifier = Modifier) {
    if (!gate.rationaleVisible) return
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(SeeSunDimens.Gap)) {
        LiveStatusText(stringResource(R.string.ui_mic_rationale))
        BodyText(stringResource(R.string.ui_mic_rationale_alternative))
        SeeSunButton(text = stringResource(R.string.ui_mic_allow), onClick = gate.allow)
        SeeSunButton(text = stringResource(R.string.ui_mic_decline), onClick = gate.decline, kind = ButtonKind.OUTLINE)
    }
}
