#!/usr/bin/env swift
//
//  #9 아웃트로의 끝 카드를 굽는다 — 앱 이름, 스토어 배지, 날씨 데이터 출처.
//
//  출처 줄은 장식이 아니다. MET Norway 데이터는 CC BY 4.0 이라 **표기가 의무**고,
//  제출 영상도 그 데이터로 그린 화면을 싣는다. 문구는 앱 About 과 같은 문자열
//  (`MetNorwayClient.ATTRIBUTION`)을 쓴다 — 두 곳의 말이 다르면 어느 쪽이 맞는지 묻게 된다.
//
//  배지는 **공식 파일을 그대로** 얹는다. 두 스토어 모두 배지를 다시 그리거나 고치는 것을
//  금한다. 받는 곳:
//    App Store    https://developer.apple.com/assets/elements/badges/download-on-the-app-store.svg
//    Google Play  https://play.google.com/intl/en_us/badges/static/images/badges/en_badge_web_generic.png
//  Google 파일에는 투명 여백이 붙어 있어서, 그 여백을 뺀 **보이는 높이**로 두 배지를 맞춘다.
//
//  사용:
//    swift scripts/render_endcard.swift <폰트.ttf> <app-store.svg> <google-play.png> <출력.png>
//
import AppKit
import CoreText

let args = CommandLine.arguments
guard args.count >= 5 else {
    FileHandle.standardError.write(
        "사용: render_endcard.swift <폰트> <app-store.svg> <google-play.png> <출력.png>\n".data(using: .utf8)!)
    exit(2)
}
let fontPath = args[1], appStorePath = args[2], googlePlayPath = args[3], outPath = args[4]

let width = 1920, height = 1080

/// `MetNorwayClient.ATTRIBUTION` 과 같은 문자열이어야 한다.
let attribution = "Weather data from MET Norway (api.met.no), licensed under CC BY 4.0"

/// 앱의 색. App.kt 의 Paper / Ink / Muted 와 같은 값이어야 한다.
func rgb(_ v: UInt32) -> NSColor {
    NSColor(srgbRed: CGFloat((v >> 16) & 0xFF) / 255,
            green: CGFloat((v >> 8) & 0xFF) / 255,
            blue: CGFloat(v & 0xFF) / 255, alpha: 1)
}
let paper = rgb(0xFBF9F4), ink = rgb(0x1A1A1A), muted = rgb(0x8A8378)

func fail(_ message: String) -> Never {
    FileHandle.standardError.write("\(message)\n".data(using: .utf8)!)
    exit(1)
}

func loadFont(_ path: String, size: CGFloat) -> NSFont {
    let url = URL(fileURLWithPath: path) as CFURL
    CTFontManagerRegisterFontsForURL(url, .process, nil)
    guard
        let descriptors = CTFontManagerCreateFontDescriptorsFromURL(url) as? [CTFontDescriptor],
        let first = descriptors.first,
        let name = CTFontDescriptorCopyAttribute(first, kCTFontNameAttribute) as? String,
        let font = NSFont(name: name, size: size)
    else { fail("폰트를 못 읽었다: \(path)") }
    return font
}

func loadImage(_ path: String) -> NSImage {
    guard let image = NSImage(contentsOf: URL(fileURLWithPath: path)) else { fail("그림을 못 읽었다: \(path)") }
    return image
}

/// 투명 여백을 뺀, 실제로 칠해진 영역 (그림 좌표, 왼쪽 아래 원점).
func opaqueBounds(_ image: NSImage) -> NSRect {
    guard let tiff = image.tiffRepresentation, let rep = NSBitmapImageRep(data: tiff) else {
        return NSRect(origin: .zero, size: image.size)
    }
    var minX = rep.pixelsWide, minY = rep.pixelsHigh, maxX = -1, maxY = -1
    for y in 0..<rep.pixelsHigh {
        for x in 0..<rep.pixelsWide where (rep.colorAt(x: x, y: y)?.alphaComponent ?? 0) > 0.05 {
            minX = min(minX, x); maxX = max(maxX, x)
            minY = min(minY, y); maxY = max(maxY, y)
        }
    }
    guard maxX >= 0 else { return NSRect(origin: .zero, size: image.size) }
    // 비트맵은 위에서부터 센다. 그림 좌표로 옮긴다.
    let sx = image.size.width / CGFloat(rep.pixelsWide), sy = image.size.height / CGFloat(rep.pixelsHigh)
    return NSRect(x: CGFloat(minX) * sx,
                  y: CGFloat(rep.pixelsHigh - 1 - maxY) * sy,
                  width: CGFloat(maxX - minX + 1) * sx,
                  height: CGFloat(maxY - minY + 1) * sy)
}

guard let rep = NSBitmapImageRep(
    bitmapDataPlanes: nil, pixelsWide: width, pixelsHigh: height,
    bitsPerSample: 8, samplesPerPixel: 4, hasAlpha: true, isPlanar: false,
    colorSpaceName: .deviceRGB, bytesPerRow: 0, bitsPerPixel: 0)
else { exit(1) }

NSGraphicsContext.saveGraphicsState()
NSGraphicsContext.current = NSGraphicsContext(bitmapImageRep: rep)
NSGraphicsContext.current?.imageInterpolation = .high
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

// 이름. 앱 화면의 "N years ago" 와 같은 서체라 앞 컷에서 끊기지 않는다.
center("Almanac", size: 132, color: ink, topY: 318)

// 배지. 보이는 높이를 같게 맞추고 한 줄에 가운데로 모은다.
let badgeHeight: CGFloat = 84
let gap: CGFloat = 36
let badges: [(NSImage, NSRect)] = [appStorePath, googlePlayPath].map { path in
    let image = loadImage(path)
    return (image, opaqueBounds(image))
}
let widths = badges.map { $0.1.width * badgeHeight / $0.1.height }
var x = (CGFloat(width) - widths.reduce(0, +) - gap * CGFloat(badges.count - 1)) / 2
let badgeTop: CGFloat = 560
for ((image, bounds), w) in zip(badges, widths) {
    let target = NSRect(x: x, y: CGFloat(height) - badgeTop - badgeHeight, width: w, height: badgeHeight)
    image.draw(in: target, from: bounds, operation: .sourceOver, fraction: 1)
    x += w + gap
}

// 출처. 작지만 읽혀야 한다 — 1080p 에서 26px 이 한계다.
center(attribution, size: 26, color: muted, topY: 972)

NSGraphicsContext.restoreGraphicsState()
guard let png = rep.representation(using: .png, properties: [:]) else { exit(1) }
try png.write(to: URL(fileURLWithPath: outPath))
print("\(width)x\(height) → \(outPath)")
