#!/usr/bin/env swift
//
//  자막 한 줄을 투명 PNG 로 굽는다.
//
//  왜 ffmpeg 이 아닌가: 이 머신의 ffmpeg 에는 drawtext(libfreetype)도 subtitles(libass)도
//  없다. 그리고 있었더라도 CoreText 로 굽는 편이 낫다 — **앱 화면과 같은 엔진으로 같은
//  폰트를 조판**하므로 커닝과 합자가 영상과 화면에서 어긋나지 않는다. 이 앱은 타이포그래피가
//  전부라 그 차이가 보인다.
//
//  사용:
//    swift scripts/render_text.swift <폰트.ttf> <크기> <#RRGGBB> <출력.png> <글자…>
//
import AppKit
import CoreText

let args = CommandLine.arguments
guard args.count >= 6 else {
    FileHandle.standardError.write(
        "사용: render_text.swift <폰트> <크기> <#RRGGBB> <출력.png> <글자…>\n".data(using: .utf8)!)
    exit(2)
}

let fontPath = args[1]
let size = CGFloat(Double(args[2]) ?? 48)
let hex = args[3]
let outPath = args[4]
let text = args[5...].joined(separator: " ")

/// 파일에서 폰트를 등록하고 이름을 얻는다. 시스템에 설치돼 있지 않아도 쓸 수 있다.
func loadFont(_ path: String, size: CGFloat) -> NSFont {
    let url = URL(fileURLWithPath: path) as CFURL
    CTFontManagerRegisterFontsForURL(url, .process, nil)
    guard
        let descriptors = CTFontManagerCreateFontDescriptorsFromURL(url) as? [CTFontDescriptor],
        let first = descriptors.first,
        let name = (CTFontDescriptorCopyAttribute(first, kCTFontNameAttribute) as? String),
        let font = NSFont(name: name, size: size)
    else {
        FileHandle.standardError.write("폰트를 못 읽었다: \(path)\n".data(using: .utf8)!)
        exit(1)
    }
    return font
}

func color(_ hex: String) -> NSColor {
    var s = hex
    if s.hasPrefix("#") { s.removeFirst() }
    let v = UInt32(s, radix: 16) ?? 0
    return NSColor(
        srgbRed: CGFloat((v >> 16) & 0xFF) / 255,
        green: CGFloat((v >> 8) & 0xFF) / 255,
        blue: CGFloat(v & 0xFF) / 255,
        alpha: 1)
}

let attributes: [NSAttributedString.Key: Any] = [
    .font: loadFont(fontPath, size: size),
    .foregroundColor: color(hex),
]
let attributed = NSAttributedString(string: text, attributes: attributes)

// 글자가 잘리지 않게 여백을 둔다. 디센더와 안티에일리어싱이 경계를 넘는다.
let padding = CGFloat(Int(size * 0.4))
let measured = attributed.size()
let width = Int(ceil(measured.width + padding * 2))
let height = Int(ceil(measured.height + padding * 2))

guard let rep = NSBitmapImageRep(
    bitmapDataPlanes: nil, pixelsWide: width, pixelsHigh: height,
    bitsPerSample: 8, samplesPerPixel: 4, hasAlpha: true, isPlanar: false,
    colorSpaceName: .deviceRGB, bytesPerRow: 0, bitsPerPixel: 0)
else { exit(1) }

NSGraphicsContext.saveGraphicsState()
NSGraphicsContext.current = NSGraphicsContext(bitmapImageRep: rep)
NSColor.clear.set()
NSBezierPath.fill(NSRect(x: 0, y: 0, width: width, height: height))
attributed.draw(at: NSPoint(x: padding, y: padding))
NSGraphicsContext.restoreGraphicsState()

guard let png = rep.representation(using: .png, properties: [:]) else { exit(1) }
try png.write(to: URL(fileURLWithPath: outPath))
print("\(width)x\(height)")
