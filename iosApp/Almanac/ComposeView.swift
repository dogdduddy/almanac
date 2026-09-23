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
    private static var demoTools: Bool {
        #if DEBUG
        return true
        #else
        return false
        #endif
    }

    func updateUIViewController(_ uiViewController: UIViewController, context: Context) {}
}
