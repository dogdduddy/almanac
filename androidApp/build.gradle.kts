plugins {
    alias(libs.plugins.androidApplication)
    alias(libs.plugins.composeCompiler)
}

/**
 * Android 실행 모듈. **KMP 가 아니다** (AGP 9 제약).
 *
 * 여기 들어가는 것: Activity, Glance 위젯, Android 리소스(폰트/문자열/매니페스트).
 * 공유 로직과 UI 는 :composeApp / :shared 에서 가져온다.
 */
android {
    namespace = "com.dogdduddy.almanac"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.dogdduddy.almanac"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
    }

    buildFeatures {
        compose = true
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    kotlin {
        jvmToolchain(21)
    }
}

dependencies {
    implementation(projects.composeApp)
    implementation(projects.shared)

    // Compose 아티팩트는 :composeApp(CMP) 를 통해 전이로 들어온다.
    // androidx Compose BOM 을 따로 물리면 CMP 와 버전이 이중 관리되므로 쓰지 않는다.
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.core.ktx)

    // Glance 위젯 — 네이티브. 공유 불가.
    implementation(libs.glance.appwidget)
    implementation(libs.glance.material3)
}
