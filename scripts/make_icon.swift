#!/usr/bin/env swift
//
// 앱 아이콘 생성기.
//
// 디자인 도구 없이 저장소 안에서 아이콘을 재생성할 수 있게 코드로 둔다.
// 색과 서체가 앱 화면과 같은 값이어야 하는데, 이미지 파일로만 두면 화면 색을
// 바꿀 때 아이콘이 조용히 어긋난다.
//
// 컨셉: 크림색 종이 위의 세리프 'A'. 아래 가는 선은 지평선이자 본문 첫 줄이다.
// ("오늘의 하늘은 이미 오래전에 누군가 써놓았다")
//
// 사용: swift scripts/make_icon.swift <출력디렉터리>

import Foundation
import CoreGraphics
import CoreText
import ImageIO
import UniformTypeIdentifiers

// 앱 화면과 같은 값 (App.kt 의 Paper / Ink)
let paper = CGColor(red: 0.984, green: 0.976, blue: 0.957, alpha: 1)
let ink = CGColor(red: 0.102, green: 0.102, blue: 0.102, alpha: 1)

let fontPath = "composeApp/src/commonMain/composeResources/font/crimson_text.ttf"

func loadFont(size: CGFloat) -> CTFont? {
    guard let data = NSData(contentsOfFile: fontPath),
          let provider = CGDataProvider(data: data),
          let cgFont = CGFont(provider) else { return nil }
    return CTFontCreateWithGraphicsFont(cgFont, size, nil, nil)
}

func renderIcon(size: CGFloat) -> CGImage? {
    guard let ctx = CGContext(
        data: nil,
        width: Int(size),
        height: Int(size),
        bitsPerComponent: 8,
        bytesPerRow: 0,
        space: CGColorSpaceCreateDeviceRGB(),
        bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue
    ) else { return nil }

    ctx.setFillColor(paper)
    ctx.fill(CGRect(x: 0, y: 0, width: size, height: size))

    guard let font = loadFont(size: size * 0.80) else { return nil }

    // AppKit/UIKit 을 안 쓰므로 NSAttributedString.Key 대신 CoreText 키를 직접 쓴다.
    let attrs: CFDictionary = [
        kCTFontAttributeName: font,
        kCTForegroundColorAttributeName: ink,
    ] as CFDictionary

    guard let attributed = CFAttributedStringCreate(nil, "A" as CFString, attrs) else {
        return nil
    }
    let line = CTLineCreateWithAttributedString(attributed)
    let glyph = CTLineGetBoundsWithOptions(line, .useGlyphPathBounds)

    // 지평선이자 본문 첫 줄. 글자 폭보다 조금 넓게 잡아 글자를 받치는 모양이 되게 한다.
    let ruleWidth = glyph.width * 1.22
    let gap = size * 0.048

    // **광학 중심을 맞춘다.** 글자 높이 + 간격 + 선을 한 덩어리로 보고 그 덩어리를
    // 아이콘 중앙에 놓는다. 글자만 기준으로 잡으면 아래에 큰 여백이 남는다.
    let blockHeight = glyph.height + gap
    let blockBottom = (size - blockHeight) / 2
    let baseline = blockBottom + gap

    // 선 두께에 하한을 둔다. 비율로만 잡으면 48px 아이콘에서 1px 미만이 되어
    // 렌더러가 지워버린다 — 큰 크기에서만 보이는 요소는 아이콘에 두면 안 된다.
    ctx.setStrokeColor(ink.copy(alpha: 0.40)!)
    ctx.setLineWidth(max(1, size * 0.016))
    ctx.move(to: CGPoint(x: (size - ruleWidth) / 2, y: blockBottom))
    ctx.addLine(to: CGPoint(x: (size + ruleWidth) / 2, y: blockBottom))
    ctx.strokePath()

    ctx.textPosition = CGPoint(
        x: (size - glyph.width) / 2 - glyph.minX,
        y: baseline - glyph.minY
    )
    CTLineDraw(line, ctx)

    return ctx.makeImage()
}

func write(_ image: CGImage, to path: String) {
    let url = URL(fileURLWithPath: path)
    try? FileManager.default.createDirectory(
        at: url.deletingLastPathComponent(), withIntermediateDirectories: true
    )
    guard let dest = CGImageDestinationCreateWithURL(
        url as CFURL, UTType.png.identifier as CFString, 1, nil
    ) else { return }
    CGImageDestinationAddImage(dest, image, nil)
    CGImageDestinationFinalize(dest)
    print("  \(path)")
}

let outDir = CommandLine.arguments.count > 1 ? CommandLine.arguments[1] : "build/icons"

// 스토어 제출용 1024 + Android 밀도별
let sizes: [(String, CGFloat)] = [
    ("icon-1024.png", 1024),
    ("icon-192.png", 192),
    ("icon-144.png", 144),
    ("icon-96.png", 96),
    ("icon-72.png", 72),
    ("icon-48.png", 48),
]

print("아이콘 생성:")
for (name, size) in sizes {
    guard let image = renderIcon(size: size) else {
        FileHandle.standardError.write("렌더 실패: \(name)\n".data(using: .utf8)!)
        exit(1)
    }
    write(image, to: "\(outDir)/\(name)")
}
