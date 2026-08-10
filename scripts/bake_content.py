#!/usr/bin/env python3
"""
큐레이션 CSV → content.db 로 굽는다.

`content.db` 는 앱과 함께 배포되고 업데이트마다 통째로 교체되므로 마이그레이션이 없다.
빌드할 때마다 이 스크립트가 처음부터 다시 만든다.

사용:
    python3 scripts/bake_content.py --source <CSV 디렉터리> --out <출력 .db>
    python3 scripts/bake_content.py --source ... --out ... --dry-run   # 검증만

의존성 없음 (Python 3 표준 라이브러리만).
"""

from __future__ import annotations

import argparse
import csv
import glob
import json
import os
import sqlite3
import sys
from collections import Counter, defaultdict

# --- 도메인 상수. shared/core 의 Kotlin enum 과 반드시 일치해야 한다 -------------

WEATHER_GROUPS = ["clear", "cloudy", "fog", "drizzle", "rain", "snow", "thunder", "wind"]
TIMES_OF_DAY = ["morning", "day", "evening_night"]

# CSV 의 bucket 어휘 → DB 어휘. 다르면 조용히 틀리는 게 아니라 여기서 잡는다.
BUCKET_ALIASES = {
    "cloud": "cloudy",
    "clouds": "cloudy",
    "cloudy": "cloudy",
    "clear": "clear",
    "fog": "fog",
    "drizzle": "drizzle",
    "rain": "rain",
    "snow": "snow",
    "thunder": "thunder",
    "storm": "thunder",
    "wind": "wind",
}

TIME_ALIASES = {
    "morning": "morning",
    "dawn": "morning",
    "day": "day",
    "noon": "day",
    "afternoon": "day",
    "evening": "evening_night",
    "night": "evening_night",
    "evening_night": "evening_night",
    "dusk": "evening_night",
}

KEEP_TRUE = {"1", "y", "yes", "true", "keep", "o", "ok", "ㅇ"}
KEEP_FALSE = {"", "0", "n", "no", "false", "x", "drop", "-"}

# DB 에 싣지 않는 큐레이션 작업용 컬럼. 여기 없는 새 컬럼이 나오면 경고한다.
PROCESS_ONLY_COLUMNS = {
    "keep", "note", "time_reason", "auto_flag", "bucket_suggest", "heuristic_rank",
    "bucket", "bucket_all", "excerpt_ko", "weather_word", "weather_phrase",
    "weather_state", "weather_rule", "weather_specificity", "word_position",
    "position_ok", "proper_noun_count", "person_marker", "voice", "person_ref",
    "dialogue", "time_guess", "time_candidates", "time_evidence", "time_conflict",
    "source_start", "source_end", "source_center", "review_flags", "time_draft",
}

# SQLDelight 의 ContentDatabase.Schema.version 과 반드시 같아야 한다.
# 스키마를 바꾸면 여기도 올린다.
SCHEMA_VERSION = 1

SCHEMA = """
CREATE TABLE packs (
    id            TEXT    NOT NULL PRIMARY KEY,
    title         TEXT    NOT NULL,
    subtitle      TEXT,
    is_base       INTEGER NOT NULL DEFAULT 0,
    auto_grant    INTEGER NOT NULL DEFAULT 0,
    sort_order    INTEGER NOT NULL DEFAULT 0
);
CREATE TABLE entries (
    id            INTEGER NOT NULL PRIMARY KEY,
    pack_id       TEXT    NOT NULL REFERENCES packs(id),
    text          TEXT    NOT NULL,
    author        TEXT    NOT NULL,
    title         TEXT    NOT NULL,
    section       TEXT,
    year          INTEGER NOT NULL,
    source_id     TEXT    NOT NULL,
    language      TEXT    NOT NULL,
    time_of_day   TEXT,
    tier          TEXT,
    season_weight TEXT,
    temp_weight   TEXT,
    word_count    INTEGER NOT NULL,
    license_note  TEXT    NOT NULL
);
CREATE TABLE entry_weather_groups (
    entry_id      INTEGER NOT NULL REFERENCES entries(id),
    weather_group TEXT    NOT NULL,
    PRIMARY KEY (entry_id, weather_group)
);
CREATE INDEX entries_lookup ON entries (language, pack_id, time_of_day);
CREATE INDEX entry_weather_groups_lookup ON entry_weather_groups (weather_group, entry_id);
"""


def fnv1a64(s: str) -> int:
    """shared/core 의 Hashing.kt 와 같은 알고리즘. 여기서는 안정적인 id 생성에만 쓴다."""
    h = 14695981039346656037
    for b in s.encode("utf-8"):
        h = ((h ^ b) * 1099511628211) & 0xFFFFFFFFFFFFFFFF
    return h


def stable_entry_id(book_id: str, start: str, end: str) -> int:
    """
    엔트리 id 는 **재빌드 사이에 반드시 안정적**이어야 한다.

    user.db 의 daily_page.entry_id 가 이 값을 참조한다. 행 순서로 id 를 매기면
    문장을 하나 추가하는 순간 기존 유저의 아카이브가 전부 다른 문장을 가리킨다.

    그래서 원문에서의 위치(책 번호 + 발췌 구간)로부터 유도한다.
    발췌문을 다듬어도 위치는 안 변하므로 id 가 유지된다.
    """
    return fnv1a64(f"{book_id}:{start}:{end}") & 0x7FFFFFFFFFFFFFFF


class BakeError(Exception):
    pass


def parse_keep(raw: str, where: str) -> bool:
    v = (raw or "").strip().lower()
    if v in KEEP_TRUE:
        return True
    if v in KEEP_FALSE:
        return False
    raise BakeError(f"{where}: keep 값을 해석할 수 없다: {raw!r} (허용: {sorted(KEEP_TRUE)} / 빈값)")


def parse_groups(row: dict, where: str) -> list[str]:
    raw = (row.get("bucket_all") or "").strip() or (row.get("bucket") or "").strip()
    if not raw:
        raise BakeError(f"{where}: bucket / bucket_all 이 모두 비어 있다")
    out = []
    for token in raw.split("|"):
        token = token.strip().lower()
        if not token:
            continue
        if token not in BUCKET_ALIASES:
            raise BakeError(f"{where}: 모르는 bucket 값 {token!r}")
        g = BUCKET_ALIASES[token]
        if g not in out:
            out.append(g)
    if not out:
        raise BakeError(f"{where}: 유효한 bucket 이 없다")
    return out


def parse_time_of_day(row: dict, where: str) -> str | None:
    """사람이 적은 time_draft 를 자동 추정 time_guess 보다 우선한다. 없으면 None(시간 무관)."""
    for column in ("time_draft", "time_guess"):
        v = (row.get(column) or "").strip().lower()
        if not v:
            continue
        if v not in TIME_ALIASES:
            raise BakeError(f"{where}: 모르는 {column} 값 {v!r}")
        return TIME_ALIASES[v]
    return None


def load_rows(source_dir: str, packs_cfg: dict) -> tuple[list[dict], dict]:
    stats = Counter()
    entries: dict[int, dict] = {}
    files = sorted(glob.glob(os.path.join(source_dir, "*.csv")))
    if not files:
        raise BakeError(f"CSV 를 찾을 수 없다: {source_dir}")

    default_pack = packs_cfg["default_pack"]
    known_packs = {p["id"] for p in packs_cfg["packs"]}
    unknown_columns: set[str] = set()

    for path in files:
        name = os.path.basename(path)
        with open(path, newline="", encoding="utf-8-sig") as f:
            reader = csv.DictReader(f)
            for column in reader.fieldnames or []:
                if column not in PROCESS_ONLY_COLUMNS and column not in {
                    "book_id", "title", "section", "author", "year", "tier",
                    "excerpt", "word_count", "language", "pack", "license_note",
                    "season_weight", "temp_weight",
                }:
                    unknown_columns.add(column)

            for lineno, row in enumerate(reader, start=2):
                where = f"{name}:{lineno}"
                stats["read"] += 1
                if not parse_keep(row.get("keep", ""), where):
                    stats["skipped_not_kept"] += 1
                    continue

                text = (row.get("excerpt") or "").strip()
                if not text:
                    raise BakeError(f"{where}: excerpt 가 비어 있는데 keep 됐다")

                pack_id = (row.get("pack") or "").strip() or default_pack
                if pack_id not in known_packs:
                    raise BakeError(f"{where}: 모르는 pack {pack_id!r} (packs.json 에 없다)")

                entry_id = stable_entry_id(
                    row.get("book_id", ""), row.get("source_start", ""), row.get("source_end", "")
                )
                if entry_id in entries:
                    raise BakeError(f"{where}: id 충돌 — 이미 {entries[entry_id]['_where']} 에서 나왔다")

                try:
                    year = int((row.get("year") or "").strip())
                except ValueError:
                    raise BakeError(f"{where}: year 가 정수가 아니다: {row.get('year')!r}")

                word_count = (row.get("word_count") or "").strip()
                word_count = int(word_count) if word_count.isdigit() else len(text.split())

                entries[entry_id] = {
                    "_where": where,
                    "id": entry_id,
                    "pack_id": pack_id,
                    "text": text,
                    "author": (row.get("author") or "").strip(),
                    "title": (row.get("title") or "").strip(),
                    "section": (row.get("section") or "").strip() or None,
                    "year": year,
                    "source_id": (row.get("book_id") or "").strip(),
                    "language": (row.get("language") or "en").strip(),
                    "time_of_day": parse_time_of_day(row, where),
                    "tier": (row.get("tier") or "").strip() or None,
                    "season_weight": (row.get("season_weight") or "").strip() or None,
                    "temp_weight": (row.get("temp_weight") or "").strip() or None,
                    "word_count": word_count,
                    "license_note": (row.get("license_note") or "").strip()
                    or "Public domain (Project Gutenberg)",
                    "groups": parse_groups(row, where),
                }
                stats["kept"] += 1

    if unknown_columns:
        print(f"  ⚠️  모르는 컬럼 (무시함): {sorted(unknown_columns)}", file=sys.stderr)
    return list(entries.values()), stats


def validate(entries: list[dict], packs_cfg: dict) -> list[str]:
    """치명적이지 않지만 알아야 하는 것들. 커버리지 구멍이 핵심이다."""
    problems = []
    if not entries:
        return ["keep 된 행이 하나도 없다 — 빈 DB 가 만들어진다"]

    auto_packs = {p["id"] for p in packs_cfg["packs"] if p.get("auto_grant")}
    base_packs = {p["id"] for p in packs_cfg["packs"] if p.get("is_base")}

    by_lang = defaultdict(list)
    for e in entries:
        by_lang[e["language"]].append(e)

    for language, rows in sorted(by_lang.items()):
        # 자동 지급 팩만 가진 유저(=무료)가 못 보는 그룹이 있으면 그 날씨에 화면이 빈다.
        for label, pack_set in (("무료(auto_grant)", auto_packs), ("베이스(is_base)", base_packs)):
            if not pack_set:
                continue
            covered = {g for e in rows if e["pack_id"] in pack_set for g in e["groups"]}
            missing = [g for g in WEATHER_GROUPS if g not in covered]
            if missing:
                problems.append(f"[{language}] {label} 팩이 못 덮는 그룹: {missing} → 그 날씨에 화면이 빈다")

        for group in WEATHER_GROUPS:
            pool = [e for e in rows if group in e["groups"]]
            if not pool:
                continue
            # 시간대 무관(None)은 세 슬롯을 모두 덮는다.
            anytime = sum(1 for e in pool if e["time_of_day"] is None)
            for tod in TIMES_OF_DAY:
                n = anytime + sum(1 for e in pool if e["time_of_day"] == tod)
                if n < 2:
                    problems.append(f"[{language}] {group}/{tod} 후보 {n}개 — 반복이 즉시 온다")

        odd = [e for e in rows if not (40 <= e["word_count"] <= 80)]
        if odd:
            problems.append(f"[{language}] 40~80단어를 벗어난 문장 {len(odd)}개 (1순위 선별 기준)")
    return problems


def write_db(path: str, entries: list[dict], packs_cfg: dict) -> None:
    if os.path.exists(path):
        os.remove(path)
    os.makedirs(os.path.dirname(os.path.abspath(path)), exist_ok=True)

    con = sqlite3.connect(path)
    try:
        con.executescript(SCHEMA)
        con.executemany(
            "INSERT INTO packs (id, title, subtitle, is_base, auto_grant, sort_order) "
            "VALUES (:id, :title, :subtitle, :is_base, :auto_grant, :sort_order)",
            [
                {
                    "id": p["id"],
                    "title": p["title"],
                    "subtitle": p.get("subtitle"),
                    "is_base": 1 if p.get("is_base") else 0,
                    "auto_grant": 1 if p.get("auto_grant") else 0,
                    "sort_order": p.get("sort_order", 0),
                }
                for p in packs_cfg["packs"]
            ],
        )
        con.executemany(
            "INSERT INTO entries (id, pack_id, text, author, title, section, year, source_id, "
            "language, time_of_day, tier, season_weight, temp_weight, word_count, license_note) "
            "VALUES (:id, :pack_id, :text, :author, :title, :section, :year, :source_id, "
            ":language, :time_of_day, :tier, :season_weight, :temp_weight, :word_count, :license_note)",
            [{k: v for k, v in e.items() if k not in ("groups", "_where")} for e in entries],
        )
        con.executemany(
            "INSERT INTO entry_weather_groups (entry_id, weather_group) VALUES (?, ?)",
            [(e["id"], g) for e in entries for g in e["groups"]],
        )
        # SQLDelight 스키마 버전과 맞춘다.
        #
        # 이 값이 맞아야 드라이버가 번들 DB 를 "이미 최신" 으로 보고 create/migrate 를
        # 건너뛴다. 0 으로 두면 드라이버가 빈 DB 로 오인해 테이블을 다시 만들려 든다.
        con.execute(f"PRAGMA user_version = {SCHEMA_VERSION}")
        con.commit()
        con.execute("VACUUM")
    finally:
        con.close()


def main() -> int:
    ap = argparse.ArgumentParser(description="큐레이션 CSV → content.db")
    ap.add_argument("--source", required=True, help="CSV 디렉터리")
    ap.add_argument("--out", required=True, help="출력 .db 경로")
    ap.add_argument("--packs", default=os.path.join(os.path.dirname(__file__), "packs.json"))
    ap.add_argument("--dry-run", action="store_true", help="DB 를 쓰지 않고 검증만")
    ap.add_argument("--strict", action="store_true", help="검증 경고가 하나라도 있으면 실패")
    args = ap.parse_args()

    with open(args.packs, encoding="utf-8") as f:
        packs_cfg = json.load(f)

    try:
        entries, stats = load_rows(args.source, packs_cfg)
    except BakeError as e:
        print(f"❌ {e}", file=sys.stderr)
        return 1

    print(f"읽음 {stats['read']}행 · keep {stats['kept']}행 · 제외 {stats['skipped_not_kept']}행")

    by_pack = Counter(e["pack_id"] for e in entries)
    for pack_id, n in sorted(by_pack.items()):
        print(f"  pack {pack_id}: {n}개")

    group_counts = Counter(g for e in entries for g in e["groups"])
    print("  그룹별:", {g: group_counts.get(g, 0) for g in WEATHER_GROUPS})
    anytime = sum(1 for e in entries if e["time_of_day"] is None)
    print(f"  시간무관: {anytime} / 시간특화: {len(entries) - anytime}")

    problems = validate(entries, packs_cfg)
    for p in problems:
        print(f"  ⚠️  {p}", file=sys.stderr)
    if problems and args.strict:
        print("❌ --strict: 경고가 있어 중단한다", file=sys.stderr)
        return 1

    if args.dry_run:
        print("(dry-run — DB 를 쓰지 않았다)")
        return 0

    write_db(args.out, entries, packs_cfg)
    size = os.path.getsize(args.out)
    print(f"✅ {args.out} ({size:,} bytes)")
    return 0


if __name__ == "__main__":
    sys.exit(main())
