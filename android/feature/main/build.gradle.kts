plugins {
    alias(libs.plugins.seesun.android.feature)
}

android {
    namespace = "kr.seesun.feature.main"
}

dependencies {
    androidTestImplementation(project(":core:uitest"))
    implementation(project(":core:ui"))
    implementation(project(":core:data"))
}
