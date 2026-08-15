import WidgetKit
import SwiftUI
import AlmanacKit

/// 홈 화면 위젯.
///
/// Android 의 Glance 위젯과 **같은 함수**(`AlmanacService.todaysPage`)를 부른다.
/// 위젯이 자기만의 조회 경로를 가지면 결정론이 깨지고 날씨 캐시도 두 벌이 된다.
///
/// iOS 는 Android 와 달리 커스텀 폰트에 제약이 없지만, 두 플랫폼 위젯이 달라 보이면
/// 심사 기준의 '일관성' 에서 손해다. 그래서 여기서도 히어로만 강조하는 같은 위계를 쓴다.
struct AlmanacEntry: TimelineEntry {
    let date: Date
    let page: TodaysPage?
}

struct Provider: TimelineProvider {

    func placeholder(in context: Context) -> AlmanacEntry {
        AlmanacEntry(date: Date(), page: nil)
    }

    func getSnapshot(in context: Context, completion: @escaping (AlmanacEntry) -> Void) {
        Task {
            completion(AlmanacEntry(date: Date(), page: await loadPage()))
        }
    }

    func getTimeline(in context: Context, completion: @escaping (Timeline<AlmanacEntry>) -> Void) {
        Task {
            let entry = AlmanacEntry(date: Date(), page: await loadPage())

            // 날씨 캐시가 최소 1시간이므로 그보다 자주 깨울 이유가 없다.
            // 시간대(아침/낮/저녁)가 바뀌면 문장도 바뀌므로 한 시간 간격이면 충분히 따라간다.
            let next = Calendar.current.date(byAdding: .hour, value: 1, to: Date()) ?? Date()
            completion(Timeline(entries: [entry], policy: .after(next)))
        }
    }

    /// 위젯은 측위를 기다릴 수 없으므로 저장된 위치만 쓴다 (`refreshLocation: false`).
    private func loadPage() async -> TodaysPage? {
        do {
            let result = try await IosAlmanacGraph.shared.service.todaysPage(
                language: "en",
                refreshLocation: false
            )
            return (result as? PageResultReady)?.page
        } catch {
            // 위젯은 예외로 죽으면 안 된다. 빈 상태로 그린다.
            return nil
        }
    }
}

struct AlmanacWidgetView: View {
    var entry: AlmanacEntry

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            if let page = entry.page {
                // 히어로만 Crimson. 본문은 시스템 serif 로 둔다 —
                // Android 위젯이 (RemoteViews 제약 때문에) 히어로만 비트맵으로 굽고
                // 나머지는 시스템 serif 이므로, 두 플랫폼 위젯을 같은 위계로 맞춘 것이다.
                //
                // 이름은 파일명이 아니라 **PostScript 이름**이어야 한다.
                // 틀리면 에러 없이 시스템 폰트로 폴백한다.
                Text("\(page.yearsAgo) years ago")
                    .font(.custom("CrimsonText-Regular", size: 30))
                    .foregroundStyle(Color.ink)
                    .lineLimit(1)
                    .minimumScaleFactor(0.6)

                Text(page.weatherPhrase)
                    .font(.system(.caption, design: .serif))
                    .foregroundStyle(Color.muted)
                    .padding(.top, 2)

                Text(page.text)
                    .font(.system(.footnote, design: .serif))
                    .foregroundStyle(Color.ink)
                    .lineLimit(6)
                    .padding(.top, 10)

                Text(page.attribution)
                    .font(.system(.caption2, design: .serif))
                    .foregroundStyle(Color.muted)
                    .lineLimit(2)
                    .padding(.top, 8)
            } else {
                Text("Waiting for the sky…")
                    .font(.system(.footnote, design: .serif))
                    .foregroundStyle(Color.muted)
            }
            Spacer(minLength: 0)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .containerBackground(Color.paper, for: .widget)
    }
}

@main
struct AlmanacWidget: Widget {
    var body: some WidgetConfiguration {
        StaticConfiguration(kind: "AlmanacWidget", provider: Provider()) { entry in
            AlmanacWidgetView(entry: entry)
        }
        .configurationDisplayName("Almanac")
        .description("오늘의 하늘을 쓴 문장")
        .supportedFamilies([.systemMedium, .systemLarge])
    }
}

private extension Color {
    static let paper = Color(red: 0.984, green: 0.976, blue: 0.957)
    static let ink = Color(red: 0.102, green: 0.102, blue: 0.102)
    static let muted = Color(red: 0.541, green: 0.514, blue: 0.470)
}
