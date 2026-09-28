#!/usr/bin/env python3
"""Almanac 데모를 원본 촬영본으로 다시 편집한다.

기존 105초 마스터는 앱과 영상 배경이 같은 종이색이라 화면의 경계가 사라졌다.
이 버전은 앱 화면을 어두운 녹갈색 무대 위의 밝은 카드로 올리고, 긴 설명을 9개의
짧은 장면으로 다시 조직한다. 기존 컷은 덮어쓰지 않는다.

사용:
    python3 scripts/remix_demo.py \
        /Users/jeonbyeongseon/almanac-footage \
        build/demo-remix/almanac-demo-remix.mp4

    # 화면은 그대로 두고 음악만 다시 입힌다 (영상 스트림은 복사만 한다)
    python3 scripts/remix_demo.py rescore <렌더된 영상.mp4> <출력.mp4>

음악은 scripts/score_demo.swift 가 작곡·합성한다. 장면 전환과 장면 안의 사건에 맞춘 곡이다.

의존성: ffmpeg, ffprobe, swift/swiftc (render_text.swift, score_demo.swift)
"""

from __future__ import annotations

import json
import os
import subprocess
import sys
import tempfile
from dataclasses import dataclass
from pathlib import Path


# Swift/CoreText가 기본적으로 쓰는 사용자 캐시는 작업 샌드박스 밖에 있다. 생성물과
# 무관한 컴파일 캐시만 쓰기 가능한 임시 경로로 돌린다.
os.environ.setdefault("CLANG_MODULE_CACHE_PATH", "/private/tmp/almanac-swift-module-cache")
os.environ.setdefault("SWIFT_MODULECACHE_PATH", "/private/tmp/almanac-swift-module-cache")


ROOT = Path(__file__).resolve().parents[1]
FONT = ROOT / "composeApp/src/commonMain/composeResources/font/crimson_text.ttf"
TEXT_RENDERER = ROOT / "scripts/render_text.swift"
SCORE = ROOT / "scripts/score_demo.swift"

#: 음악의 크기. 내레이션이 없어 음악이 유일한 소리다 — 웹 영상에 맞춰 −16 LUFS, 정점 −1.5 dBTP.
#: 이전 판은 −24.9 LUFS 라 스피커를 한참 키워야 들렸다.
LOUDNESS = -16.0
TRUE_PEAK = -1.5

#: 위젯 장면에서 iOS 홈 화면이 머무는 길이 (그다음이 Android).
WIDGET_IOS_SECONDS = 3.5

#: KMP 분할 컷의 길이. 끝 SPLIT_TRIO 초는 세 화면이다.
KMP_LENGTH = 9.0

#: 일곱 하늘 — 음악이 컷마다 한 음씩 올라간다.
SKY_SCENES = ("04a-rain", "04b-snow", "04c-fog", "04d-wind", "04e-dawn", "04f-day", "04g-dusk")

#: 음악이 맞춰 치는 장면 안의 사건. (장면 이름, 장면 시작에서 몇 초)
#:
#: 원본 컷의 시각에서 계산할 수 있는 것은 score_cues 가 계산한다. 여기 적은 것은 녹화 안의
#: 손동작처럼 코드에 적혀 있지 않은 것뿐이고, 2026-09-29 에 v2 렌더에서 장면 전환 검출과
#: 프레임으로 쟀다. 원본을 다시 찍으면 다시 잰다.
MEASURED_CUES = {
    "turn1": ("03-turn", 1.18),        # 첫 넘김 — 104 → 118 years ago
    "turn2": ("03-turn", 5.35),        # 둘째 넘김 — 118 → 179 years ago
    "open": ("05-widgets", 6.22),      # Android 위젯을 누르고 앱이 열린다
    "sheet": ("08-purchase", 1.83),    # Google Play 시트가 올라온다
    "buy": ("08-purchase", 4.17),      # 1-tap buy
    "paid": ("08-purchase", 5.20),     # Payment successful ✓
    "page": ("08-purchase", 9.35),     # Start reading → 페이지로 돌아온다
}

# 기존 촬영본의 기기별 시스템 영역, KMP 정렬, 구매 조각 타이밍은 이미
# cut_demo.py 에서 검증되어 있다. 그 값만 재사용하고, 기존에 태워 둔 자막은 쓰지 않는다.
sys.path.insert(0, str(ROOT / "scripts"))
import cut_demo as legacy  # noqa: E402

OUT_W, OUT_H, FPS = 1920, 1080, 60
PAPER = "0xFBF9F4"
LIGHT = "#F6F0E7"
MUTED = "#C9B89F"
ACCENT = "#D1A968"


@dataclass(frozen=True)
class Caption:
    text: str
    size: int
    color: str
    x: int
    y: int
    delay: float = 0.10


@dataclass(frozen=True)
class Scene:
    name: str
    source: str
    duration: float
    start: float
    speed: float
    crop: tuple[int, int, int, int]
    card: tuple[int, int, int, int]
    captions: tuple[Caption, ...]
    colors: tuple[str, str, str]
    still: bool = False
    cue: bool = True
    inset: tuple[int, int, int, int] = (0, 0, 0, 0)


def run(args: list[str]) -> None:
    proc = subprocess.run(args, capture_output=True, text=True)
    if proc.returncode:
        sys.stderr.write(proc.stderr[-5000:])
        raise SystemExit(f"명령 실패: {' '.join(args[:8])} …")


def duration(path: Path) -> float:
    proc = subprocess.run(
        ["ffprobe", "-v", "error", "-show_entries", "format=duration",
         "-of", "csv=p=0", str(path)],
        capture_output=True, text=True, check=True,
    )
    return float(proc.stdout.strip())


def render_text(caption: Caption, out: Path) -> None:
    stamp = out.with_suffix(out.suffix + ".txt")
    key = f"{caption.text}\n{caption.size}\n{caption.color}\n"
    if out.exists() and stamp.exists() and stamp.read_text(encoding="utf-8") == key:
        return
    out.unlink(missing_ok=True)
    run([
        "swift", str(TEXT_RENDERER), str(FONT), str(caption.size),
        caption.color, str(out), caption.text,
    ])
    stamp.write_text(key, encoding="utf-8")


def is_fresh(target: Path, sources: list[Path]) -> bool:
    return (target.exists()
            and target.stat().st_mtime >= max(source.stat().st_mtime for source in sources))


def make_clean_purchase(footage: Path, build: Path) -> Path:
    """구매 증명 다섯 조각을 기존의 화면 내 자막 없이 다시 잇는다."""
    target = build / "clean-purchase.mp4"
    pieces = legacy.SEQUENCES["08-purchase"]
    sources = [footage / piece[0] for piece in pieces]
    if is_fresh(target, sources):
        return target

    with tempfile.TemporaryDirectory(prefix="almanac-purchase-", dir="/private/tmp") as tmp:
        tmp_path = Path(tmp)
        parts: list[Path] = []
        for i, piece in enumerate(pieces):
            rel, start, length, move = piece[:4]
            source = footage / rel
            part = tmp_path / f"part-{i}.mp4"
            legacy.prepare_cut(
                str(source), str(part), start=start, length=length,
                label=None, cache_dir=tmp, subtitle=None, move=move,
            )
            parts.append(part)
        listing = tmp_path / "parts.txt"
        listing.write_text(
            "".join(f"file '{part}'\n" for part in parts), encoding="utf-8")
        run([
            "ffmpeg", "-v", "error", "-f", "concat", "-safe", "0",
            "-i", str(listing), "-c", "copy", "-movflags", "+faststart",
            "-y", str(target),
        ])
    return target


def make_clean_widgets(footage: Path, build: Path) -> Path:
    """현지화된 홈 화면은 버리고 iOS/Android 위젯 영역만 이어 붙인다."""
    target = build / "clean-widgets.mp4"
    ios = footage / "raw-ios/06-widget-ios.png"
    android = footage / "raw-android/06-widget-android.mp4"
    if is_fresh(target, [ios, android]):
        return target

    run([
        "ffmpeg", "-v", "error",
        "-loop", "1", "-framerate", str(FPS), "-t", str(WIDGET_IOS_SECONDS + 1), "-i", str(ios),
        "-i", str(android),
        "-filter_complex",
        (f"[0:v]crop=1080:520:60:170,fps=60,trim=duration={WIDGET_IOS_SECONDS},"
         "setpts=PTS-STARTPTS[ios];"
         "[1:v]fps=60,tpad=stop_mode=clone:stop_duration=10,"
         "trim=duration=6.5,setpts=PTS-STARTPTS,crop=1080:520:0:130[android];"
         "[ios][android]concat=n=2:v=1:a=0,format=yuv420p[out]"),
        "-map", "[out]", "-an", "-r", str(FPS),
        "-c:v", "libx264", "-preset", "medium", "-crf", "16",
        "-color_primaries", "bt709", "-color_trc", "bt709",
        "-colorspace", "bt709", "-movflags", "+faststart", "-y", str(target),
    ])
    return target


def make_clean_kmp(footage: Path, build: Path) -> Path:
    """Android/iPhone/Desktop 라벨을 살리고 기존 하단 자막은 뺀 KMP 컷."""
    target = build / "clean-kmp.mp4"
    phones = [
        (str(footage / rel), turn_at, label)
        for rel, turn_at, label in legacy.SPLIT_SOURCES
    ]
    desktop = str(footage / legacy.SPLIT_DESKTOP[0])
    sources = [Path(src) for src, _, _ in phones] + [Path(desktop)]
    if is_fresh(target, sources):
        return target

    duo_length = KMP_LENGTH - legacy.SPLIT_TRIO
    with tempfile.TemporaryDirectory(prefix="almanac-kmp-", dir="/private/tmp") as tmp:
        widths = [
            int(round(legacy.points(src) * legacy.SPLIT_SCALE / 2) * 2)
            for src, _, _ in phones
        ]
        x = (legacy.OUT_W - sum(widths)
             - legacy.SPLIT_GAP * (len(phones) - 1)) // 2
        duo = []
        for (src, turn_at, label), width in zip(phones, widths):
            duo.append(dict(
                src=src,
                start=max(turn_at - legacy.SPLIT_TURN_AT, 0.0),
                x=x, y=legacy.SPLIT_TOP, w=width, h=legacy.SPLIT_PHONE_H,
                label=label,
                label_y=legacy.SPLIT_TOP + legacy.SPLIT_PHONE_H + 14,
            ))
            x += width + legacy.SPLIT_GAP
        legacy.align_tops(duo, legacy.SPLIT_TOP, legacy.SPLIT_PHONE_H)
        duo_dst = Path(tmp) / "duo.mp4"
        legacy.compose(
            duo, duo_length + legacy.TRIO_FADE, None, str(duo_dst), tmp)

        order = [phones[0], (desktop, None, legacy.SPLIT_DESKTOP[1]), phones[1]]
        widths = [
            int(round(legacy.points(src) * legacy.TRIO_SCALE / 2) * 2)
            for src, _, _ in order
        ]
        x = (legacy.OUT_W - sum(widths)
             - legacy.TRIO_GAP * (len(order) - 1)) // 2
        trio = []
        for (src, turn_at, label), width in zip(order, widths):
            is_desktop = turn_at is None
            trio.append(dict(
                src=src, x=x, w=width, label=label,
                start=(0.0 if is_desktop else
                       max(turn_at - legacy.SPLIT_TURN_AT, 0.0) + duo_length),
                label_y=legacy.SPLIT_TOP + legacy.TRIO_H + 14,
            ))
            x += width + legacy.TRIO_GAP
        legacy.align_tops(trio, legacy.SPLIT_TOP, legacy.TRIO_H)
        trio_dst = Path(tmp) / "trio.mp4"
        legacy.compose(trio, legacy.SPLIT_TRIO, None, str(trio_dst), tmp)
        run([
            "ffmpeg", "-v", "error", "-i", str(duo_dst), "-i", str(trio_dst),
            "-filter_complex",
            (f"[0:v][1:v]xfade=transition=fade:duration={legacy.TRIO_FADE}:"
             f"offset={duo_length},format=yuv420p"),
            "-an", "-c:v", "libx264", "-preset", "medium", "-crf", "16",
            "-movflags", "+faststart", "-y", str(target),
        ])
    return target


def source_args(scene: Scene, source: Path) -> list[str]:
    if scene.still or source.suffix.lower() in {".png", ".jpg", ".jpeg"}:
        return ["-loop", "1", "-framerate", str(FPS), "-t", str(scene.duration + 1),
                "-i", str(source)]
    return ["-i", str(source)]


def normalize_source(scene: Scene, source: Path, build: Path) -> Path:
    """이어붙인 기존 컷을 하나의 일정한 H.264/BT.709 스트림으로 만든다.

    #6·#7·#8은 서로 다른 기기와 정지화면을 concat한 파일이라 구간 경계에서 H.264
    색상 메타데이터가 바뀐다. 그 파일에 바로 속도 조절 필터를 걸면 ffmpeg가 필터를
    재초기화하면서 경계 직후의 정지 구간을 건너뛸 수 있다. 한 번 정규화하면 실제
    타임라인은 그대로면서 이후 필터가 끊기지 않는다.
    """
    if scene.still or source.suffix.lower() in {".png", ".jpg", ".jpeg"}:
        return source
    normalized = build / f"{scene.name}-normalized.mp4"
    if normalized.exists() and normalized.stat().st_mtime >= source.stat().st_mtime:
        return normalized
    run([
        "ffmpeg", "-v", "error", "-i", str(source), "-an",
        "-r", str(FPS), "-fps_mode", "cfr", "-pix_fmt", "yuv420p",
        "-c:v", "libx264", "-preset", "fast", "-crf", "14",
        "-color_primaries", "bt709", "-color_trc", "bt709",
        "-colorspace", "bt709", "-movflags", "+faststart", "-y", str(normalized),
    ])
    return normalized


def render_scene(scene: Scene, footage: Path, build: Path) -> Path:
    source = (build / scene.source.removeprefix("@build/")
              if scene.source.startswith("@build/")
              else footage / scene.source)
    if not source.exists():
        raise SystemExit(f"원본이 없다: {source}")
    source = normalize_source(scene, source, build)

    target = build / f"{scene.name}.mp4"
    text_paths: list[Path] = []
    for i, caption in enumerate(scene.captions):
        path = build / f"{scene.name}-text-{i}.png"
        render_text(caption, path)
        text_paths.append(path)

    cx, cy, cw, ch = scene.card
    crop_w, crop_h, crop_x, crop_y = scene.crop
    inset_left, inset_top, inset_right, inset_bottom = scene.inset
    content_w = cw - inset_left - inset_right
    content_h = ch - inset_top - inset_bottom
    frames = max(int(scene.duration * FPS), 1)
    c0, c1, c2 = (c.removeprefix("#") for c in scene.colors)

    inputs = source_args(scene, source)
    for text_path in text_paths:
        inputs += ["-loop", "1", "-framerate", str(FPS),
                   "-t", str(scene.duration + 1), "-i", str(text_path)]

    # 움직이는 어두운 무대. 종이색 앱 카드가 한눈에 분리되도록 명도 차를 크게 둔다.
    filters = [
        (f"gradients=s={OUT_W}x{OUT_H}:r={FPS}:c0=0x{c0}:c1=0x{c1}:c2=0x{c2}:"
         f"nb_colors=3:x0=0:y0=0:x1={OUT_W}:y1={OUT_H}:"
         f"d={scene.duration}:speed=0.0015,"
         "drawbox=x=112:y=100:w=5:h=880:color=0xD1A968@0.70:t=fill[bg]")
    ]

    if scene.still or source.suffix.lower() in {".png", ".jpg", ".jpeg"}:
        head = "[0:v]fps=60,setpts=PTS-STARTPTS,"
    else:
        head = (f"[0:v]trim=start={scene.start},setpts=(PTS-STARTPTS)/{scene.speed},"
                f"fps={FPS},tpad=stop_mode=clone:stop_duration=20,")

    # 아주 느린 1.8% 푸시인. 정지 화면도 영상처럼 호흡하지만 앱 글자는 흔들리지 않는다.
    filters.append(
        f"{head}trim=duration={scene.duration},setpts=PTS-STARTPTS,"
        f"crop={crop_w}:{crop_h}:{crop_x}:{crop_y},"
        f"scale={content_w}:{content_h}:flags=lanczos,"
        f"pad={cw}:{ch}:{inset_left}:{inset_top}:color={PAPER},"
        f"zoompan=z='1+0.018*on/{frames}':"
        f"x='iw/2-(iw/zoom/2)':y='ih/2-(ih/zoom/2)':"
        f"d=1:s={cw}x{ch}:fps={FPS},"
        "format=rgba,fade=t=in:st=0:d=0.28:alpha=1,"
        f"pad=iw+8:ih+8:4:4:color={PAPER}[card]"
    )

    # 카드 아래의 부드러운 그림자. 배경과 앱의 경계를 만들되 기기 목업처럼 보이지 않게 한다.
    filters.append(
        f"color=c=black@0.42:s={cw + 44}x{ch + 44}:r={FPS}:d={scene.duration},"
        "format=rgba,gblur=sigma=24[shadow]"
    )
    filters.append(
        f"[bg][shadow]overlay=x={cx - 18}:y={cy - 4}:format=auto[b0]"
    )
    filters.append(
        f"[b0][card]overlay=x={cx}:y={cy}:format=auto[b1]"
    )

    last = "[b1]"
    for i, caption in enumerate(scene.captions, start=1):
        filters.append(
            f"[{i}:v]format=rgba,fade=t=in:st={caption.delay}:d=0.42:alpha=1[txt{i}]"
        )
        out = f"[b{i + 1}]"
        filters.append(
            f"{last}[txt{i}]overlay=x={caption.x}:y={caption.y}:format=auto{out}"
        )
        last = out

    filters.append(
        f"{last}format=yuv420p,setparams=range=limited:color_primaries=bt709:"
        "color_trc=bt709:colorspace=bt709[out]"
    )

    run([
        "ffmpeg", "-v", "error", *inputs,
        "-filter_complex", ";".join(filters), "-map", "[out]", "-an",
        "-t", str(scene.duration), "-r", str(FPS),
        "-c:v", "libx264", "-preset", "medium", "-crf", "16",
        "-color_primaries", "bt709", "-color_trc", "bt709",
        "-colorspace", "bt709", "-movflags", "+faststart", "-y", str(target),
    ])
    print(f"  {scene.name:18s} {duration(target):5.1f}s")
    return target


def score_cues(plan: tuple[Scene, ...]) -> dict[str, float]:
    """음악이 맞춰 칠 순간들 — 최종 영상의 몇 초인가.

    장면 경계는 장면 길이의 누적이다. 장면 안의 사건은 원본 컷의 시각을 재생 속도로 나눠
    계산하고, 계산할 근거가 코드에 없는 것만 MEASURED_CUES 의 실측값을 쓴다.
    """
    starts: dict[str, float] = {}
    cursor = 0.0
    for scene in plan:
        starts[scene.name] = cursor
        cursor += scene.duration
    by_name = {scene.name: scene for scene in plan}

    def within(name: str, source_time: float) -> float:
        """원본 컷의 [source_time] 초가 최종 영상의 몇 초인가."""
        scene = by_name[name]
        return starts[name] + (source_time - scene.start) / scene.speed

    purchase = legacy.SEQUENCES["08-purchase"]
    cues = {
        "hook": starts["01-hook"],
        "match": starts["02-match"],
        # 날씨 줄과 발췌문의 rain 이 칠해지기 시작하는 순간 (cut_demo.HIGHLIGHTS)
        "glow": within("02-match", legacy.HIGHLIGHTS["03-matching"][2]),
        "turn": starts["03-turn"],
        "widgets": starts["05-widgets"],
        "android": starts["05-widgets"] + WIDGET_IOS_SECONDS,
        "surfaces": starts["06-surfaces"],
        # 두 폰이 같은 순간 넘어간다 (make_clean_kmp 가 넘김을 SPLIT_TURN_AT 에 맞춘다)
        "sync": within("06-surfaces", legacy.SPLIT_TURN_AT),
        # 데스크톱이 들어와 셋이 된다 — 겹침의 한가운데
        "desktop": within("06-surfaces",
                          KMP_LENGTH - legacy.SPLIT_TRIO + legacy.TRIO_FADE / 2),
        "shared": starts["07-architecture"],
        "purchase": starts["08-purchase"],
        # 앱의 확인 화면(The shelf is open) — 구매 조각 앞 셋의 길이 뒤다
        "shelf": within("08-purchase", sum(piece[2] for piece in purchase[:3])),
        "end": starts["09-end"],
    }
    for i, name in enumerate(SKY_SCENES, start=1):
        cues[f"sky{i}"] = starts[name]
    for name, (scene, offset) in MEASURED_CUES.items():
        cues[name] = starts[scene] + offset
    return cues


def make_score(path: Path, seconds: float, cues: dict[str, float], build: Path) -> None:
    """scripts/score_demo.swift 로 곡을 합성한다. 최적화 빌드로 한 번 컴파일해 둔다.

    `swift 파일` 로 바로 돌리면 최적화 없이 돌아서 수백만 샘플을 합성하기에 느리다.
    """
    binary = build / "score_demo"
    if not binary.exists() or binary.stat().st_mtime < SCORE.stat().st_mtime:
        run(["swiftc", "-O", "-o", str(binary), str(SCORE)])
    proc = subprocess.run(
        [str(binary), str(path), f"{seconds:.3f}",
         *(f"{name}={at:.3f}" for name, at in sorted(cues.items()))],
        capture_output=True, text=True,
    )
    if proc.returncode:
        sys.stderr.write(proc.stderr)
        raise SystemExit("음악 합성 실패")
    print(proc.stdout.rstrip())


def attach_score(video: Path, score: Path, output: Path) -> None:
    """곡을 LOUDNESS 로 맞춰 영상에 싣는다. **영상 스트림은 다시 인코딩하지 않는다.**

    loudnorm 은 두 번 돈다 — 먼저 재고, 잰 값으로 선형 보정한다. 한 번만 돌리면 동적 모드가
    곡의 셈여림을 눌러 버린다. loudnorm 은 내부적으로 192 kHz 로 올리므로 48 kHz 로 되돌린다.
    """
    target = f"I={LOUDNESS}:TP={TRUE_PEAK}:LRA=11"
    measured = subprocess.run(
        ["ffmpeg", "-hide_banner", "-i", str(score), "-af",
         f"loudnorm={target}:print_format=json", "-f", "null", "-"],
        capture_output=True, text=True,
    ).stderr
    stats = json.loads(measured[measured.rindex("{"):measured.rindex("}") + 1])
    loudnorm = (
        f"loudnorm={target}:measured_I={stats['input_i']}:measured_TP={stats['input_tp']}:"
        f"measured_LRA={stats['input_lra']}:measured_thresh={stats['input_thresh']}:"
        f"offset={stats['target_offset']}:linear=true,aresample=48000"
    )
    run([
        "ffmpeg", "-v", "error", "-i", str(video), "-i", str(score),
        "-map", "0:v:0", "-map", "1:a:0", "-c:v", "copy",
        "-af", loudnorm, "-c:a", "aac", "-b:a", "192k", "-shortest",
        "-metadata", "comment=Original score composed and synthesized for Almanac "
                     "(scripts/score_demo.swift)",
        "-movflags", "+faststart", "-y", str(output),
    ])


def rescore(video: Path, output: Path) -> None:
    """이미 렌더한 영상의 음악만 바꾼다. 화면은 한 프레임도 다시 만들지 않는다."""
    plan = scenes()
    total = sum(scene.duration for scene in plan)
    if abs(duration(video) - total) > 0.05:
        raise SystemExit(
            f"{video} 는 {duration(video):.2f}초인데 장면 계획은 {total:.2f}초다. "
            "다른 판의 렌더이면 음악이 어긋난다.")
    build = output.parent / "remix-parts"
    build.mkdir(parents=True, exist_ok=True)
    score = build / "original-score.wav"
    make_score(score, total, score_cues(plan), build)
    attach_score(video, score, output)
    print(f"\n{output}\n  {duration(output):.2f}s · 영상 스트림 그대로 · 음악만 교체")


def scenes() -> tuple[Scene, ...]:
    return (
        Scene(
            "01-hook", "cuts/01-cold-open.mp4", 6.0, 0.0, 1.0,
            (900, 900, 510, 0), (980, 86, 820, 820),
            (
                Caption("ALMANAC", 34, ACCENT, 146, 116, 0.0),
                Caption("What if the sky", 82, LIGHT, 142, 270, 0.15),
                Caption("chose your next page?", 82, LIGHT, 142, 370, 0.28),
                Caption("Weather becomes a way into literature.", 35, MUTED, 148, 530, 0.55),
            ),
            ("#101714", "#34291F", "#18221E"),
        ),
        Scene(
            "02-match", "cuts/03-matching.mp4", 9.0, 0.5, 1.2,
            (900, 900, 510, 0), (974, 88, 824, 824),
            (
                Caption("01  ·  MATCH", 30, ACCENT, 146, 118, 0.0),
                Caption("Today's rain.", 74, LIGHT, 142, 276, 0.12),
                Caption("A page from 1922.", 74, LIGHT, 142, 368, 0.26),
                Caption("The forecast and the prose share a word.", 34, MUTED, 148, 520, 0.55),
            ),
            ("#17120F", "#3B2C20", "#101A17"),
        ),
        Scene(
            "03-turn", "cuts/04-page-turn.mp4", 8.0, 0.7, 1.35,
            (960, 960, 480, 0), (180, 116, 760, 760),
            (
                Caption("02  ·  TURN BACK", 30, ACCENT, 1100, 118, 0.0),
                Caption("A different sky.", 72, LIGHT, 1096, 278, 0.12),
                Caption("A different voice.", 72, LIGHT, 1096, 368, 0.26),
                Caption("Swipe back through the days you've read.", 34, MUTED, 1102, 520, 0.55),
            ),
            ("#111A19", "#24373A", "#32271F"),
            inset=(20, 40, 20, 0),
        ),
        Scene(
            "04a-rain", "_take/2026-09-28-pixel8-d/05-rain.mp4", 1.45, 0.624, 2.6,
            (1080, 1450, 0, 132), (1080, 57, 720, 966),
            (
                Caption("SEVEN SKIES", 30, ACCENT, 146, 118, 0.0),
                Caption("RAIN", 100, LIGHT, 142, 280, 0.04),
                Caption("Seven skies. Seven voices.", 36, MUTED, 148, 430, 0.12),
            ),
            ("#101719", "#24343A", "#332820"),
        ),
        Scene(
            "04b-snow", "_take/2026-09-28-pixel8-d/05-snow.mp4", 1.45, 0.751, 2.6,
            (1080, 1450, 0, 132), (1080, 57, 720, 966),
            (
                Caption("SEVEN SKIES", 30, ACCENT, 146, 118, 0.0),
                Caption("SNOW", 100, LIGHT, 142, 280, 0.04),
                Caption("Seven skies. Seven voices.", 36, MUTED, 148, 430, 0.12),
            ),
            ("#17201F", "#36413C", "#26313A"),
            cue=False,
        ),
        Scene(
            "04c-fog", "_take/2026-09-28-pixel8-d/05-fog.mp4", 1.45, 0.798, 3.0,
            (1080, 1450, 0, 132), (1080, 57, 720, 966),
            (
                Caption("SEVEN SKIES", 30, ACCENT, 146, 118, 0.0),
                Caption("FOG", 100, LIGHT, 142, 280, 0.04),
                Caption("Seven skies. Seven voices.", 36, MUTED, 148, 430, 0.12),
            ),
            ("#171716", "#3A3933", "#202828"),
            cue=False,
        ),
        Scene(
            "04d-wind", "_take/2026-09-28-pixel8-d/05-wind.mp4", 1.35, 0.677, 1.1,
            (1080, 1450, 0, 132), (1080, 57, 720, 966),
            (
                Caption("SEVEN SKIES", 30, ACCENT, 146, 118, 0.0),
                Caption("WIND", 100, LIGHT, 142, 280, 0.04),
                Caption("Seven skies. Seven voices.", 36, MUTED, 148, 430, 0.12),
            ),
            ("#10191A", "#29393B", "#352B23"),
            cue=False,
        ),
        Scene(
            "04e-dawn", "_take/2026-09-28-pixel8-d/05-dawn.mp4", 1.4, 0.696, 3.0,
            (1080, 1450, 0, 132), (1080, 57, 720, 966),
            (
                Caption("SEVEN SKIES", 30, ACCENT, 146, 118, 0.0),
                Caption("DAWN", 100, LIGHT, 142, 280, 0.04),
                Caption("Seven skies. Seven voices.", 36, MUTED, 148, 430, 0.12),
            ),
            ("#1E1719", "#483126", "#22302C"),
            cue=False,
        ),
        Scene(
            "04f-day", "_take/2026-09-28-pixel8-d/05-day.mp4", 1.4, 0.729, 1.0,
            (1080, 1450, 0, 132), (1080, 57, 720, 966),
            (
                Caption("SEVEN SKIES", 30, ACCENT, 146, 118, 0.0),
                Caption("DAY", 100, LIGHT, 142, 280, 0.04),
                Caption("Seven skies. Seven voices.", 36, MUTED, 148, 430, 0.12),
            ),
            ("#101A18", "#2A4038", "#2D3326"),
            cue=False,
        ),
        Scene(
            "04g-dusk", "_take/2026-09-28-pixel8-d/05-dusk.mp4", 1.5, 0.705, 1.0,
            (1080, 1450, 0, 132), (1080, 57, 720, 966),
            (
                Caption("SEVEN SKIES", 30, ACCENT, 146, 118, 0.0),
                Caption("DUSK", 100, LIGHT, 142, 280, 0.04),
                Caption("Seven skies. Seven voices.", 36, MUTED, 148, 430, 0.12),
            ),
            ("#17151C", "#3A2B38", "#1C2B2B"),
            cue=False,
        ),
        Scene(
            "05-widgets", "@build/clean-widgets.mp4", 10.0, 0.0, 1.0,
            (1080, 520, 0, 0), (845, 280, 940, 452),
            (
                Caption("03  ·  GLANCE", 30, ACCENT, 146, 118, 0.0),
                Caption("Your page,", 68, LIGHT, 142, 260, 0.12),
                Caption("right on the", 68, LIGHT, 142, 345, 0.20),
                Caption("home screen.", 68, LIGHT, 142, 430, 0.28),
                Caption("Home-screen widgets without opening the app.", 31, MUTED, 148, 555, 0.50),
                Caption("iOS  →  Android", 28, ACCENT, 148, 640, 0.62),
            ),
            ("#101615", "#25352F", "#36291F"),
        ),
        Scene(
            "06-surfaces", "@build/clean-kmp.mp4", 8.5, 0.0, 1.06,
            (1920, 1080, 0, 0), (160, 160, 1600, 900),
            (
                Caption("04  ·  ONE CORE. FIVE SURFACES.", 46, LIGHT, 188, 46, 0.05),
            ),
            ("#131817", "#322B24", "#182624"),
        ),
        Scene(
            "07-architecture", "cards/07-structure.png", 4.0, 0.0, 1.0,
            (1920, 1080, 0, 0), (690, 224, 1110, 624),
            (
                Caption("SHARED ONCE.", 56, LIGHT, 146, 274, 0.10),
                Caption("Rendered everywhere.", 42, MUTED, 148, 360, 0.28),
            ),
            ("#17120F", "#35291F", "#13201D"),
            still=True,
        ),
        Scene(
            "08-purchase", "@build/clean-purchase.mp4", 11.0, 0.0, 1.45,
            (820, 1080, 550, 0), (1054, 100, 640, 844),
            (
                Caption("05  ·  ONE-TIME PURCHASE", 30, ACCENT, 146, 118, 0.0),
                Caption("One purchase.", 76, LIGHT, 142, 280, 0.12),
                Caption("The whole shelf.", 76, LIGHT, 142, 375, 0.26),
                Caption("No subscription. Restore anytime.", 34, MUTED, 148, 530, 0.55),
                Caption("Purchases via RevenueCat", 30, ACCENT, 148, 610, 0.68),
            ),
            ("#121615", "#2D3A33", "#392A20"),
        ),
        Scene(
            "09-end", "cards/09-end.png", 6.0, 0.0, 1.0,
            (1920, 1080, 0, 0), (690, 224, 1120, 630),
            (
                Caption("LOOK UP.", 78, LIGHT, 142, 294, 0.12),
                Caption("READ BACK.", 78, LIGHT, 142, 392, 0.28),
            ),
            ("#0F1513", "#30271F", "#17221F"),
            still=True,
        ),
    )


def main(argv: list[str]) -> int:
    if len(argv) == 4 and argv[1] == "rescore":
        rescore(Path(argv[2]).expanduser().resolve(), Path(argv[3]).expanduser().resolve())
        return 0
    if len(argv) != 3:
        print(__doc__.strip(), file=sys.stderr)
        return 2

    footage = Path(argv[1]).expanduser().resolve()
    output = Path(argv[2]).expanduser().resolve()
    build = output.parent / "remix-parts"
    build.mkdir(parents=True, exist_ok=True)
    output.parent.mkdir(parents=True, exist_ok=True)

    make_clean_widgets(footage, build)
    make_clean_kmp(footage, build)
    make_clean_purchase(footage, build)

    plan = scenes()
    parts = [render_scene(scene, footage, build) for scene in plan]

    listing = build / "parts.txt"
    listing.write_text("".join(f"file '{part}'\n" for part in parts), encoding="utf-8")
    silent = build / "silent-master.mp4"
    run([
        "ffmpeg", "-v", "error", "-f", "concat", "-safe", "0", "-i", str(listing),
        "-c", "copy", "-movflags", "+faststart", "-y", str(silent),
    ])

    total = duration(silent)
    score = build / "original-score.wav"
    make_score(score, total, score_cues(plan), build)
    attach_score(silent, score, output)

    print(f"\n{output}\n  {duration(output):.2f}s · 1920x1080 · 60fps · H.264/AAC")
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv))
