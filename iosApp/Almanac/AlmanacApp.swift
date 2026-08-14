import SwiftUI

/// iOS 셸.
///
/// 화면은 전부 Compose Multiplatform 이고 Swift 는 창을 띄우는 역할만 한다.
/// Android 와 같은 UI 코드가 도는 것이 심사 기준의 '일관성' 이므로,
/// 여기에 SwiftUI 화면을 늘리지 말 것.
@main
struct AlmanacApp: App {
    var body: some Scene {
        WindowGroup {
            ComposeView()
                .ignoresSafeArea(.all)
        }
    }
}
