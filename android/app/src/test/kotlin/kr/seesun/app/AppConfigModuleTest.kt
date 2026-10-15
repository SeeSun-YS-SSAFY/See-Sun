package kr.seesun.app

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** 앱 설정이 코어 모듈에 빠짐없이 전달되는지(통합 E2E BUG-05: 동의 기록의 앱 버전이 비어 있었다). */
class AppConfigModuleTest {
    @Test
    fun `동의 기록에 남길 앱 버전을 데이터 계층에 넘긴다`() {
        val config = AppConfigModule.dataConfig()
        assertTrue(config.appVersion.isNotBlank())
        assertEquals(BuildConfig.VERSION_NAME, config.appVersion)
    }
}
