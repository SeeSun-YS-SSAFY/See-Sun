plugins {
    alias(libs.plugins.seesun.android.feature)
}

android {
    namespace = "kr.seesun.feature.mypage"
}

dependencies {
    androidTestImplementation(project(":core:uitest"))
    implementation(project(":core:ui"))
    implementation(project(":core:data"))
    implementation(libs.androidx.activity.compose)
    testImplementation(libs.turbine)
}
