package kr.seesun.core.designsystem.component

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kr.seesun.core.designsystem.R

/** 로고를 TalkBack이 읽는 이름. 그림과 글자 표식을 합쳐 한 번만 읽는다. */
const val LOGO_LABEL = "시선"

/**
 * 가로 로고(Figma 메인 1031:3818): 해 원판 + "See:Sun" 글자 표식. 두 그림을 하나로 묶어 "시선"으로 한 번 읽는다.
 * 원판이 64dp 미만이면 둘레 글자를 뺀 그림을 쓴다(작으면 읽을 수 없는 글자). 로고는 글꼴 크기를 따르지 않는다(WCAG 1.4.4·1.4.5 로고 예외).
 *
 * @param heading 화면 제목 대신 로고를 둘 때(메인) true.
 */
@Composable
fun SeeSunLogoRow(modifier: Modifier = Modifier, emblemSize: Dp = 56.dp, heading: Boolean = false) {
    Row(
        modifier = modifier.clearAndSetSemantics {
            contentDescription = LOGO_LABEL
            if (heading) heading()
        },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(emblemSize * WORD_GAP_RATIO)
    ) {
        Emblem(emblemSize)
        Image(
            painterResource(R.drawable.seesun_wordmark),
            contentDescription = null,
            modifier = Modifier.height(emblemSize * WORD_HEIGHT_RATIO)
        )
    }
}

/**
 * 세로 로고(Figma 진입 화면 INIT 345:961): 큰 원판(둘레 글자 포함) 위, "See:Sun" 아래. "시선"으로 한 번 읽는다.
 */
@Composable
fun SeeSunLogoStack(modifier: Modifier = Modifier, emblemSize: Dp = 160.dp) {
    Column(
        modifier = modifier.clearAndSetSemantics { contentDescription = LOGO_LABEL },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(emblemSize * STACK_GAP_RATIO)
    ) {
        Emblem(emblemSize)
        Image(
            painterResource(R.drawable.seesun_wordmark),
            contentDescription = null,
            modifier = Modifier.height(emblemSize * STACK_WORD_RATIO)
        )
    }
}

@Composable
private fun Emblem(size: Dp) {
    val res = if (size < FULL_EMBLEM_MIN) R.drawable.seesun_emblem_plain else R.drawable.seesun_emblem
    Image(painterResource(res), contentDescription = null, modifier = Modifier.size(size))
}

private val FULL_EMBLEM_MIN = 64.dp
private const val WORD_GAP_RATIO = 0.3f
private const val WORD_HEIGHT_RATIO = 0.62f
private const val STACK_GAP_RATIO = 0.25f
private const val STACK_WORD_RATIO = 0.3f
