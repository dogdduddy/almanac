import SwiftUI
import UIKit
import ComposeApp

/// Compose Multiplatform 화면을 SwiftUI 에 끼워 넣는다.
///
/// `MainViewControllerKt` 는 Kotlin 의 최상위 함수 `MainViewController()` 를
/// Swift 에서 부를 수 있게 KMP 가 만들어주는 이름이다.
struct ComposeView: UIViewControllerRepresentable {
    func makeUIViewController(context: Context) -> UIViewController {
        MainViewControllerKt.MainViewController(demoTools: Self.demoTools)
    }

    /// 촬영용 조건 고정 메뉴를 띄울지.
    ///
    /// **앱 타깃의 빌드 구성으로 판단한다.** Kotlin 쪽에서 보는 것은 프레임워크의
    /// 구성이지 앱의 구성이 아니므로, 여기서 정해 넘긴다.
    ///
    /// `DEMO_TOOLS` 는 DemoRelease 구성에서만 켜진다 (project.yml). 촬영 빌드가
    /// 따로 있는 이유는 성능이다 — 역방향 넘김과 날씨별 등장 애니메이션은 최적화된
    /// 바이너리라야 제 모습이 나오는데, Debug 로만 가르면 촬영 메뉴와 성능을
    /// 동시에 얻을 수 없다. 제출용 Release 에는 둘 다 없다.
    private static var demoTools: Bool {
        #if DEBUG || DEMO_TOOLS
        return true
        #else
        return false
        #endif
    }

    func updateUIViewController(_ uiViewController: UIViewController, context: Context) {}
}
