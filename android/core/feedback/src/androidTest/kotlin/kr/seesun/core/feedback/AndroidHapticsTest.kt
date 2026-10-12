package kr.seesun.core.feedback

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AndroidHapticsTest {

    @Test
    fun 다섯_패턴_모두_기기에서_오류_없이_재생된다() {
        val haptics = AndroidHaptics(ApplicationProvider.getApplicationContext())
        HapticPattern.entries.forEach { haptics.vibrate(it) }
        haptics.cancel()
    }
}
