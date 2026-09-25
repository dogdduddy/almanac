#!/usr/bin/env swift
//
//  #7 KMP 증명 컷의 구조 카드를 굽는다.
//
//  분할 화면이 "같은 입력에 같은 반응" 을 보여준 **뒤에** 5초간 붙는 카드다.
//  화면이 증명한 것에 이름을 붙이는 자리이지 설명하는 자리가 아니므로,
//  읽는 데 5초를 넘기면 안 된다 — `deterministic pick` 을 뺀 이유가 그것이다
//  (그 설명은 Devpost 본문으로 간다).
//
//  앱과 같은 폰트·같은 색으로 굽는다. 앞뒤 컷이 앱 화면이라 카드만 다른 서체면
//  거기서 영상이 끊긴다.
//
//  사용:
//    swift scripts/render_card.swift <폰트.ttf> <출력.png>
//
import AppKit
import CoreText

let args = CommandLine.arguments
guard args.count >= 3 else {
    FileHandle.standardError.write("사용: render_card.swift <폰트> <출력.png>\n".data(using: .utf8)!)
    exit(2)
}
let fontPath = args[1]
let outPath = args[2]

let width = 1920, height = 1080

/// 앱의 색. App.kt 의 Paper / Ink / Muted 와 같은 값이어야 한다.
func rgb(_ v: UInt32) -> NSColor {
    NSColor(srgbRed: CGFloat((v >> 16) & 0xFF) / 255,
            green: CGFloat((v >> 8) & 0xFF) / 255,
            blue: CGFloat(v & 0xFF) / 255, alpha: 1)
}
let paper = rgb(0xFBF9F4), ink = rgb(0x1A1A1A), muted = rgb(0x8A8378)

func loadFont(_ path: String, size: CGFloat) -> NSFont {
    let url = URL(fileURLWithPath: path) as CFURL
    CTFontManagerRegisterFontsForURL(url, .process, nil)
    guard
        let descriptors = CTFontManagerCreateFontDescriptorsFromURL(url) as? [CTFontDescriptor],
        let first = descriptors.first,
        let name = CTFontDescriptorCopyAttribute(first, kCTFontNameAttribute) as? String,
        let font = NSFont(name: name, size: size)
    else {
        FileHandle.standardError.write("폰트를 못 읽었다: \(path)\n".data(using: .utf8)!)
        exit(1)
    }
    return font
}

guard let rep = NSBitmapImageRep(
    bitmapDataPlanes: nil, pixelsWide: width, pixelsHigh: height,
    bitsPerSample: 8, samplesPerPixel: 4, hasAlpha: true, isPlanar: false,
    colorSpaceName: .deviceRGB, bytesPerRow: 0, bitsPerPixel: 0)
else { exit(1) }

NSGraphicsContext.saveGraphicsState()
NSGraphicsContext.current = NSGraphicsContext(bitmapImageRep: rep)
paper.set()
NSBezierPath.fill(NSRect(x: 0, y: 0, width: width, height: height))

/// 가로 중앙에 한 줄. y 는 **위에서부터** 잰다 (읽는 순서와 같게).
func center(_ text: String, size: CGFloat, color: NSColor, topY: CGFloat) {
    let attrs: [NSAttributedString.Key: Any] = [.font: loadFont(fontPath, size: size),
                                                .foregroundColor: color]
    let s = NSAttributedString(string: text, attributes: attrs)
    let m = s.size()
    s.draw(at: NSPoint(x: (CGFloat(width) - m.width) / 2, y: CGFloat(height) - topY - m.height))
}

/// 세 칸 중 하나.
///
/// 화면 폭을 3등분하면 양끝이 가장자리로 밀려 **한 줄로 안 읽힌다.** 가운데 band 안에만
/// 배치해 셋이 한 묶음으로 보이게 한다.
let columnCenters: [CGFloat] = [0.27, 0.50, 0.73]

func column(_ text: String, size: CGFloat, color: NSColor, topY: CGFloat, index: Int) {
    let attrs: [NSAttributedString.Key: Any] = [.font: loadFont(fontPath, size: size),
                                                .foregroundColor: color]
    let s = NSAttributedString(string: text, attributes: attrs)
    let m = s.size()
    let cx = CGFloat(width) * columnCenters[index]
    s.draw(at: NSPoint(x: cx - m.width / 2, y: CGFloat(height) - topY - m.height))
}

// 위: 무엇이 공유되는가.
center("Shared Kotlin", size: 92, color: ink, topY: 300)
center("weather · matching · SQLDelight", size: 40, color: muted, topY: 425)

// 아래: 그 하나가 어디로 나가는가. 다섯 표면이 세 칸에 들어간다.
let surfaces = [
    ("Android", "Compose · Glance"),
    ("iOS", "Compose · WidgetKit"),
    ("Desktop", "Compose"),
]
for (i, (platform, tech)) in surfaces.enumerated() {
    column(platform, size: 52, color: ink, topY: 630, index: i)
    column(tech, size: 32, color: muted, topY: 706, index: i)
}

NSGraphicsContext.restoreGraphicsState()
guard let png = rep.representation(using: .png, properties: [:]) else { exit(1) }
try png.write(to: URL(fileURLWithPath: outPath))
print("\(width)x\(height) → \(outPath)")
