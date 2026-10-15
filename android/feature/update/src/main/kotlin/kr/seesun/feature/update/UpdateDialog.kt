package kr.seesun.feature.update

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kr.seesun.core.a11y.politeLiveRegion
import kr.seesun.core.a11y.screenHeading
import kr.seesun.core.a11y.screenPane
import kr.seesun.core.designsystem.component.ActionRow
import kr.seesun.core.designsystem.component.ActionSpec
import kr.seesun.core.designsystem.component.ButtonKind
import kr.seesun.core.designsystem.theme.SeeSunDimens
import kr.seesun.core.designsystem.theme.SeeSunTheme

/**
 * 가운영 자체 업데이트 진입점. 앱 화면 위(MainActivity)에 한 번 둔다. 설정이 꺼져 있으면 아무것도 하지 않는다.
 *
 * - 앱을 열 때·6시간마다 다시 볼 때 매니페스트를 확인하고, 새 버전이면 안내 창을 띄운다. 서버에 닿지 않으면 조용히 넘어간다.
 * - 검증을 마친 APK는 시스템 설치 화면으로 넘긴다. 설치 허용이 꺼져 있으면 설정으로 안내하고, 돌아오면 이어서 설치한다.
 */
@Composable
fun SelfUpdateGate(viewModel: UpdateViewModel = hiltViewModel()) {
    if (!viewModel.enabled) return
    val context = LocalContext.current
    val state by viewModel.state.collectAsStateWithLifecycle()
    LifecycleResumeEffect(viewModel) {
        viewModel.onResume()
        onPauseOrDispose { }
    }
    val apk = state.apk
    LaunchedEffect(state.step, state.installerOpened, apk) {
        if (state.step == UpdateStep.READY && !state.installerOpened && apk != null) {
            if (ApkInstaller.openInstaller(context, apk)) viewModel.onInstallerOpened() else viewModel.onOpenFailed()
        }
    }
    if (state.manifest == null) return
    UpdateDialog(
        state = state,
        installedVersionName = viewModel.installedVersionName,
        onUpdate = viewModel::onUpdate,
        onLater = viewModel::onLater,
        onOpenSettings = { if (!ApkInstaller.openUnknownSourcesSettings(context)) viewModel.onOpenFailed() },
        onOpenInstaller = {
            if (apk != null && ApkInstaller.openInstaller(context, apk)) viewModel.onInstallerOpened() else viewModel.onOpenFailed()
        }
    )
}

/**
 * 업데이트 안내 창. Dialog라 TalkBack 포커스가 창 안에 갇히고(04 §4), 단계가 바뀌면 paneTitle(창 제목)이 바뀌어
 * TalkBack이 새 제목을 읽는다. 꼭 해야 하는 업데이트는 뒤로 가기·바깥 누르기로 닫히지 않고 [나중에]가 없다.
 */
@Composable
fun UpdateDialog(
    state: UpdateUiState,
    installedVersionName: String,
    onUpdate: () -> Unit,
    onLater: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenInstaller: () -> Unit
) {
    val manifest = state.manifest ?: return
    val colors = SeeSunTheme.colors
    val shape = RoundedCornerShape(SeeSunDimens.CornerRadius)
    val content = dialogContent(state, installedVersionName, manifest)
    val later = ActionSpec(stringResource(R.string.update_action_later), onLater, kind = ButtonKind.OUTLINE)
    val primary = when (state.step) {
        UpdateStep.AVAILABLE -> ActionSpec(stringResource(R.string.update_action_update), onUpdate, kind = ButtonKind.PRIMARY)

        UpdateStep.DOWNLOADING -> null

        UpdateStep.NEEDS_PERMISSION -> ActionSpec(
            stringResource(R.string.update_action_settings),
            onOpenSettings,
            kind = ButtonKind.PRIMARY
        )

        UpdateStep.READY -> ActionSpec(stringResource(R.string.update_action_install), onOpenInstaller, kind = ButtonKind.PRIMARY)

        UpdateStep.FAILED -> ActionSpec(stringResource(R.string.update_action_retry), onUpdate, kind = ButtonKind.PRIMARY)
    }
    val actions = listOfNotNull(primary, later.takeIf { !state.required })
    Dialog(
        onDismissRequest = onLater,
        properties = DialogProperties(
            dismissOnBackPress = !state.required,
            dismissOnClickOutside = false,
            usePlatformDefaultWidth = false
        )
    ) {
        Column(
            modifier = Modifier
                .padding(16.dp)
                .fillMaxWidth()
                .background(colors.surface, shape)
                .border(4.dp, colors.border, shape)
                .verticalScroll(rememberScrollState())
                .padding(20.dp)
                .screenPane(content.title),
            verticalArrangement = Arrangement.spacedBy(SeeSunDimens.Gap)
        ) {
            Text(
                text = content.title,
                style = SeeSunTheme.typography.screenTitleSmall,
                color = colors.onSurface,
                modifier = Modifier.fillMaxWidth().screenHeading()
            )
            content.lines.forEach { line ->
                Text(text = line, style = SeeSunTheme.typography.body, color = colors.onSurface, modifier = Modifier.fillMaxWidth())
            }
            if (state.step == UpdateStep.DOWNLOADING) {
                Text(
                    text = stringResource(R.string.update_progress, state.progress),
                    style = SeeSunTheme.typography.result,
                    color = colors.onSurface,
                    modifier = Modifier
                        .fillMaxWidth()
                        .semantics { progressBarRangeInfo = ProgressBarRangeInfo(state.progress / PERCENT, 0f..1f) }
                )
            }
            content.status?.let { status ->
                Text(
                    text = status,
                    style = SeeSunTheme.typography.body,
                    color = colors.onSurface,
                    modifier = Modifier.fillMaxWidth().politeLiveRegion()
                )
            }
            if (actions.isNotEmpty()) ActionRow(actions)
        }
    }
}

private class DialogContent(val title: String, val lines: List<String>, val status: String?)

@Composable
private fun dialogContent(state: UpdateUiState, installedVersionName: String, manifest: UpdateManifest): DialogContent {
    val versions = stringResource(R.string.update_versions, installedVersionName, manifest.versionName)
    return when (state.step) {
        UpdateStep.AVAILABLE -> if (state.required) {
            DialogContent(
                stringResource(R.string.update_title_required),
                listOfNotNull(stringResource(R.string.update_required_body), manifest.notes.ifBlank { null }, versions),
                null
            )
        } else {
            DialogContent(
                stringResource(R.string.update_title_available),
                listOfNotNull(manifest.notes.ifBlank { null }, versions, stringResource(R.string.update_question)),
                null
            )
        }

        UpdateStep.DOWNLOADING -> DialogContent(
            stringResource(R.string.update_title_downloading),
            emptyList(),
            stringResource(R.string.update_downloading_body)
        )

        UpdateStep.NEEDS_PERMISSION -> DialogContent(
            stringResource(R.string.update_title_permission),
            emptyList(),
            stringResource(R.string.update_permission_body)
        )

        UpdateStep.READY -> if (state.installerOpened) {
            DialogContent(stringResource(R.string.update_title_retry), emptyList(), stringResource(R.string.update_retry_body))
        } else {
            DialogContent(stringResource(R.string.update_title_ready), emptyList(), stringResource(R.string.update_ready_body))
        }

        UpdateStep.FAILED -> DialogContent(
            stringResource(R.string.update_title_failed),
            emptyList(),
            stringResource(R.string.update_failed_body)
        )
    }
}

private const val PERCENT = 100f
