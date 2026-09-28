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
    # 내비게이션 바는 2205 행부터다. 130 으로 두니 회색 막대 다섯 줄이 남아 #5 아래에 선이 그어졌다.
    (1080, 2340): (98, 135),    # Galaxy S23 — cutout / 내비게이션 바
    (1080, 2400): (132, 63),    # Pixel 8 에뮬레이터 — 상태바 / 제스처 바 (dumpsys window 실측)
    (1206, 2622): (186, 102),   # iPhone 17 Pro — 상태바 62pt / 홈 인디케이터 34pt
    (880, 1720): (0, 0),        # 데스크톱 창 — 제목 표시줄은 남긴다. 창이라는 것이 보여야 한다
}

#: 앱 영역 안에 떠 있는 시스템 장식. (x, y, w, h — 녹화 픽셀) **바로 왼쪽의 빈 종이를
#: 떼어다 덮는다.** 색을 박아 칠하면 녹화의 종이색과 한 단계만 달라도 네모가 보이는데,
#: 옆 종이를 그대로 옮기면 프레임마다 정확히 같다. 본문은 화면 폭의 89% 안이라(Galaxy 는
#: 958px 에서 끝난다) 오른쪽 가장자리 100px 은 늘 빈 종이다.
DEVICE_PATCHES = {
    # Galaxy S23 — 엣지 패널 손잡이. 9/25 몽타주 일곱 컷 전부 같은 자리에 찍혀 있었다.
    # 기기 설정에서 끌 수 있지만, 이미 찍은 원본(애니메이션이 120Hz 로 담긴 유일한 것)을 살린다.
    (1080, 2340): [(1060, 1664, 20, 472)],
}

#: 기기의 논리 폭 (dp · pt). **분할 화면에서 글자 크기를 맞추는 기준이다.**
#:
#: 같은 픽셀 폭으로 놓으면 논리 폭이 좁은 기기의 글자가 크게 나온다. 9/27 분할이 그랬다 —
#: Galaxy 는 화면 확대와 글꼴 크기가 기본값이 아니어서 본문이 iPhone 보다 18% 컸고,
#: 넘기기 전 2초 동안 작품명이 잘려 나갔다. 그래서 분할은 **논리 폭에 비례해** 키우고,
#: 기본 설정인 기기(에뮬레이터)로 찍는다. Galaxy 는 설정을 모르므로 여기 없다 — 없으면 멈춘다.
DEVICE_POINTS = {
    (1080, 2400): 1080 / 2.625,   # Pixel 8 에뮬레이터 — 420dpi, 글꼴 배율 1.0
    (1206, 2622): 402,            # iPhone 17 Pro — @3x
    (880, 1720): 440,             # 데스크톱 창 — Retina @2x
}

#: 촬영 메뉴의 `Close` 가 있는 자리 (시스템 영역을 뺀 앱 화면에 대한 비율: 왼쪽, 위, 폭, 높이).
#: 페이지에서는 이 자리가 비어 있다 — 제목(`N years ago`)은 화면 폭의 63% 에서 끝난다.
#:
#: **페이지가 나타나는 시각은 재지 않고 찾는다** (`page_at`). 예전에는 0.25초로 박았는데
#: 우연히 맞았던 것이다. `trim` 은 그 앞의 프레임을 버리고 뒤의 첫 프레임부터 쓰는데,
#: Galaxy 녹화에는 0초(메뉴)와 0.64초(페이지) 사이에 프레임이 없었다. 9/28 에뮬레이터
#: 녹화에는 0.27초에 메뉴를 다시 그린 한 장이 끼어 있어서 같은 값으로 자르니 메뉴가 찍혔다.
#: 메뉴는 닫히는 순간 한 번에 사라지고(빈 종이 한 장) 페이지가 번져 나오므로, 이 자리가
#: 처음으로 깨끗한 종이인 프레임이 곧 페이지의 시작이다.
MENU_CLOSE_AREA = (0.75, 0.0, 0.25, 0.08)

#: 빈 종이로 볼 밝기. 종이는 249 다. 메뉴가 10% 만 남아 있어도 237 로 떨어진다.
CLEAN_LUMA = 240

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

#: 조각 여럿을 하드 컷으로 잇는 컷. (원본, 시작, 길이, 카메라 움직임) — 길이의 합이 컷 길이다.
#:
#: #6 은 두 기기의 홈 화면 위젯을 보여준 뒤, 위젯을 눌러 앱이 **같은 문장으로** 열리는
#: 데까지다. 누르는 쪽은 Android 다 — iOS 시뮬레이터에는 탭 수단이 없고, 에뮬레이터는
#: adb 로 실제 위젯을 누른다. 앱은 죽여 둔 상태라(`am kill`) 위젯에서 차갑게 열린다.
#: iPhone 쪽은 홈 화면 한 장이라 천천히 다가가서 정지화면으로 안 보이게 한다.
#:
#: 9/25 의 iPhone 녹화는 버렸다. 재부팅 직후라 위젯이 검은 바탕에 검은 글자로 그려져
#: 읽히지 않았다. 지금 원본은 2026-09-28 에 다시 찍은 것이다.
SEQUENCES = {
    # #8 구매. Galaxy 에 내부 테스트 트랙으로 설치한 스토어 빌드 0.4.0 (6), 라이선스 테스터 계정의
    # 테스트 결제다. 기기 언어를 영어로 두고 찍었다 (2026-09-28 두 번째 촬영 — 첫 촬영은 결제 시트가
    # 한국어였다. `_old/2026-09-28-ko-purchase`). 녹화 하나로 담지 못해 다섯 조각이다.
    # - 서가는 촬영 직전 스크린샷이다. 가변 프레임 녹화라 탭 전 2.5초에 프레임이 한 장뿐이고,
    #   거기서 잘라 들어가면 그 한 장을 버려 서가가 사라진다
    # - 결제 시트는 5.2~6.3초에 떠 있고 7.0초에 1-tap buy 를 누른다. 그 사이는 같은 시트라 잘라도 안 보인다
    # - Play 의 `Payment successful` ✓ 까지 쓰고, 이어지는 Play Points 안내(9.9초~)는 뺀다
    # - 앱의 확인 화면은 멈춰 있어 녹화에 남지 않으므로 스크린샷, 마지막은 `Start reading` 을 누른 순간부터
    # 증명은 "스토어 결제 성공 → 앱의 서가 상태가 바뀜" 의 연결이다 (콘티 #8 비고).
    #
    # **자막은 뒤 두 조각에만 얹는다.** 자막 자리가 폰 화면 아래쪽인데, 결제 시트는 어두운 바탕이라
    # 검은 자막이 안 보이고 시트의 글자와 겹쳤다. 문장의 뜻도 "열렸다" 는 확인 화면에 맞는다 (7.15초).
    "08-purchase": [
        ("raw-android/08-shelf.png",          0.0,  2.0,  None, False),
        ("raw-android/08-purchase.mp4",       2.4,  4.0,  None, False),
        ("raw-android/08-purchase.mp4",       6.95, 2.85, None, False),
        ("raw-android/08-confirm.png",        0.0,  4.65, None),
        ("raw-android/08-start-reading.mp4",  9.5,  2.5,  None),
    ],
    "06-widgets": [
        ("raw-ios/06-widget-ios.png",         0.0, 5.5, (1.00, 1.08, 0.5, 0.12)),
        ("raw-android/06-widget-android.mp4", 0.0, 9.5, (1.00, 1.04, 0.5, 0.15)),
    ],
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

#: 컷 안에서 칠할 단어. (찾을 단어들, 원본에서 문장이 자리 잡은 시각, 칠하기 시작하는
#: 컷 시각, 번지는 길이)
#:
#: #3 은 "날씨가 페이지를 고른다" 를 말하는 컷인데, 그 근거 — 발췌문에 오늘 날씨의 단어가
#: 들어 있어야 뽑힌다는 선별 기준 — 는 앱 화면에 따로 표시되지 않는다. 그래서 편집이 칠한다.
#: 날씨 줄의 `rain` 과 발췌문의 `rain` 이 같은 색으로 켜지면 둘이 이어진다.
#:
#: 자리는 `scripts/find_words.swift`(macOS 문자 인식)가 원본 프레임에서 찾는다. 좌표를
#: 박지 않으므로 다시 찍어도 표를 고칠 필요가 없다.
HIGHLIGHTS = {
    "03-matching": (["rain"], 8.0, 4.0, 1.0),
}

#: 강조 색. 종이에 **곱한다** — 위에 덮으면 잉크까지 물들어 글자가 흐려진다.
#: 곱하면 종이만 옅은 모래색이 되고 글자는 그대로 검다.
HIGHLIGHT = "0xF3E3B3"

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
                move: tuple[float, float, float, float] | None = None,
                highlight: tuple[list[tuple[int, int, int, int]], float, float] | None = None,
                ) -> None:
    """한 컷: 잘라내기 → 시스템 영역 제거 → 16:9 종이 위에 올리기 → 구석 라벨.

    [highlight] 는 (시스템 영역을 뺀 원본 좌표의 상자들, 시작, 번지는 길이).

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

    crop = f"crop=in_w:in_h-{crop_top + crop_bottom}:0:{crop_top},"
    mask = ""
    if highlight:
        # **줌보다 먼저 칠한다** — 그래야 강조가 글자와 함께 움직인다. 흰 판에 상자를
        # 그리고 흐려서 가장자리를 누그러뜨린 뒤, 흰색에서 번져 나오게 한다.
        # 곱하기는 두 입력의 시각이 같은 간격이어야 번짐이 매끄럽다. 그래서 원본도
        # 여기서 60fps 로 채운다 (가변 프레임 녹화에는 정지 구간에 프레임이 없다).
        boxes, at, fade = highlight
        fw, fh = frame_size(src)
        fh -= crop_top + crop_bottom
        draws = "".join(f"drawbox=x={x}:y={y}:w={w}:h={h}:color={HIGHLIGHT}:t=fill,"
                        for x, y, w, h in boxes)
        mask = (f"color=c=white:s={fw}x{fh}:r=60:d={length + 1},format=gbrp,{draws}"
                f"gblur=sigma=3,fade=t=in:st={at}:d={fade}:color=white[hl];")
        # **왕복 변환을 정확하게 한다.** 곱하기는 RGB 에서 해야 맞는데, swscale 의 기본값은
        # 근사라 YUV → RGB → YUV 한 바퀴에 밝기가 두 단계 내려갔다 — 화면 전체가 종이 여백보다
        # 어두워져 경계가 보였다. 정밀 플래그를 주면 한 바퀴 돌아도 값이 그대로다.
        # 기기 녹화는 BT.709 · TV 범위다. 흰 판은 처음부터 RGB 라 255 가 정확히 255 다.
        exact = "flags=accurate_rnd+full_chroma_int"
        crop = (f"fps=60,{crop}scale=in_color_matrix=bt709:in_range=tv:{exact},"
                f"format=gbrp[cut];"
                f"[cut][hl]blend=all_mode=multiply,"
                f"scale=out_color_matrix=bt709:out_range=tv:{exact},format=yuv420p,")
    elif move and not still:
        # **zoompan 은 입력 한 장에 출력 한 장을 낸다** — 시각을 보지 않는다. 가변 프레임
        # 녹화를 그대로 넣으면 화면이 멈춰 있던 구간이 한 프레임으로 접힌다. #6 에서 위젯을
        # 보여주던 2.6초가 사라지고 앱이 바로 열렸다. 먼저 60fps 로 채운다.
        crop = f"fps=60,{crop}"

    patch = "".join(
        f"split[pa{i}][pb{i}];[pb{i}]crop={w}:{h}:{x - w}:{y}[pc{i}];"
        f"[pa{i}][pc{i}]overlay={x}:{y},"
        for i, (x, y, w, h) in enumerate(DEVICE_PATCHES.get(frame_size(src), [])))
    base = (
        f"{mask}{head}{patch}"
        f"{crop}"
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


def find_words(src: str, words: list[str], at: float, crop_top: int
               ) -> list[tuple[int, int, int, int]]:
    """원본의 [at] 초 프레임에서 [words] 의 자리를 찾는다. 시스템 영역을 뺀 좌표로 준다."""
    with tempfile.TemporaryDirectory() as tmp:
        frame = f"{tmp}/frame.png"
        run(["ffmpeg", "-v", "error", "-ss", f"{at}", "-i", src, "-frames:v", "1", "-y", frame])
        out = subprocess.run(["swift", f"{ROOT}/scripts/find_words.swift", frame, *words],
                             capture_output=True, text=True).stdout
    boxes = []
    for line in out.splitlines():
        _, x, y, w, h = line.split()
        # 글자 상자에 살짝 여유를 둔다. 딱 맞으면 칠이 아니라 밑줄처럼 보인다.
        boxes.append((int(x) - 8, int(y) - crop_top - 2, int(w) + 16, int(h) + 4))
    if not boxes:
        raise SystemExit(f"{src} 의 {at}초 프레임에서 {words} 를 못 찾았다.")
    return boxes


def page_at(src: str) -> float:
    """촬영 메뉴가 사라진 첫 프레임의 시각 (`MENU_CLOSE_AREA`)."""
    top, bottom = crop_for(src)
    w, h = frame_size(src)
    h -= top + bottom
    ax, ay, aw, ah = MENU_CLOSE_AREA
    cx, cy, cw, ch = int(w * ax), int(h * ay), int(w * aw), int(h * ah)
    # 가변 프레임 그대로 푼다. 시각 목록과 한 장씩 짝지어야 한다.
    raw = subprocess.run(
        ["ffmpeg", "-v", "error", "-t", "3", "-i", src, "-fps_mode", "passthrough",
         "-vf", f"format=gray,crop={cw}:{ch}:{cx}:{top + cy}", "-f", "rawvideo", "-"],
        capture_output=True).stdout
    times = subprocess.run(
        ["ffprobe", "-v", "error", "-select_streams", "v:0", "-read_intervals", "%+3",
         "-show_entries", "frame=pts_time", "-of", "csv=p=0", src],
        capture_output=True, text=True).stdout.split()
    size = cw * ch
    for i, t in enumerate(times[:len(raw) // size]):
        if min(raw[i * size:(i + 1) * size]) >= CLEAN_LUMA:
            return float(t)
    raise SystemExit(f"{src} 의 앞 3초에서 촬영 메뉴가 닫히지 않았다.")


def montage(raw_dir: str, out_path: str) -> None:
    with tempfile.TemporaryDirectory() as tmp:
        parts = []
        for name, length, label in MONTAGE:
            src = f"{raw_dir}/{name}.mp4"
            if not os.path.isfile(src):
                raise SystemExit(f"녹화가 없다: {src}")
            dst = f"{tmp}/{name}.mp4"
            start = page_at(src)
            prepare_cut(src, dst, start=start, length=length,
                        label=label, cache_dir=tmp)
            parts.append(dst)
            print(f"  {name:10s} {length:>4.1f}초  ({label})  ← {start:.2f}초부터")

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
#: 값은 **눈으로 찾는다.** 넘김 전에는 화면이 멈춰 있어 가변 프레임 녹화에 프레임이
#: 없으니 "긴 정지 구간이 끝나는 프레임" 이 후보지만, 그게 넘김이라는 보장은 없다 —
#: iPhone 녹화에는 넘김 2초 전에 다시 그리기가 한 번 끼어 있어서(3.53초) 그 값을 넣었더니
#: 두 폰이 0.35초 어긋나 넘어갔다. 넘김은 스프링이라 0.2초면 90% 가 끝나므로,
#: 첫 프레임에서 기울기가 보이면 그 40ms 앞이 시작이다.
#:
#: 원본은 `scripts/record_kmp.py` 가 찍는다 (2026-09-28, 잠긴 서가, clear · day).
SPLIT_SOURCES = [
    ("raw-android/07-kmp-android.mp4", 5.10, "Android"),
    ("raw-ios/07-kmp-ios.mp4",         5.88, "iPhone"),
]

#: 끝에 들어오는 데스크톱 창 (정지 화면, 라벨). **있을 때만** 마지막 `SPLIT_TRIO` 초를
#: 셋이 나눠 쓴다 — 콘티의 "데스크톱이 들어와 셋이 같은 문장" 이다. 가운데에 놓는다.
SPLIT_DESKTOP = ("raw-desktop/07-kmp-desktop.png", "Desktop")
SPLIT_TRIO = 2.0

#: 첫 글자 줄을 찾을 때의 밝기 (회색조). 종이(FBF9F4)는 249, 창의 제목 표시줄은 241,
#: 날짜 줄의 흐린 글자(8A8378)는 131 이다.
PAPER_LUMA = 245
INK_LUMA = 180

#: 컷 안에서 넘김이 일어나는 시각. 앞은 "같은 문장" 을 보여주고, 뒤는 읽을 시간이다.
SPLIT_TURN_AT = 2.2

#: 논리 1pt 가 몇 픽셀이 되는가. 두 화면일 때는 iPhone(402pt)이 680px 이 되게 잡았다.
#:
#: **세로로 꽉 채우면 본문이 안 읽힌다** — 1080p 에서 폰 비율을 전부 담으면 폭이
#: 552px 이고 본문 17sp 는 23px 로 뭉갠다 (콘티가 우려한 그대로다). 페이지 아래쪽은
#: 빈 종이이므로 **잘라내고 키운다.**
SPLIT_SCALE = 680 / 402
SPLIT_PHONE_H = 880
SPLIT_TOP = 40
SPLIT_GAP = 100

#: 셋일 때. 폭이 모자라 작아지지만 2초뿐이고, 그 2초에 읽을 것은 "같다" 는 사실이다.
TRIO_SCALE = 1.30
TRIO_H = 880
TRIO_GAP = 60

#: 겹치는 길이. 두 화면에서 셋으로 넘어갈 때 한 프레임에 전부 자리가 바뀌면 튀어서
#: 아주 짧게 겹친다.
TRIO_FADE = 0.3


def points(path: str) -> float:
    size = frame_size(path)
    if size not in DEVICE_POINTS:
        known = ", ".join(f"{w}x{h}" for w, h in DEVICE_POINTS)
        raise SystemExit(
            f"{size[0]}x{size[1]} 의 논리 폭을 모른다 ({path}).\n"
            f"아는 기기: {known}. 분할은 글자 크기를 맞춰야 하므로 DEVICE_POINTS 에 한 줄 더할 것.")
    return DEVICE_POINTS[size]


def ink_top(src: str, at: float) -> int:
    """시스템 영역을 뺀 원본의 [at] 초 프레임에서 **첫 글자 줄의 윗끝** (원본 픽셀).

    창의 제목 표시줄은 건너뛴다 — 한 줄이 통째로 종이색인 곳이 처음 나온 뒤부터 찾는다.
    가장자리 2% 는 보지 않는다. 창 캡처의 테두리 한 픽셀이 종이색이 아니다.
    """
    top, bottom = crop_for(src)
    w, h = frame_size(src)
    h -= top + bottom
    x0 = w // 50
    cw = w - 2 * x0
    # 분할은 녹화 끝을 복제해 늘려 쓰므로 [at] 이 파일보다 뒤일 수 있다. 그때는 마지막
    # 프레임인데, 가변 프레임 녹화는 마지막 장이 파일 끝보다 0.1초쯤 앞에 있어서 끝 근처로
    # 탐색하면 아무것도 안 나온다. 끝 1초를 전부 풀고 마지막 장을 쓴다.
    if src.lower().endswith(".png"):
        pick = ["-i", src, "-frames:v", "1"]
    elif at < duration(src) - 0.5:
        pick = ["-ss", f"{at}", "-i", src, "-frames:v", "1"]
    else:
        pick = ["-sseof", "-1", "-i", src]
    raw = subprocess.run(
        ["ffmpeg", "-v", "error", *pick,
         "-vf", f"crop={cw}:{h}:{x0}:{top},format=gray", "-f", "rawvideo", "-"],
        capture_output=True).stdout
    raw = raw[len(raw) - len(raw) % (cw * h) - cw * h:] if len(raw) >= cw * h else b""
    rows = [raw[y * cw:(y + 1) * cw] for y in range(len(raw) // cw)]
    paper = next((y for y, r in enumerate(rows) if min(r) >= PAPER_LUMA), None)
    ink = next((y for y in range(paper or 0, len(rows)) if min(rows[y]) < INK_LUMA), None)
    if paper is None or ink is None:
        raise SystemExit(f"{src} 의 {at}초 프레임에서 글자 줄을 못 찾았다.")
    return ink


def align_tops(tiles: list[dict], top: int, height: int) -> None:
    """나란히 놓은 화면들의 **첫 글자 줄(날짜)** 을 한 높이에 맞춘다. 아래 끝은 셋이 같다.

    폰은 상태바를 잘라냈고 창은 제목 표시줄을 남겼다. 게다가 앱의 위 여백이 표면마다
    달라서 — 9/28 캡처에서 창의 날짜 줄은 폰보다 70px 아래, 두 폰끼리도 20px 어긋났다 —
    제목 표시줄 높이만 빼서는 맞지 않는다. 그래서 재지 않고 **찾는다.** 가장 아래에 있는
    것에 맞추므로 나머지는 그만큼 내려가고, 내려간 만큼 아래의 빈 종이를 덜 보인다.
    """
    offsets = [ink_top(t["src"], t["start"]) * t["w"] / frame_size(t["src"])[0] for t in tiles]
    lowest = max(offsets)
    for t, offset in zip(tiles, offsets):
        shift = int(round((lowest - offset) / 2)) * 2   # yuv420 은 짝수 높이여야 한다
        t["y"], t["h"] = top + shift, height - shift


def compose(tiles: list[dict], length: float, line: str | None, dst: str, tmp: str,
            label_size: int = 30) -> None:
    """화면 여럿을 종이 위에 나란히 놓는다.

    tile: src, start(원본 시각), x, y, w, h, label, crop_top(더 잘라낼 위쪽 픽셀).
    **`fps` 가 `trim` 앞에 온다.** 기기 녹화는 가변 프레임이라 정지 구간에 프레임이
    없는데, 그 상태로 잘라내면 `setpts=PTS-STARTPTS` 가 빈 시간을 통째로 접어버린다 —
    자른 지점이 벽시계가 아니라 "그 이후 첫 프레임" 이 된다. 한 파일만 다룰 때는
    그래도 되지만(`prepare_cut` 이 그렇다), **여러 기기를 같은 순간에 맞추려면 시각이
    진짜여야 한다.** 녹화가 컷보다 먼저 끝나도 되게 `tpad` 도 `trim` 앞에 둔다.
    """
    inputs, chains = [], []
    for i, t in enumerate(tiles):
        src = t["src"]
        top, bottom = crop_for(src)
        top += t.get("crop_top", 0)
        if src.lower().endswith(".png"):
            inputs += ["-loop", "1", "-framerate", "60", "-t", f"{length + 1}", "-i", src]
            head = f"[{i}:v]"
        else:
            inputs += ["-i", src]
            head = (f"[{i}:v]fps=60,tpad=stop_mode=clone:stop_duration=30,"
                    f"trim=start={t['start']},setpts=PTS-STARTPTS,")
        chains.append(
            f"{head}crop=in_w:in_h-{top + bottom}:0:{top},"
            f"scale={t['w']}:-2,crop={t['w']}:{t['h']}:0:0[p{i}]"
        )
    chains.append(f"color=c={PAPER}:s={OUT_W}x{OUT_H}:r=60[bg]")

    last = "[bg]"
    for i, t in enumerate(tiles):
        chains.append(f"{last}[p{i}]overlay=x={t['x']}:y={t['y']}[s{i}]")
        last = f"[s{i}]"

    # 라벨은 화면 아래, 자막은 그 아래다.
    n = len(tiles)
    for i, t in enumerate(tiles):
        inputs += ["-i", render_text(t["label"], label_size, "#8A8378", tmp)]
        label_y = t["label_y"]
        chains.append(f"{last}[{n + i}:v]overlay=x={t['x']}+({t['w']}-w)/2:y={label_y}[l{i}]")
        last = f"[l{i}]"
    if line:
        inputs += ["-i", render_text(line, 46, "#1A1A1A", tmp)]
        chains.append(f"{last}[{2 * n}:v]overlay=x=(W-w)/2:y=H-H*0.075[final]")
        last = "[final]"

    chain = ";".join(chains) + f";{last}format=yuv420p[out]"
    run(["ffmpeg", "-v", "error", *inputs, "-filter_complex", chain,
         "-map", "[out]", "-an", "-t", f"{length}",
         "-c:v", "libx264", "-preset", "medium", "-crf", "16", "-y", dst])


def split(raw_root: str, parts_dir: str, name: str = "07a-kmp") -> None:
    """기기 녹화를 한 프레임에 나란히 놓는다. 넘김은 같은 순간에 일어난다.

    데스크톱 캡처가 있으면 끝 `SPLIT_TRIO` 초는 셋이다. 없으면 끝까지 둘이다.
    """
    lengths = {n: length for n, length, _ in TIMELINE}
    numbers = {n: number for n, _, number in TIMELINE}
    length = lengths[name]
    line = SUBTITLES[numbers[name]].replace("{N}", YEARS) if numbers[name] else None

    phones = []
    for rel, turn_at, label in SPLIT_SOURCES:
        src = os.path.join(raw_root, rel)
        if not os.path.isfile(src):
            raise SystemExit(f"녹화가 없다: {src}")
        phones.append((src, turn_at, label))
    desk_src = os.path.join(raw_root, SPLIT_DESKTOP[0])
    trio = os.path.isfile(desk_src)
    duo_length = length - SPLIT_TRIO if trio else length

    os.makedirs(parts_dir, exist_ok=True)
    dst = f"{parts_dir}/{name}.mp4"

    with tempfile.TemporaryDirectory() as tmp:
        # 둘: 논리 폭에 비례한 폭으로 나란히 놓아 글자 크기를 맞춘다.
        widths = [int(round(points(src) * SPLIT_SCALE / 2) * 2) for src, _, _ in phones]
        x = (OUT_W - sum(widths) - SPLIT_GAP * (len(phones) - 1)) // 2
        duo = []
        for (src, turn_at, label), w in zip(phones, widths):
            duo.append(dict(src=src, start=max(turn_at - SPLIT_TURN_AT, 0.0), x=x, y=SPLIT_TOP,
                            w=w, h=SPLIT_PHONE_H, label=label,
                            label_y=SPLIT_TOP + SPLIT_PHONE_H + 14))
            x += w + SPLIT_GAP
        align_tops(duo, SPLIT_TOP, SPLIT_PHONE_H)
        duo_dst = dst if not trio else f"{tmp}/duo.mp4"
        compose(duo, duo_length + (TRIO_FADE if trio else 0), line, duo_dst, tmp)

        if trio:
            # 셋: 데스크톱을 가운데에. 폰은 넘긴 뒤의 정지 화면이 이어진다.
            order = [phones[0], (desk_src, None, SPLIT_DESKTOP[1]), phones[1]]
            widths = [int(round(points(src) * TRIO_SCALE / 2) * 2) for src, _, _ in order]
            x = (OUT_W - sum(widths) - TRIO_GAP * (len(order) - 1)) // 2
            tiles = []
            for (src, turn_at, label), w in zip(order, widths):
                desk = turn_at is None
                tiles.append(dict(
                    src=src, x=x, w=w, label=label,
                    start=0.0 if desk else max(turn_at - SPLIT_TURN_AT, 0.0) + duo_length,
                    label_y=SPLIT_TOP + TRIO_H + 14))
                x += w + TRIO_GAP
            align_tops(tiles, SPLIT_TOP, TRIO_H)
            trio_dst = f"{tmp}/trio.mp4"
            compose(tiles, SPLIT_TRIO, line, trio_dst, tmp)
            run(["ffmpeg", "-v", "error", "-i", duo_dst, "-i", trio_dst, "-filter_complex",
                 f"[0:v][1:v]xfade=transition=fade:duration={TRIO_FADE}:offset={duo_length},"
                 f"format=yuv420p",
                 "-an", "-c:v", "libx264", "-preset", "medium", "-crf", "16", "-y", dst])

    made = duration(dst)
    mark = "\u2713" if abs(made - length) < 0.15 else "!"
    shape = "둘 → 셋" if trio else "둘 (데스크톱 캡처 없음)"
    print(f"  {mark} {name}  {length:.1f}초 (실제 {made:.2f})  ← 분할 {shape}")


def sequence(name: str, raw_root: str, parts_dir: str) -> None:
    """`SEQUENCES` 의 조각들을 각각 만들어 하드 컷으로 잇는다."""
    lengths = {n: length for n, length, _ in TIMELINE}
    numbers = {n: number for n, _, number in TIMELINE}
    line = SUBTITLES[numbers[name]].replace("{N}", YEARS) if numbers[name] else None
    pieces = SEQUENCES[name]
    # 조각은 (원본, 시작, 길이, 카메라) 에 자막을 얹을지가 붙을 수 있다. 없으면 얹는다.
    pieces = [(*p, True) if len(p) == 4 else p for p in pieces]
    total = sum(length for _, _, length, _, _ in pieces)
    if abs(total - lengths[name]) > 0.01:
        raise SystemExit(f"{name} 조각의 합이 {total:.2f}초다. 콘티는 {lengths[name]:.1f}초.")

    os.makedirs(parts_dir, exist_ok=True)
    dst = f"{parts_dir}/{name}.mp4"
    with tempfile.TemporaryDirectory() as tmp:
        listing = f"{tmp}/parts.txt"
        with open(listing, "w") as f:
            for i, (rel, start, length, move, subtitled) in enumerate(pieces):
                src = os.path.join(raw_root, rel)
                if not os.path.isfile(src):
                    raise SystemExit(f"녹화가 없다: {src}")
                part = f"{tmp}/part{i}.mp4"
                prepare_cut(src, part, start=start, length=length, label=None,
                            cache_dir=tmp, subtitle=line if subtitled else None, move=move)
                f.write(f"file '{part}'\n")
        run(["ffmpeg", "-v", "error", "-f", "concat", "-safe", "0",
             "-i", listing, "-c", "copy", "-y", dst])
    made = duration(dst)
    mark = "✓" if abs(made - lengths[name]) < 0.15 else "!"
    shape = " → ".join(os.path.basename(p[0]) for p in pieces)
    print(f"  {mark} {name}  {lengths[name]:.1f}초 (실제 {made:.2f})  ← {shape}")


def cut(name: str, raw_root: str, parts_dir: str) -> None:
    """컷 하나를 원본에서 잘라낸다. 길이는 콘티가 정하고, 시작은 `SOURCES` 가 정한다."""
    lengths = {n: length for n, length, _ in TIMELINE}
    if name not in lengths:
        raise SystemExit(f"콘티에 없는 컷: {name}\n있는 것: " + ", ".join(lengths))
    if name in SEQUENCES:
        sequence(name, raw_root, parts_dir)
        return
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
    highlight = None
    if name in HIGHLIGHTS:
        words, settled_at, at, fade = HIGHLIGHTS[name]
        highlight = (find_words(src, words, settled_at, crop_for(src)[0]), at, fade)
    with tempfile.TemporaryDirectory() as tmp:
        if card is None:
            prepare_cut(src, dst, start=start, length=lengths[name],
                        label=None, cache_dir=tmp, subtitle=line,
                        move=MOVES.get(name), highlight=highlight)
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
