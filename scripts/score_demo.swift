// 데모 영상(remix_demo.py)의 배경 음악 — 이 파일이 악보이자 악기다.
//
// 사용:
//     swiftc -O -o score scripts/score_demo.swift
//     ./score <출력.wav> <길이(초)> 이름=초 ...
//
// 큐(이름=초)는 remix_demo.py 의 score_cues 가 만든다. 장면 경계와 장면 안의 사건
// (비가 칠해지는 순간, 책장이 넘어가는 순간, 결제가 끝나는 순간)이다. 화음과 음이 그 순간에 떨어진다.
//
// **직접 작곡하고 합성한다.** 제출물의 음악은 상업적 사용 근거가 있어야 하는데
// (docs/product/demo-video.md), 직접 만든 곡이면 증빙이 따로 필요 없다.
// 이전 판(사인파 3화음 드론 + 장면마다 벨)은 음악이 아니라 신호음처럼 들렸다. 여기서는
// 부드러운 펠트 피아노·패드·베이스·작은 종에 잔향을 입히고, 화음 진행을 편집에 맞춘다.
//
// 결과는 매번 같다 — 난수도 씨앗이 고정돼 있다.

import Foundation

let sampleRate = 48_000.0

// MARK: - 인자

func fail(_ message: String) -> Never {
    FileHandle.standardError.write(Data((message + "\n").utf8))
    exit(2)
}

let arguments = CommandLine.arguments
guard arguments.count >= 3, let total = Double(arguments[2]) else {
    fail("사용: score <출력.wav> <길이(초)> 이름=초 ...")
}
let outputPath = arguments[1]
var cueTable: [String: Double] = [:]
for argument in arguments.dropFirst(3) {
    let parts = argument.split(separator: "=", maxSplits: 1).map(String.init)
    guard parts.count == 2, let seconds = Double(parts[1]) else { fail("큐 형식이 틀렸다: \(argument)") }
    cueTable[parts[0]] = seconds
}
func cue(_ name: String) -> Double {
    guard let seconds = cueTable[name] else { fail("큐가 없다: \(name)") }
    return seconds
}

let frameCount = Int((total * sampleRate).rounded())

// MARK: - 음 이름

func key(_ name: String) -> Int {
    let base: [Character: Int] = ["C": 0, "D": 2, "E": 4, "F": 5, "G": 7, "A": 9, "B": 11]
    var chars = Array(name)
    var pitch = base[chars.removeFirst()]!
    if chars.first == "#" { pitch += 1; chars.removeFirst() }
    else if chars.first == "b" { pitch -= 1; chars.removeFirst() }
    return 12 * (Int(String(chars))! + 1) + pitch
}

func hertz(_ key: Int) -> Double { 440 * pow(2, Double(key - 69) / 12) }

struct Seed {
    var state: UInt64
    mutating func next() -> Double {
        state = state &* 6_364_136_223_846_793_005 &+ 1_442_695_040_888_963_407
        return Double(state >> 11) / Double(UInt64(1) << 53)
    }
}
var seed = Seed(state: 20_260_929)

// MARK: - 버스

/// 마른 소리와 잔향으로 보내는 소리. 악기는 모노로 만들고 여기에 자리를 잡아 놓는다.
var dryL = [Double](repeating: 0, count: frameCount)
var dryR = [Double](repeating: 0, count: frameCount)
var sendL = [Double](repeating: 0, count: frameCount)
var sendR = [Double](repeating: 0, count: frameCount)

/// 모노 음을 [start] 에 놓는다. 등전력 팬(-1 왼쪽 … 1 오른쪽), [reverb] 만큼 잔향으로 보낸다.
func place(_ tone: [Double], at start: Int, gain: Double, pan: Double, reverb: Double) {
    let count = min(tone.count, frameCount - start)
    guard count > 0 else { return }
    let angle = (pan + 1) * .pi / 4
    let left = gain * cos(angle), right = gain * sin(angle)
    for i in 0..<count {
        let s = tone[i]
        dryL[start + i] += s * left
        dryR[start + i] += s * right
        sendL[start + i] += s * left * reverb
        sendR[start + i] += s * right * reverb
    }
}

/// 두 겹으로 감쇠하는 사인파를 더한다 (빠르게 사라지는 머리 + 오래 남는 꼬리).
/// 사인을 매 샘플 부르지 않고 회전 점화식으로 만든다 — y[n] = 2cos(w)·y[n-1] − y[n-2].
func addPartial(_ tone: inout [Double], frequency: Double, phase: Double,
                head: Double, headT60: Double, tail: Double, tailT60: Double) {
    let w = 2 * Double.pi * frequency / sampleRate
    let c = 2 * cos(w)
    var y1 = sin(phase - w), y2 = sin(phase - 2 * w)
    var eh = head, et = tail
    let dh = exp(-6.907755 / (headT60 * sampleRate))
    let dt = exp(-6.907755 / (tailT60 * sampleRate))
    tone.withUnsafeMutableBufferPointer { out in
        for i in 0..<out.count {
            let y = c * y1 - y2
            y2 = y1
            y1 = y
            out[i] += (eh + et) * y
            eh *= dh
            et *= dt
        }
    }
}

func lowpass(_ tone: inout [Double], cutoff: Double) {
    let a = 1 - exp(-2 * Double.pi * cutoff / sampleRate)
    var s1 = 0.0, s2 = 0.0
    tone.withUnsafeMutableBufferPointer { out in
        for i in 0..<out.count {
            s1 += a * (out[i] - s1)
            s2 += a * (s1 - s2)
            out[i] = s2
        }
    }
}

/// 올림 코사인. 0 → 1.
func rise(_ x: Double) -> Double { x <= 0 ? 0 : x >= 1 ? 1 : 0.5 - 0.5 * cos(.pi * x) }

// MARK: - 악기

struct Strike { let time: Double; let key: Int; let velocity: Double }

/// 펠트 피아노. 두 줄이 아주 조금 어긋나게 울리고(맥놀이), 배음은 약간 비정수배다.
/// 펠트가 고음을 먹으므로 세게 칠수록 밝아진다. 페달을 밟은 채라 음은 스스로 사라질 때까지 둔다.
func piano(_ strike: Strike) {
    let start = Int(strike.time * sampleRate)
    guard start >= 0, start < frameCount else { return }
    let note = Double(strike.key)
    let f0 = hertz(strike.key)
    let sustain = min(12.0, 9.0 * pow(2.0, -(note - 48) / 24))     // T60 — 낮은 음일수록 길다
    let length = min(frameCount - start, Int(min(8.0, sustain) * sampleRate))
    guard length > 0 else { return }
    var tone = [Double](repeating: 0, count: length)
    let stiffness = 0.00012 * pow(2.0, (note - 60) / 24)
    let felt = 1100 + 3000 * strike.velocity
    let unison = pow(2.0, 0.6 / 1200)

    var k = 1.0
    while k <= 16 {
        let f = k * f0 * (1 + stiffness * k * k).squareRoot()
        if f > 12_000 { break }
        var amp = pow(k, -1.15) * exp(-(k - 1) * (0.42 - 0.22 * strike.velocity))
        amp /= 1 + (f / felt) * (f / felt)
        let fall = 1 + 0.45 * (k - 1)
        for detune in [unison, 1 / unison] {
            addPartial(&tone, frequency: f * detune, phase: seed.next() * 2 * .pi,
                       head: 0.65 * amp * 0.5, headT60: sustain / 6 / fall,
                       tail: 0.35 * amp * 0.5, tailT60: sustain / fall)
        }
        k += 1
    }

    // 망치가 줄에 닿는 소리 — 아주 짧은 저역 잡음.
    let hammer = min(length, Int(0.012 * sampleRate))
    var thump = 0.0
    for i in 0..<hammer {
        thump += 0.15 * ((seed.next() * 2 - 1) - thump)
        tone[i] += 0.012 * strike.velocity * thump * (1 - Double(i) / Double(hammer))
    }
    let attack = Double(Int(0.006 * sampleRate))
    let fade = Int(0.05 * sampleRate)
    for i in 0..<length {
        var e = rise(Double(i) / attack)
        if i > length - fade { e *= Double(length - i) / Double(fade) }
        tone[i] *= e
    }
    place(tone, at: start, gain: pow(strike.velocity, 1.4) * 0.9,
          pan: max(-0.5, min(0.5, (note - 64) / 30)), reverb: 0.45)
}

/// 작은 종. 비정수배 배음이 빨리 사라진다 — "켜지는" 순간에만 쓴다.
func bell(_ strike: Strike) {
    let start = Int(strike.time * sampleRate)
    guard start >= 0, start < frameCount else { return }
    let length = min(frameCount - start, Int(3.5 * sampleRate))
    var tone = [Double](repeating: 0, count: length)
    let f0 = hertz(strike.key)
    let partials: [(ratio: Double, amp: Double, t60: Double)] = [
        (1.0, 1.0, 3.2), (2.0, 0.35, 1.9), (3.01, 0.18, 1.2), (4.16, 0.10, 0.8), (5.43, 0.05, 0.5),
    ]
    for p in partials where f0 * p.ratio < 16_000 {
        addPartial(&tone, frequency: f0 * p.ratio, phase: seed.next() * 2 * .pi,
                   head: 0, headT60: 1, tail: p.amp, tailT60: p.t60)
    }
    let attack = 0.002 * sampleRate
    for i in 0..<length { tone[i] *= rise(Double(i) / attack) }
    place(tone, at: start, gain: strike.velocity * 0.22, pan: 0.25, reverb: 0.7)
}

/// 패드 한 음. 셋이 조금씩 어긋난 톱니파를 부드럽게 깎은 것 — 화음이 바뀔 때 겹쳐서 넘어간다.
func pad(_ key: Int, from start: Double, to end: Double, level: Double, pan: Double) {
    let attack = 1.4, release = 2.0
    let first = Int(start * sampleRate)
    let last = min(frameCount, Int((end + release) * sampleRate))
    guard last > first else { return }
    var tone = [Double](repeating: 0, count: last - first)
    let f0 = hertz(key)
    let harmonics = max(1, min(10, Int(3500 / f0)))
    for cents in [-7.0, 0.0, 7.0] {
        let f = f0 * pow(2, cents / 1200)
        for h in 1...harmonics {
            let amp = exp(-0.35 * Double(h - 1)) / Double(h) / 3
            addPartial(&tone, frequency: f * Double(h), phase: seed.next() * 2 * .pi,
                       head: 0, headT60: 1, tail: amp, tailT60: 1e9)
        }
    }
    let hold = end - start
    let breath = seed.next() * 2 * .pi
    for i in 0..<tone.count {
        let t = Double(i) / sampleRate
        var e = rise(t / attack)
        if t > hold { e *= 1 - rise((t - hold) / release) }
        e *= 0.88 + 0.12 * sin(2 * .pi * 0.07 * (start + t) + breath)
        tone[i] *= e
    }
    lowpass(&tone, cutoff: 1500)
    place(tone, at: first, gain: level, pan: pan, reverb: 0.6)
}

/// 베이스 한 음. 바닥만 받친다.
func bass(_ key: Int, from start: Double, to end: Double, level: Double) {
    let attack = 0.9, release = 1.6
    let first = Int(start * sampleRate)
    let last = min(frameCount, Int((end + release) * sampleRate))
    guard last > first else { return }
    var tone = [Double](repeating: 0, count: last - first)
    let f0 = hertz(key)
    for (h, amp) in [(1.0, 1.0), (2.0, 0.22), (3.0, 0.06)] {
        addPartial(&tone, frequency: f0 * h, phase: seed.next() * 2 * .pi,
                   head: 0, headT60: 1, tail: amp, tailT60: 1e9)
    }
    let hold = end - start
    for i in 0..<tone.count {
        let t = Double(i) / sampleRate
        var e = rise(t / attack)
        if t > hold { e *= 1 - rise((t - hold) / release) }
        tone[i] *= e
    }
    place(tone, at: first, gain: level, pan: 0, reverb: 0.12)
}

// MARK: - 악보

let hook = cue("hook"), match = cue("match"), glow = cue("glow")
let turn = cue("turn"), turn1 = cue("turn1"), turn2 = cue("turn2")
let sky = (1...7).map { cue("sky\($0)") }
let widgets = cue("widgets"), android = cue("android"), open = cue("open")
let surfaces = cue("surfaces"), sync = cue("sync"), desktop = cue("desktop")
let shared = cue("shared")
let purchase = cue("purchase"), sheet = cue("sheet"), buy = cue("buy"), paid = cue("paid")
let shelf = cue("shelf"), page = cue("page"), end = cue("end")

/// 화음. 다음 화음이 올 때까지 이어진다. D 장조 —
/// D | Bm G | Em A7sus A | D Bm G A | D G D | Bm G Em | Asus A | G Em A7sus D G | D
struct Chord { let time: Double; let bass: [String]; let pad: [String] }
let chords: [Chord] = [
    Chord(time: hook, bass: ["D2"], pad: ["A3", "C#4", "E4", "F#4"]),              // Dmaj9 — 질문
    Chord(time: match, bass: ["B1"], pad: ["A3", "C#4", "D4", "F#4"]),             // Bm9 — 오늘의 비
    Chord(time: match + 4.5, bass: ["G2"], pad: ["B3", "D4", "F#4", "A4"]),        // Gmaj9
    Chord(time: turn, bass: ["E2"], pad: ["G3", "B3", "D4", "F#4"]),               // Em9 — 되넘기기
    Chord(time: turn + 4.0, bass: ["A2"], pad: ["G3", "D4", "E4", "A4"]),          // A7sus4
    Chord(time: turn + 6.0, bass: ["A2"], pad: ["A3", "C#4", "E4", "F#4"]),        // A6
    Chord(time: sky[0], bass: ["D2"], pad: ["A3", "D4", "F#4", "A4"]),             // D — 일곱 하늘
    Chord(time: sky[2], bass: ["B1"], pad: ["B3", "D4", "F#4", "A4"]),             // Bm7
    Chord(time: sky[4], bass: ["G2"], pad: ["B3", "D4", "G4", "B4"]),              // G
    Chord(time: sky[6], bass: ["A2"], pad: ["A3", "C#4", "E4", "A4"]),             // A
    Chord(time: widgets, bass: ["D2"], pad: ["A3", "C#4", "E4", "F#4"]),           // Dmaj9 — 홈 화면
    Chord(time: android, bass: ["G2"], pad: ["B3", "D4", "F#4", "A4"]),            // Gmaj9
    Chord(time: open, bass: ["D2"], pad: ["A3", "D4", "E4", "F#4"]),               // Dadd9 — 앱이 열린다
    Chord(time: surfaces, bass: ["B1"], pad: ["A3", "C#4", "D4", "F#4"]),          // Bm9 — 한 코어
    Chord(time: sync, bass: ["G2"], pad: ["B3", "D4", "F#4", "A4"]),               // Gmaj9 — 둘이 함께 넘어간다
    Chord(time: desktop, bass: ["E2"], pad: ["G3", "B3", "D4", "F#4"]),            // Em9 — 셋째 화면
    Chord(time: shared, bass: ["A2"], pad: ["A3", "D4", "E4", "A4"]),              // Asus4 — 한 번 공유
    Chord(time: shared + 2.0, bass: ["A2"], pad: ["A3", "C#4", "E4", "A4"]),       // A
    Chord(time: purchase, bass: ["G2"], pad: ["B3", "D4", "G4", "B4"]),            // G — 구매
    Chord(time: sheet, bass: ["E2"], pad: ["G3", "B3", "D4", "G4"]),               // Em7 — 시트가 올라온다
    Chord(time: buy - 0.6, bass: ["A2"], pad: ["G3", "D4", "E4", "A4"]),           // A7sus4 — 누르기 직전
    Chord(time: paid, bass: ["D2", "D3"], pad: ["A3", "C#4", "E4", "F#4", "A4"]),  // Dmaj9 — 결제 완료
    Chord(time: page, bass: ["G2"], pad: ["B3", "D4", "F#4", "A4"]),               // Gmaj9 — 다시 읽기
    Chord(time: end, bass: ["D2", "D3"], pad: ["A3", "D4", "E4", "F#4"]),          // Dadd9 — IV → I 로 닫는다
]

/// 곡의 세기. 패드와 베이스가 따른다 — 일곱 하늘에서 한 번, 결제가 끝나는 순간에 한 번 부푼다.
let intensity: [(time: Double, level: Double)] = [
    (hook, 0.75), (sky[0], 0.95), (widgets, 0.8), (surfaces, 0.9),
    (purchase, 0.85), (paid, 1.05), (end, 0.9), (total, 0.8),
]
func level(at t: Double) -> Double {
    for (a, b) in zip(intensity, intensity.dropFirst()) where t <= b.time {
        return a.level + (b.level - a.level) * max(0, (t - a.time) / max(b.time - a.time, 1e-9))
    }
    return intensity.last!.level
}

var strikes: [Strike] = []
var bells: [Strike] = []
func p(_ time: Double, _ name: String, _ velocity: Double) {
    strikes.append(Strike(time: time, key: key(name), velocity: velocity))
}
func b(_ time: Double, _ name: String, _ velocity: Double) {
    bells.append(Strike(time: time, key: key(name), velocity: velocity))
}

// 01 — 하늘이 다음 페이지를 고른다면. 드문드문, 들릴 듯 말 듯.
p(hook + 0.40, "F#4", 0.40); p(hook + 1.20, "A4", 0.36); p(hook + 2.00, "C#5", 0.40)
p(hook + 2.90, "E5", 0.46); p(hook + 4.40, "D5", 0.34); p(hook + 5.30, "A4", 0.28)

// 02 — 오늘의 비. 날씨 줄과 발췌문의 rain 이 켜지는 순간 종이 울린다.
p(match + 0.05, "B2", 0.30); p(match + 0.30, "F#5", 0.42)
p(match + 1.10, "E5", 0.34); p(match + 1.90, "D5", 0.32)
p(glow, "C#6", 0.24); b(glow, "F#6", 0.30); b(glow + 0.05, "C#7", 0.12)
p(match + 4.5, "G2", 0.28); p(match + 4.75, "B4", 0.34); p(match + 5.6, "D5", 0.30)
p(match + 6.5, "F#5", 0.34); p(match + 7.5, "A5", 0.30); p(match + 8.4, "E5", 0.24)

// 03 — 되넘기기. 책장이 넘어갈 때마다 위에서 아래로 쓸어내린다.
p(turn + 0.05, "E3", 0.28)
for (i, name) in ["B5", "A5", "F#5"].enumerated() { p(turn1 + Double(i) * 0.07, name, 0.32 - Double(i) * 0.04) }
p(turn1 + 1.2, "G5", 0.30); p(turn1 + 2.3, "F#5", 0.28)
p(turn + 4.05, "A2", 0.26)
for (i, name) in ["A5", "G5", "E5"].enumerated() { p(turn2 + Double(i) * 0.07, name, 0.32 - Double(i) * 0.04) }
p(turn + 6.05, "C#5", 0.34); p(turn + 7.0, "E5", 0.26)

// 04 — 일곱 하늘. 컷마다 한 음씩 올라가고, 컷 사이에 낮은 메아리가 한 번.
let rising = ["D5", "F#5", "A5", "B5", "D6", "E6", "F#6"]
let echoes = ["A4", "D5", "F#5", "F#5", "B5", "B5", "C#6"]
for i in 0..<7 {
    p(sky[i], rising[i], 0.40 + Double(i) * 0.012)
    let next = i < 6 ? sky[i + 1] : widgets
    p((sky[i] + next) / 2, echoes[i], 0.20)
}
p(sky[0] + 0.02, "D3", 0.28); p(sky[2] + 0.02, "B2", 0.26)
p(sky[4] + 0.02, "G2", 0.26); p(sky[6] + 0.02, "A2", 0.28)

// 05 — 홈 화면. iOS 에서 Android 로 넘어가면 화음이 바뀌고, 위젯을 누르면 두 음이 함께 열린다.
p(widgets + 0.05, "D3", 0.26); p(widgets + 0.4, "A4", 0.32)
p(widgets + 1.6, "F#5", 0.34); p(widgets + 2.8, "E5", 0.28)
p(android + 0.05, "G2", 0.26); p(android + 0.3, "B4", 0.30); p(android + 1.5, "D5", 0.30)
p(open, "F#5", 0.36); p(open + 0.02, "A5", 0.30)
p(open + 1.4, "E5", 0.28); p(open + 2.6, "D5", 0.26); p(open + 3.3, "C#5", 0.24)

// 06 — 한 코어, 다섯 화면. 두 폰이 같은 순간 넘어가면 옥타브 유니즌, 데스크톱이 들어오면 세 음.
p(surfaces + 0.02, "B1", 0.22); p(surfaces + 0.02, "B2", 0.22)
p(surfaces + 0.3, "F#5", 0.34); p(surfaces + 1.2, "E5", 0.30)
p(sync, "D5", 0.38); p(sync + 0.005, "D6", 0.30); p(sync + 0.02, "G2", 0.26)
p(sync + 1.2, "B5", 0.30); p(sync + 2.4, "A5", 0.28); p(sync + 3.5, "F#5", 0.26)
p(desktop, "G5", 0.32); p(desktop + 0.12, "B5", 0.30); p(desktop + 0.24, "E6", 0.28)
p(desktop + 0.02, "E2", 0.24)

// 07 — 한 번 공유, 어디서나. 걸린 4도가 3도로 풀린다.
p(shared + 0.05, "A2", 0.28); p(shared + 0.3, "E5", 0.32); p(shared + 1.2, "D5", 0.32)
p(shared + 2.0, "C#5", 0.36); p(shared + 3.1, "E5", 0.26)

// 08 — 구매. 시트가 오르면 기대하고, 결제가 끝나면 풀리고, 서가가 열리면 반짝인다.
p(purchase + 0.05, "G2", 0.28); p(purchase + 0.3, "D5", 0.30); p(purchase + 1.1, "B4", 0.26)
p(sheet, "G5", 0.30); p(sheet + 0.02, "E2", 0.24); p(sheet + 1.1, "B5", 0.24)
p(buy - 0.6, "A2", 0.28); p(buy - 0.55, "E5", 0.28); p(buy, "D5", 0.30)
p(paid, "D2", 0.34); p(paid + 0.01, "D3", 0.32); p(paid + 0.02, "F#5", 0.40); p(paid + 0.10, "A5", 0.34)
for (i, name) in ["D6", "F#6", "A6", "C#7"].enumerated() {
    b(shelf + Double(i) * 0.14, name, 0.30 - Double(i) * 0.04)
    p(shelf + Double(i) * 0.14, name, 0.20 - Double(i) * 0.03)
}
p(shelf + 1.2, "E5", 0.28); p(shelf + 2.3, "F#5", 0.26)
p(page, "G2", 0.28); p(page + 0.05, "B5", 0.30); p(page + 0.9, "A5", 0.26)

// 09 — 고개를 들고, 거슬러 읽기. 버금딸림에서 으뜸으로 조용히 닫는다.
p(end, "D2", 0.30); p(end + 0.01, "D3", 0.30); p(end + 0.1, "F#5", 0.34); p(end + 0.8, "A5", 0.30)
p(end + 1.7, "E6", 0.24); p(end + 2.6, "D6", 0.22); p(end + 3.6, "A5", 0.18)

// MARK: - 연주

for (i, chord) in chords.enumerated() {
    let until = i + 1 < chords.count ? chords[i + 1].time : total
    let strength = level(at: chord.time)
    let spread: [Double] = chord.pad.count == 5 ? [-0.45, -0.22, 0, 0.22, 0.45] : [-0.4, -0.13, 0.13, 0.4]
    for (j, name) in chord.pad.enumerated() {
        pad(key(name), from: chord.time, to: until, level: 0.026 * strength, pan: spread[j])
    }
    for name in chord.bass { bass(key(name), from: chord.time, to: until, level: 0.027 * strength) }
}
for strike in strikes { piano(strike) }
for strike in bells { bell(strike) }

// MARK: - 잔향 (Freeverb)

struct Comb {
    var buffer: [Double]
    var index = 0
    var store = 0.0
    init(_ size: Int) { buffer = [Double](repeating: 0, count: size) }
    mutating func process(_ x: Double, feedback: Double, damp: Double) -> Double {
        let out = buffer[index]
        store = out * (1 - damp) + store * damp
        buffer[index] = x + store * feedback
        index += 1
        if index == buffer.count { index = 0 }
        return out
    }
}

struct Allpass {
    var buffer: [Double]
    var index = 0
    init(_ size: Int) { buffer = [Double](repeating: 0, count: size) }
    mutating func process(_ x: Double) -> Double {
        let delayed = buffer[index]
        buffer[index] = x + delayed * 0.5
        index += 1
        if index == buffer.count { index = 0 }
        return delayed - x
    }
}

func freeverb(room: Double, damp: Double, predelay: Double) -> ([Double], [Double]) {
    let scale = sampleRate / 44_100
    let combSizes = [1116, 1188, 1277, 1356, 1422, 1491, 1557, 1617]
    let allpassSizes = [556, 441, 341, 225]
    let spread = 23
    var combsL = combSizes.map { Comb(Int(Double($0) * scale)) }
    var combsR = combSizes.map { Comb(Int(Double($0 + spread) * scale)) }
    var allpassL = allpassSizes.map { Allpass(Int(Double($0) * scale)) }
    var allpassR = allpassSizes.map { Allpass(Int(Double($0 + spread) * scale)) }
    let feedback = room * 0.28 + 0.7
    let damping = damp * 0.4
    let delay = Int(predelay * sampleRate)
    var outL = [Double](repeating: 0, count: frameCount)
    var outR = [Double](repeating: 0, count: frameCount)
    for i in 0..<frameCount {
        let j = i - delay
        let input = j < 0 ? 0 : (sendL[j] + sendR[j]) * 0.015
        var l = 0.0, r = 0.0
        for c in 0..<combsL.count {
            l += combsL[c].process(input, feedback: feedback, damp: damping)
            r += combsR[c].process(input, feedback: feedback, damp: damping)
        }
        for a in 0..<allpassL.count {
            l = allpassL[a].process(l)
            r = allpassR[a].process(r)
        }
        outL[i] = l
        outR[i] = r
    }
    return (outL, outR)
}

let (wetL, wetR) = freeverb(room: 0.88, damp: 0.35, predelay: 0.022)

// MARK: - 마스터

var mixL = [Double](repeating: 0, count: frameCount)
var mixR = [Double](repeating: 0, count: frameCount)
let fadeIn = 0.5 * sampleRate, fadeOut = 2.5 * sampleRate
let lowCut = 1 - exp(-2 * Double.pi * 30 / sampleRate)       // 30 Hz 아래는 스피커가 못 낸다
var dcL = 0.0, dcR = 0.0
var peak = 0.0
for i in 0..<frameCount {
    var l = dryL[i] + 0.8 * wetL[i]
    var r = dryR[i] + 0.8 * wetR[i]
    dcL += lowCut * (l - dcL); l -= dcL
    dcR += lowCut * (r - dcR); r -= dcR
    let g = rise(Double(i) / fadeIn) * rise(Double(frameCount - i) / fadeOut)
    mixL[i] = l * g
    mixR[i] = r * g
    peak = max(peak, abs(mixL[i]), abs(mixR[i]))
}
// 최종 크기는 ffmpeg loudnorm 이 맞춘다. 여기서는 넘치지 않게만 둔다 (−3 dBFS).
let normalize = peak > 0 ? 0.707 / peak : 1

// MARK: - WAV (32-bit float, 스테레오)

var data = Data()
func append32(_ value: UInt32) { withUnsafeBytes(of: value.littleEndian) { data.append(contentsOf: $0) } }
func append16(_ value: UInt16) { withUnsafeBytes(of: value.littleEndian) { data.append(contentsOf: $0) } }
let payload = frameCount * 2 * 4
data.append(Data("RIFF".utf8)); append32(UInt32(36 + payload)); data.append(Data("WAVE".utf8))
data.append(Data("fmt ".utf8)); append32(16); append16(3); append16(2)
append32(UInt32(sampleRate)); append32(UInt32(sampleRate) * 8); append16(8); append16(32)
data.append(Data("data".utf8)); append32(UInt32(payload))
var interleaved = [Float](repeating: 0, count: frameCount * 2)
for i in 0..<frameCount {
    interleaved[2 * i] = Float(mixL[i] * normalize)
    interleaved[2 * i + 1] = Float(mixR[i] * normalize)
}
interleaved.withUnsafeBufferPointer { data.append(Data(buffer: $0)) }
do {
    try data.write(to: URL(fileURLWithPath: outputPath))
} catch {
    fail("쓰지 못했다: \(outputPath) — \(error)")
}
print(String(format: "  악보 %d음 · 종 %d · 화음 %d · %.2f초 · 정점 %.3f → −3 dBFS",
             strikes.count, bells.count, chords.count, total, peak))
