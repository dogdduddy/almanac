import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    alias(libs.plugins.kotlinJvm)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
    // 저장하면 실행 중인 창이 다시 그려진다. 날씨별 등장 애니메이션을 폰 빌드 없이 튜닝하기 위한 것.
    alias(libs.plugins.composeHotReload)
}

/**
 * 데스크톱 실행 모듈 (Windows / macOS / Linux, JVM).
 *
 * :androidApp 과 같은 자리다 — 화면(App)과 시작 절차(AppLoader)는 :composeApp 과 :shared 의
 * 것을 그대로 쓰고, 여기에는 조립 지점과 창 하나만 있다.
 *
 * **여기 없는 것도 의도다.** 위젯·결제·측위는 플랫폼 능력이라 데스크톱에는 두지 않는다.
 * 결제가 없으므로 서가는 무료 스타터로 고정되고, 화면은 상품이 비면 서가 진입점을 감춘다.
 */
kotlin {
    jvmToolchain(21)
}

dependencies {
    implementation(projects.composeApp)
    implementation(projects.shared)
    implementation(compose.desktop.currentOs)
    implementation(libs.kotlinx.coroutines.swing)

    testImplementation(kotlin("test"))
}

tasks.test {
    useJUnitPlatform()
}

/**
 * 촬영용 고정 메뉴 스위치를 앱 JVM 으로 넘긴다.
 *
 *   ./gradlew :desktopApp:run -Palmanac.demo=true
 *
 * 환경 변수로 하지 않는 이유: Gradle 은 앱을 **데몬의 환경**으로 띄워서, 셸에서 앞에
 * 붙인 변수가 들어가지 않는다. 기본값이 false 라 그냥 띄우면 진입점이 없다.
 */
tasks.withType<JavaExec>().configureEach {
    systemProperty("almanac.demo", providers.gradleProperty("almanac.demo").getOrElse("false"))
}

compose.desktop {
    application {
        mainClass = "com.dogdduddy.almanac.MainKt"

        nativeDistributions {
            targetFormats(TargetFormat.Dmg, TargetFormat.Msi, TargetFormat.Deb)
            packageName = "Almanac"
            packageVersion = "0.3.1"
            description = "Today's weather, in a passage written long ago."
            vendor = "dogdduddy"
        }
    }
}

/**
 * 클래스패스에 content.db 를 넣는다. Android 의 preBuild → syncContentToAndroid 와 같은 배관.
 * 사본은 생성물이라 gitignore 대상이고, 정본은 content/content.db 다.
 */
tasks.named("processResources").configure { dependsOn(":syncContentToDesktop") }
