import SwiftUI
import UIKit
import ComposeApp

/// Compose Multiplatform 화면을 SwiftUI 에 끼워 넣는다.
///
/// `MainViewControllerKt` 는 Kotlin 의 최상위 함수 `MainViewController()` 를
/// Swift 에서 부를 수 있게 KMP 가 만들어주는 이름이다.
struct ComposeView: UIViewControllerRepresentable {
    func makeUIViewController(context: Context) -> UIViewController {
        MainViewControllerKt.MainViewController()
    }

    func updateUIViewController(_ uiViewController: UIViewController, context: Context) {}
}
