#!/usr/bin/env python3
"""
녹화한 컷을 데모 영상으로 조립한다.

**컷 길이가 소수점까지 정해져 있는 것이 이 스크립트의 존재 이유다**
(docs/product/demo-video.md #5). 손으로 맞추면 매번 달라지고, 한 컷을 고치면
뒤가 전부 밀린다. 여기서는 표 한 줄을 고치면 끝이고 결과는 항상 같다.

하는 일.
- 기기 녹화의 시스템 영역(상태바·내비게이션 바)을 잘라낸다
- 지정한 길이로 **하드 컷**한다. 디졸브를 쓰지 않는 것이 #5 의 요구다
- 앱과 **같은 서체(Crimson Text)** 로 자막을 태운다
- 가변 프레임 녹화를 60fps 고정으로 맞춘다

사용:
    python3 scripts/cut_demo.py cut       <컷 이름> <녹화 뿌리> <컷 디렉터리>
    python3 scripts/cut_demo.py split     <녹화 뿌리> <컷 디렉터리>    # #7 두 화면
    python3 scripts/cut_demo.py montage   <녹화 디렉터리> <출력.mp4>   # #5 조립
    python3 scripts/cut_demo.py subtitles <출력 디렉터리> [연도차]      # 자막 8장
    python3 scripts/cut_demo.py status    <컷 디렉터리>                # 진행 상황
    python3 scripts/cut_demo.py preview   <컷 디렉터리> <출력.mp4>      # 찍은 것만 잇기
    python3 scripts/cut_demo.py master    <컷 디렉터리> <출력.mp4>      # 전체 이어붙이기

의존성: ffmpeg, swift(자막 렌더).
"""

from __future__ import annotations

import os
import subprocess
import sys
import tempfile

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
FONT = f"{ROOT}/composeApp/src/commonMain/composeResources/font/crimson_text.ttf"

#: 앱의 종이색. 레터박스를 이 색으로 채우면 프레임 전체가 앱의 일부로 보인다.
PAPER = "0xFBF9F4"
INK = "0x1A1A1A"
MUTED = "0x8A8378"

#: 기기별 시스템 영역 (위, 아래 픽셀). **녹화 해상도로 기기를 알아낸다** —
#: 파일이 스스로 어디서 왔는지 말하므로 컷마다 기기를 적어 둘 필요가 없다.
#:
#: 잘라내는 이유는 두 가지다. 상태바의 시각이 #5 의 고정 시간대와 어긋나고,
#: **Android 와 iOS 를 섞어 쓰기 때문이다** — 시스템 영역을 걷어내면 남는 것은
#: 앱 화면뿐이라 두 기기의 컷이 한 영상 안에서 따로 놀지 않는다.
DEVICE_CROP = {
    (1080, 2340): (98, 130),    # Galaxy S23 — cutout / 내비게이션 바
    (1206, 2622): (186, 102),   # iPhone 17 Pro — 상태바 62pt / 홈 인디케이터 34pt
}

#: 페이지가 나타나는 시각.
#:
#: **파일 안의 시각은 벽시계와 다르다.** `screenrecord` 는 화면이 바뀔 때만 프레임을
#: 쓰므로, 녹화 앞의 정지 구간(촬영 메뉴를 띄워둔 0.8초)은 파일에서 거의 사라진다.
#: 그래서 실측으로 잡는다 — 이 값 이후로는 촬영 메뉴가 한 프레임도 없다.
PAGE_AT = 0.25

OUT_W, OUT_H = 1920, 1080

#: #5 하늘의 변주. (파일 이름, 길이, 구석 라벨) — 합 15.0초.
MONTAGE = [
    ("05-rain", 2.0, "Rain"),
    ("05-snow", 3.0, "Snow"),
    ("05-fog", 2.5, "Fog"),
    ("05-wind", 2.0, "Wind"),
    ("05-dawn", 1.8, "Dawn"),
    ("05-day", 1.8, "Day"),
    ("05-dusk", 1.9, "Dusk"),
]

MONTAGE_SUBTITLE = "Each sky reveals a different page."

#: 상한. 대회 규정이므로 사람이 기억할 일이 아니라 빌드가 막을 일이다.
MAX_SECONDS = 120.0

#: 콘티 전체 (docs/product/demo-video.md). 합 105초 = 1분 45초.
#: 한 줄을 고치면 뒤가 저절로 밀린다 — 손으로 맞추지 않는다.
TIMELINE = [
    ("01-cold-open",   7.0, 1),
    ("02-pitch",       7.0, 2),
    ("03-matching",   12.0, 3),
    ("04-page-turn",  12.0, 4),
    ("05-montage",    15.0, None),   # 자막은 montage 단계에서 이미 태운다
    ("06-widgets",    15.0, None),   # 화면이 스스로 설명한다
    # #7 은 재료가 두 갈래로 온다 — 기기 녹화와 구조 카드. 한 줄로 두면 카드까지
    # 촬영을 기다려야 하므로 나눈다. 합은 그대로 14초다.
    ("07a-kmp",        9.0, 6),
    ("07b-card",       5.0, None),
    ("08-purchase",   16.0, 7),
    ("09-outro",       7.0, 8),
]

#: 컷마다 어느 녹화의 **어디서부터** 쓰는가. (원본 경로, 시작 시각)
#:
#: 시작 시각이 표에 있는 이유는 녹화가 컷보다 길기 때문이다 — 앱이 뜨기 전의
#: 홈 화면이나, 촬영 메뉴를 닫는 손이 앞에 붙어 있다. 그 값을 눈으로 한 번 찾고
#: 여기 적어 두면 그 컷은 다시 돌려도 같은 자리에서 시작한다.
#:
#: **`05-montage` 는 여기 없다.** 그건 일곱 조각을 이어 만드는 것이라 `montage` 가 맡는다.
SOURCES = {
    "01-cold-open": ("raw-ios/01-cold-open.mp4", 1.78),
    "02-pitch":     ("raw-ios/02-pitch.png",     0.00),
    "03-matching":  ("raw-ios/03-matching.mp4",  1.78),
    "04-page-turn": ("raw-ios/04-page-turn.mp4", 3.60),
    "09-outro":     ("raw-ios/09-outro.png",     0.00),
    "07b-card":     ("cards/07-structure.png",   0.00),
}

#: 컷 끝에 붙는 카드. (카드 경로, 카드가 들어오기 시작하는 시각, 디졸브 길이)
#:
#: #9 는 마지막 발췌문 → 앱 이름 → 스토어 배지 순서다 (콘티). 페이지와 카드는 녹화 하나로
#: 담을 수 없으므로 편집이 잇는다. 다른 컷은 전부 하드 컷이지만 여기만 디졸브다 —
#: 영상이 끝난다는 신호가 여기서 나와야 한다. 자막은 앞쪽(페이지)에만 있고 2.5초를 넘긴다.
#: 카드는 `scripts/render_endcard.swift` 가 굽는다.
END_CARDS = {
    "09-outro": ("cards/09-end.png", 3.4, 0.6),
}

#: 아직 한쪽만 찍힌 컷. 완성되면 `SOURCES` 로 옮긴다.
#:
#: `06-widgets` 는 Android 홈 화면과 iPhone 홈 화면을 둘 다 보여줘야 하는데
#: Android 기기가 없어 iOS 쪽만 있다. 그 안에서 쓸 구간을 적어 둔다 —
#: 3.3초에서 시작하면 홈 화면 2초, 위젯 탭, 앱이 같은 문장으로 열리는 데까지 담긴다.
PENDING = {
    "06-widgets": [("raw-ios/06-widget-ios.mp4", 3.30, "iPhone 쪽 절반")],
}

#: 컷 안의 카메라 움직임. (시작 배율, 끝 배율, 가로 초점, 세로 초점)
#:
#: **이게 없으면 앞 26초가 같은 화면이다.** 앱 화면은 하나뿐이라 #1 #2 #3 이
#: 전부 오늘 페이지다. 콘티가 #2 를 "화면이 오늘 페이지 전체로 물러나며",
#: #3 을 "날씨 칩 → 발췌문" 이라고 적은 것은 그래서다 — 화면이 아니라
#: **프레임이** 움직여서 세 컷을 가른다.
#:
#: 초점은 0~1 의 정규 좌표다. 날씨 줄은 위에서 10% 쯤에 있다.
#:
#: **배율의 상한은 본문 폭이 정한다.** 확대는 가로세로를 같이 자르는데, 본문이
#: 화면 폭의 89% 를 쓰고 있어서 1.12 를 넘기면 줄 끝의 글자가 잘린다. 1.3 으로
#: 걸어 보니 `The Enchanted April` 이 `e Enchanted April` 이 됐다 — 의도한
#: 프레이밍이 아니라 실수로 보인다. 그래서 움직임은 작고, 대신 느리다.
MOVES = {
    "02-pitch":    (1.12, 1.00, 0.5, 0.10),   # 날씨 줄에서 페이지 전체로 물러난다
    "03-matching": (1.00, 1.12, 0.5, 0.10),   # 페이지에서 날씨 줄로 들어간다
}

#: 화면에 찍힌 연도 차이. **자막이 화면과 다르면 그게 제일 먼저 보인다.**
#: 2026-09-25 서울, 비 → The Enchanted April (1922) → 104.
YEARS = "104"

#: 자막 8줄. `{N}` 은 그날 찍힌 실제 연도 차이로 바꾼다.
SUBTITLES = {
    1: "This weather, written {N} years ago.",
    2: "Almanac matches today's sky with literature.",
    3: "Weather chooses today's page.",
    4: "Turn back through time.",
    5: "Each sky reveals a different page.",
    6: "One Kotlin core. Five surfaces.",
    7: "One purchase opens the whole shelf.",
    8: "Look up. Read back.",
}


def run(args: list[str]) -> None:
    proc = subprocess.run(args, capture_output=True, text=True)
    if proc.returncode != 0:
        sys.stderr.write(proc.stderr[-2500:])
        raise SystemExit(f"ffmpeg 실패: {' '.join(args[:6])} …")


def render_text(text: str, size: int, color: str, cache_dir: str) -> str:
    """
    글자를 투명 PNG 로 굽는다.

    **이 머신의 ffmpeg 에는 drawtext 도 subtitles 도 없다.** 그리고 있었더라도
    CoreText 쪽이 낫다 — 앱 화면과 같은 엔진으로 같은 폰트를 조판하므로
    커닝과 합자가 영상과 화면에서 어긋나지 않는다.
    """
    key = f"{abs(hash((text, size, color))):x}"
    out = f"{cache_dir}/text-{key}.png"
    if not os.path.isfile(out):
        run(["swift", f"{ROOT}/scripts/render_text.swift", FONT,
             str(size), color, out, text])
    return out


def frame_size(path: str) -> tuple[int, int]:
    out = subprocess.run(
        ["ffprobe", "-v", "error", "-select_streams", "v:0", "-show_entries",
         "stream=width,height", "-of", "csv=p=0:s=x", path],
        capture_output=True, text=True).stdout.strip()
    w, h = out.split("x")[:2]
    return int(w), int(h)


def crop_for(path: str) -> tuple[int, int]:
    """이 녹화의 시스템 영역. **모르는 기기면 만들지 않는다** — 잘못 자르느니 멈춘다."""
    size = frame_size(path)
    if size[0] >= size[1]:
        return (0, 0)   # 가로 프레임이면 기기 화면이 아니다 (구조 카드 등)
    if size not in DEVICE_CROP:
        known = ", ".join(f"{w}x{h}" for w, h in DEVICE_CROP)
        raise SystemExit(
            f"{size[0]}x{size[1]} 의 시스템 영역을 모른다 ({path}).\n"
            f"아는 기기: {known}. DEVICE_CROP 에 한 줄 더할 것.")
    return DEVICE_CROP[size]


def prepare_cut(src: str, dst: str, *, start: float, length: float,
                label: str | None, cache_dir: str,
                subtitle: str | None = None,
                move: tuple[float, float, float, float] | None = None) -> None:
    """한 컷: 잘라내기 → 시스템 영역 제거 → 16:9 종이 위에 올리기 → 구석 라벨.

    원본은 녹화일 수도 **정지화면일 수도** 있다. 화면이 멈춰 있는 컷(#2 #9, #7 의
    구조 카드)은 녹화로 담기지 않는다 — `simctl` 도 `screenrecord` 도 화면이 바뀔 때만
    프레임을 쓰기 때문이다. 8초를 녹화해도 파일은 0.07초로 나온다. 그런 컷은
    스크린샷 한 장이 원본이고, 움직임은 편집이 만든다.
    """
    crop_top, crop_bottom = crop_for(src)
    still = src.lower().endswith((".png", ".jpg", ".jpeg"))
    # `tpad` 로 **마지막 프레임을 늘린다.** 같은 이유다 — 문장이 다 놓인 뒤의 정적은
    # 파일에 들어 있지 않은데, 몽타주는 그 정적을 보여줘야 한다.
    # 뒤에서 `-t` 로 정확히 잘라내므로 여유는 넉넉히 준다.
    # **자르기를 필터 안에서 한다.** `-ss` 로 입력을 탐색하면 출력 타임스탬프에
    # 그 오프셋이 남아 `-t` 가 그만큼 일찍 끊는다 — 컷마다 0.4초씩 짧아졌다.
    # trim + setpts 는 그런 여지가 없다.
    # 정지화면은 잘라낼 앞부분이 없다. `-loop` 이 길이를 만들고 `-t` 가 끊는다.
    head = "[0:v]" if still else f"[0:v]trim=start={start},setpts=PTS-STARTPTS,"
    # 카메라 움직임은 **줄이기 전에** 건다. 558px 로 줄인 뒤에 확대하면 뭉개진다.
    pan = ""
    if move:
        z0, z1, cx, cy = move
        frames = int(round(length * 60))
        pan = (
            f"zoompan=z='{z0}+({z1}-{z0})*on/{max(frames - 1, 1)}'"
            f":x='(iw-iw/zoom)*{cx}':y='(ih-ih/zoom)*{cy}'"
            f":d=1:s={{W}}x{{H}}:fps=60,"
        )

    base = (
        f"{head}"
        f"crop=in_w:in_h-{crop_top + crop_bottom}:0:{crop_top},"
        f"{pan}"
        f"scale=-2:{OUT_H},"
        f"pad={OUT_W}:{OUT_H}:(ow-iw)/2:0:color={PAPER},"
        f"fps=60,tpad=stop_mode=clone:stop_duration=8"
    )
    if move:
        w, h = frame_size(src)
        base = base.replace("{W}", str(w)).replace("{H}", str(h - crop_top - crop_bottom))

    inputs = (["-loop", "1", "-framerate", "60", "-t", f"{length + 1}", "-i", src]
              if still else ["-i", src])
    # 얹을 것을 순서대로 모은다. 구석 라벨은 왼쪽 위, 자막은 아래 가운데다.
    overlays = []
    if label:
        overlays.append((render_text(label, 34, "#8A8378", cache_dir),
                         "x=W*0.06:y=H*0.08"))
    if subtitle:
        overlays.append((render_text(subtitle, 46, "#1A1A1A", cache_dir),
                         "x=(W-w)/2:y=H-H*0.14"))
    if overlays:
        chain = f"{base}[v0];"
        for i, (png, pos) in enumerate(overlays):
            inputs += ["-i", png]
            last = i == len(overlays) - 1
            tail = ",format=yuv420p" if last else f"[v{i + 1}];"
            chain += f"[v{i}][{i + 1}:v]overlay={pos}{tail}"
    else:
        chain = f"{base},format=yuv420p"
    run([
        "ffmpeg", "-v", "error", *inputs, "-filter_complex", chain, "-an",
        "-t", f"{length}",
        "-c:v", "libx264", "-preset", "medium", "-crf", "16", "-y", dst,
    ])


def montage(raw_dir: str, out_path: str) -> None:
    with tempfile.TemporaryDirectory() as tmp:
        parts = []
        for name, length, label in MONTAGE:
            src = f"{raw_dir}/{name}.mp4"
            if not os.path.isfile(src):
                raise SystemExit(f"녹화가 없다: {src}")
            dst = f"{tmp}/{name}.mp4"
            prepare_cut(src, dst, start=PAGE_AT, length=length,
                        label=label, cache_dir=tmp)
            parts.append(dst)
            print(f"  {name:10s} {length:>4.1f}초  ({label})")

        listing = f"{tmp}/parts.txt"
        with open(listing, "w") as f:
            for p in parts:
                f.write(f"file '{p}'\n")

        joined = f"{tmp}/joined.mp4"
        run(["ffmpeg", "-v", "error", "-f", "concat", "-safe", "0",
             "-i", listing, "-c", "copy", "-y", joined])

        # 자막은 이어붙인 뒤에 얹는다. 컷마다 태우면 경계에서 글자가 튄다.
        sub_png = render_text(MONTAGE_SUBTITLE, 46, "#1A1A1A", tmp)
        run(["ffmpeg", "-v", "error", "-i", joined, "-i", sub_png,
             "-filter_complex",
             "[0:v][1:v]overlay=x=(W-w)/2:y=H-H*0.14,format=yuv420p",
             "-c:v", "libx264", "-preset", "medium", "-crf", "16",
             "-y", out_path])


#: #7 두 화면 분할. (원본 경로, 넘김이 시작되는 시각, 라벨)
#:
#: **두 기기의 넘김을 같은 순간에 맞춘다.** 각각 따로 찍었으므로 파일 안의 시각이
#: 다르다. 여기 적은 값이 컷 안에서 `SPLIT_TURN_AT` 초가 되도록 각자 잘라낸다.
SPLIT_SOURCES = [
    ("raw-android/07-kmp-android.mp4", 3.15, "Android"),
    ("raw-ios/07-kmp-ios.mp4",         7.70, "iPhone"),
]

#: 컷 안에서 넘김이 일어나는 시각. 앞은 "같은 문장" 을 보여주고, 뒤는 읽을 시간이다.
SPLIT_TURN_AT = 2.2

#: 폰 하나의 폭과, 잘라낼 높이.
#:
#: **세로로 꽉 채우면 본문이 안 읽힌다** — 1080p 에서 폰 비율을 전부 담으면 폭이
#: 552px 이고 본문 17sp 는 23px 로 뭉갠다 (콘티가 우려한 그대로다). 페이지 아래쪽은
#: 빈 종이이므로 **잘라내고 키운다.** 두 기기의 논리 폭이 411dp 와 402pt 로 거의
#: 같아서, 같은 픽셀 폭으로 맞추면 글자 크기도 같아진다.
SPLIT_PHONE_W = 680
SPLIT_PHONE_H = 880
SPLIT_TOP = 40
SPLIT_GAP = 100


def split(raw_root: str, parts_dir: str, name: str = "07a-kmp") -> None:
    """두 기기의 녹화를 한 프레임에 나란히 놓는다. 넘김은 같은 순간에 일어난다."""
    lengths = {n: length for n, length, _ in TIMELINE}
    numbers = {n: number for n, _, number in TIMELINE}
    length = lengths[name]
    line = SUBTITLES[numbers[name]].replace("{N}", YEARS) if numbers[name] else None

    os.makedirs(parts_dir, exist_ok=True)
    dst = f"{parts_dir}/{name}.mp4"

    with tempfile.TemporaryDirectory() as tmp:
        inputs, chains, tags = [], [], []
        for i, (rel, turn_at, label) in enumerate(SPLIT_SOURCES):
            src = os.path.join(raw_root, rel)
            if not os.path.isfile(src):
                raise SystemExit(f"녹화가 없다: {src}")
            crop_top, crop_bottom = crop_for(src)
            start = max(turn_at - SPLIT_TURN_AT, 0.0)
            inputs += ["-i", src]
            # **`fps` 가 `trim` 앞에 온다.** 기기 녹화는 가변 프레임이라 정지 구간에
            # 프레임이 없는데, 그 상태로 잘라내면 `setpts=PTS-STARTPTS` 가 빈 시간을
            # 통째로 접어버린다 — 자른 지점이 벽시계가 아니라 "그 이후 첫 프레임" 이
            # 된다. 한 파일만 다룰 때는 그래도 되지만(`prepare_cut` 이 그렇다),
            # **두 기기를 같은 순간에 맞추려면 시각이 진짜여야 한다.**
            chains.append(
                f"[{i}:v]fps=60,trim=start={start},setpts=PTS-STARTPTS,"
                f"crop=in_w:in_h-{crop_top + crop_bottom}:0:{crop_top},"
                f"scale={SPLIT_PHONE_W}:-2,"
                # 아래쪽 빈 종이를 버린다. 남는 것이 문장이다.
                f"crop={SPLIT_PHONE_W}:{SPLIT_PHONE_H}:0:0,"
                f"tpad=stop_mode=clone:stop_duration=12[p{i}]"
            )
            tags.append(f"[p{i}]")

        total_w = SPLIT_PHONE_W * len(SPLIT_SOURCES) + SPLIT_GAP * (len(SPLIT_SOURCES) - 1)
        x0 = (OUT_W - total_w) // 2
        chains.append(f"color=c={PAPER}:s={OUT_W}x{OUT_H}:r=60[bg]")

        last = "[bg]"
        for i, tag in enumerate(tags):
            x = x0 + i * (SPLIT_PHONE_W + SPLIT_GAP)
            out = f"[s{i}]"
            chains.append(f"{last}{tag}overlay=x={x}:y={SPLIT_TOP}{out}")
            last = out

        # 라벨과 자막. 라벨은 폰 아래, 자막은 그 아래다.
        extra = len(SPLIT_SOURCES)
        for i, (_, _, label) in enumerate(SPLIT_SOURCES):
            png = render_text(label, 30, "#8A8378", tmp)
            inputs += ["-i", png]
            x = x0 + i * (SPLIT_PHONE_W + SPLIT_GAP)
            out = f"[l{i}]"
            chains.append(
                f"{last}[{extra + i}:v]"
                f"overlay=x={x}+({SPLIT_PHONE_W}-w)/2:y={SPLIT_TOP + SPLIT_PHONE_H + 14}{out}"
            )
            last = out

        if line:
            png = render_text(line, 46, "#1A1A1A", tmp)
            inputs += ["-i", png]
            n = extra + len(SPLIT_SOURCES)
            chains.append(f"{last}[{n}:v]overlay=x=(W-w)/2:y=H-H*0.075[final]")
            last = "[final]"

        chain = ";".join(chains) + f";{last}format=yuv420p[out]"
        run(["ffmpeg", "-v", "error", *inputs, "-filter_complex", chain,
             "-map", "[out]", "-an", "-t", f"{length}",
             "-c:v", "libx264", "-preset", "medium", "-crf", "16", "-y", dst])

    made = duration(dst)
    mark = "\u2713" if abs(made - length) < 0.15 else "!"
    print(f"  {mark} {name}  {length:.1f}\ucd08 (\uc2e4\uc81c {made:.2f})  \u2190 \ubd84\ud560 {len(SPLIT_SOURCES)}\ud654\uba74")


def cut(name: str, raw_root: str, parts_dir: str) -> None:
    """컷 하나를 원본에서 잘라낸다. 길이는 콘티가 정하고, 시작은 `SOURCES` 가 정한다."""
    lengths = {n: length for n, length, _ in TIMELINE}
    if name not in lengths:
        raise SystemExit(f"콘티에 없는 컷: {name}\n있는 것: " + ", ".join(lengths))
    if name not in SOURCES:
        raise SystemExit(f"원본이 어디인지 모른다: {name} (SOURCES 에 적을 것)")

    rel, start = SOURCES[name]
    src = os.path.join(raw_root, rel)
    if not os.path.isfile(src):
        raise SystemExit(f"녹화가 없다: {src}")

    numbers = {n: number for n, _, number in TIMELINE}
    line = SUBTITLES[numbers[name]].replace("{N}", YEARS) if numbers[name] else None

    os.makedirs(parts_dir, exist_ok=True)
    dst = f"{parts_dir}/{name}.mp4"
    card = END_CARDS.get(name)
    with tempfile.TemporaryDirectory() as tmp:
        if card is None:
            prepare_cut(src, dst, start=start, length=lengths[name],
                        label=None, cache_dir=tmp, subtitle=line,
                        move=MOVES.get(name))
        else:
            card_rel, card_at, fade = card
            card_src = os.path.join(raw_root, card_rel)
            if not os.path.isfile(card_src):
                raise SystemExit(f"카드가 없다: {card_src}")
            # 앞쪽은 디졸브가 겹칠 만큼 더 길게 뽑는다. 두 조각의 합이 컷 길이가 된다.
            head = f"{tmp}/head.mp4"
            tail = f"{tmp}/tail.mp4"
            prepare_cut(src, head, start=start, length=card_at + fade,
                        label=None, cache_dir=tmp, subtitle=line,
                        move=MOVES.get(name))
            prepare_cut(card_src, tail, start=0.0, length=lengths[name] - card_at,
                        label=None, cache_dir=tmp)
            run(["ffmpeg", "-v", "error", "-i", head, "-i", tail, "-filter_complex",
                 f"[0:v][1:v]xfade=transition=fade:duration={fade}:offset={card_at},"
                 f"format=yuv420p",
                 "-an", "-c:v", "libx264", "-preset", "medium", "-crf", "16", "-y", dst])
    made = duration(dst)
    mark = "✓" if abs(made - lengths[name]) < 0.15 else "!"
    print(f"  {mark} {name}  {lengths[name]:.1f}초 (실제 {made:.2f})  ← {rel} @ {start}초")


def duration(path: str) -> float:
    out = subprocess.run(
        ["ffprobe", "-v", "error", "-show_entries", "format=duration",
         "-of", "csv=p=0", path], capture_output=True, text=True).stdout.strip()
    return float(out) if out else 0.0


def subtitles(out_dir: str, years: str = YEARS) -> None:
    """자막 8줄을 전부 PNG 로 굽는다. 편집기에서 바로 얹을 수 있다."""
    os.makedirs(out_dir, exist_ok=True)
    for number, text in SUBTITLES.items():
        line = text.replace("{N}", years)
        path = render_text(line, 46, "#1A1A1A", out_dir)
        named = f"{out_dir}/subtitle-{number}.png"
        os.replace(path, named)
        print(f"  {number}. {line}")


def status(parts_dir: str) -> None:
    """어느 컷이 있고 어느 컷이 비었는지. 촬영 중에 계속 보게 된다."""
    total = 0.0
    for name, length, _ in TIMELINE:
        path = f"{parts_dir}/{name}.mp4"
        if os.path.isfile(path):
            actual = duration(path)
            mark = "✓" if abs(actual - length) < 0.15 else "!"
            print(f"  {mark} {name:14s} {length:>5.1f}초  (실제 {actual:.2f})")
            total += actual
        else:
            print(f"  · {name:14s} {length:>5.1f}초  — 없음")
    planned = sum(length for _, length, _ in TIMELINE)
    print(f"\n  찍은 것 {total:.2f}초 / 계획 {planned:.1f}초 / 상한 {MAX_SECONDS:.0f}초")


def preview(parts_dir: str, out_path: str) -> None:
    """**찍은 것만** 순서대로 이어붙인다. 촬영 중에 흐름을 보려고 만든다.

    `master` 와 달리 빠진 컷을 기다리지 않는다. 대신 무엇이 빠졌는지 말한다 —
    조용히 짧은 영상을 내놓으면 그게 완성본인 줄 알게 된다.
    """
    have = [n for n, _, _ in TIMELINE if os.path.isfile(f"{parts_dir}/{n}.mp4")]
    missing = [n for n, _, _ in TIMELINE if n not in have]
    if not have:
        raise SystemExit("찍은 컷이 하나도 없다.")

    with tempfile.TemporaryDirectory() as tmp:
        listing = f"{tmp}/parts.txt"
        with open(listing, "w") as f:
            for name in have:
                f.write(f"file '{parts_dir}/{name}.mp4'\n")
        run(["ffmpeg", "-v", "error", "-f", "concat", "-safe", "0",
             "-i", listing, "-c", "copy", "-y", out_path])

    print(f"{out_path}  —  {duration(out_path):.2f}초 ({len(have)}/{len(TIMELINE)} 컷)")
    if missing:
        print("아직 없는 컷: " + ", ".join(missing))


def master(parts_dir: str, out_path: str) -> None:
    """완성된 컷들을 하나로 잇는다. **2분을 넘기면 만들지 않는다.**"""
    missing = [n for n, _, _ in TIMELINE if not os.path.isfile(f"{parts_dir}/{n}.mp4")]
    if missing:
        raise SystemExit("아직 없는 컷: " + ", ".join(missing))

    planned = sum(length for _, length, _ in TIMELINE)
    if planned > MAX_SECONDS:
        raise SystemExit(f"콘티가 이미 상한을 넘는다: {planned:.1f}초 > {MAX_SECONDS:.0f}초")

    with tempfile.TemporaryDirectory() as tmp:
        listing = f"{tmp}/parts.txt"
        with open(listing, "w") as f:
            for name, _, _ in TIMELINE:
                f.write(f"file '{parts_dir}/{name}.mp4'\n")
        run(["ffmpeg", "-v", "error", "-f", "concat", "-safe", "0",
             "-i", listing, "-c", "copy", "-y", out_path])

    made = duration(out_path)
    if made > MAX_SECONDS:
        os.remove(out_path)
        raise SystemExit(f"2분을 넘었다: {made:.2f}초. 만들지 않는다.")
    print(f"\n{out_path}  —  {made:.2f}초 (상한 {MAX_SECONDS:.0f})")


def main(argv: list[str]) -> int:
    if len(argv) < 3:
        print(__doc__.strip(), file=sys.stderr)
        return 2
    cmd = argv[1]
    if cmd == "split" and len(argv) >= 4:
        split(argv[2], argv[3])
    elif cmd == "cut" and len(argv) >= 5:
        cut(argv[2], argv[3], argv[4])
    elif cmd == "montage" and len(argv) >= 4:
        montage(argv[2], argv[3])
        print(f"\n{argv[3]}  —  {duration(argv[3]):.2f}초 (목표 15.00)")
    elif cmd == "subtitles":
        subtitles(argv[2], argv[3] if len(argv) > 3 else YEARS)
    elif cmd == "status":
        status(argv[2])
    elif cmd == "preview" and len(argv) >= 4:
        preview(argv[2], argv[3])
    elif cmd == "master" and len(argv) >= 4:
        master(argv[2], argv[3])
    else:
        print(__doc__.strip(), file=sys.stderr)
        return 2
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv))
