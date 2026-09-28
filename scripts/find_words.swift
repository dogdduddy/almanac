#!/usr/bin/env swift
//
//  화면 캡처에서 단어의 자리를 찾는다 — #3 의 날씨 단어 강조가 어디를 칠할지.
//
//  좌표를 눈으로 재서 박으면 컷을 다시 찍을 때마다 다시 재야 하고, 한 번 틀리면
//  강조가 글자 옆 빈 종이에 뜬다. macOS 의 문자 인식(Vision)은 줄마다 **글자 범위의
//  상자**를 주므로 단어 단위로 정확히 잡힌다.
//
//  단어는 통째로만 맞춘다 — `rain` 을 찾을 때 `drain`, `rainbow` 는 제외한다.
//  대소문자는 가리지 않는다.
//
//  사용:
//    swift scripts/find_words.swift <그림.png> <단어…>
//  출력 (한 줄에 하나, 픽셀, 왼쪽 위 원점):
//    <단어> <x> <y> <폭> <높이>
//
import AppKit
import Vision

let args = CommandLine.arguments
guard args.count >= 3 else {
    FileHandle.standardError.write("사용: find_words.swift <그림.png> <단어…>\n".data(using: .utf8)!)
    exit(2)
}
let words = Set(args[2...].map { $0.lowercased() })

guard
    let image = NSImage(contentsOf: URL(fileURLWithPath: args[1])),
    let cg = image.cgImage(forProposedRect: nil, context: nil, hints: nil)
else {
    FileHandle.standardError.write("그림을 못 읽었다: \(args[1])\n".data(using: .utf8)!)
    exit(1)
}
let width = CGFloat(cg.width), height = CGFloat(cg.height)

let request = VNRecognizeTextRequest()
request.recognitionLevel = .accurate
request.usesLanguageCorrection = false
try VNImageRequestHandler(cgImage: cg).perform([request])

for line in request.results ?? [] {
    guard let candidate = line.topCandidates(1).first else { continue }
    let text = candidate.string
    // 단어 경계로 자른다. 구두점은 단어에 붙이지 않는다 ("rain," → "rain").
    var index = text.startIndex
    while index < text.endIndex {
        guard text[index].isLetter else { index = text.index(after: index); continue }
        var end = index
        while end < text.endIndex, text[end].isLetter || text[end] == "’" || text[end] == "'" {
            end = text.index(after: end)
        }
        let word = String(text[index..<end])
        if words.contains(word.lowercased()),
           let box = try? candidate.boundingBox(for: index..<end)?.boundingBox {
            // Vision 은 정규 좌표에 왼쪽 아래 원점이다. 픽셀·왼쪽 위로 옮긴다.
            let x = box.minX * width, w = box.width * width
            let y = (1 - box.maxY) * height, h = box.height * height
            print(String(format: "%@ %.0f %.0f %.0f %.0f", word.lowercased(), x, y, w, h))
        }
        index = end
    }
}
