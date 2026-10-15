package kr.seesun.app

/**
 * 실서버가 있어야 도는 기기 시험. 평소 게이트(connectedDebugAndroidTest)에서는 빼고
 * (app/build.gradle.kts의 notAnnotation), scripts/e2e 실행기가 `am instrument -e class`로 직접 고른다.
 */
@Retention(AnnotationRetention.RUNTIME)
@Target(AnnotationTarget.CLASS)
annotation class RealServer
