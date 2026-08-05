plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidKmpLibrary)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
}

/**
 * 양 플랫폼이 공유하는 Compose Multiplatform UI.
 *
 * AGP 9 부터 `com.android.application` 과 KMP 플러그인은 같은 모듈에 공존할 수 없다.
 * 그래서 Android 실행 모듈(:androidApp)은 순수 Android 로 두고,
 * 공유 UI 는 이 KMP 라이브러리에 모은다. iOS 는 여기서 나오는 프레임워크를 링크한다.
 */
kotlin {
    jvmToolchain(21)

    android {
        namespace = "com.dogdduddy.almanac.ui"
        compileSdk = 36
        minSdk = 26
    }

    // iosX64(인텔 시뮬레이터)는 뺀다 — Compose Multiplatform 1.11.x 가 해당 타깃 아티팩트를
    // 발행하지 않아 의존성 해석이 깨진다. 개발 머신이 Apple Silicon 이라 필요도 없다.
    listOf(
        iosArm64(),
        iosSimulatorArm64(),
    ).forEach { target ->
        target.binaries.framework {
            baseName = "ComposeApp"
            isStatic = true
            export(projects.shared)
        }
    }

    sourceSets {
        commonMain.dependencies {
            api(projects.shared)
            implementation(compose.runtime)
            implementation(compose.foundation)
            implementation(compose.material3)
            implementation(compose.ui)
            implementation(compose.components.resources)
        }
    }
}
