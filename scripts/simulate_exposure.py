#!/usr/bin/env python3
"""
노출 분포 시뮬레이션 — 선택 정책을 바꾸기 전에 숫자로 확인한다.

실행: python3 scripts/simulate_exposure.py

검증한 것 — 위젯은 슬롯을 고정하되 읽음으로 세지 않는다.

모델:
  · 하루 3슬롯 전부 위젯이 그린다 (슬롯 고정 O, 카운트 X)
  · 유저는 하루 N번 앱을 연다 → 그 슬롯만 '읽음'
  · 읽음이 균등 노출(최소 카운트 집합)의 유일한 입력
"""
import sqlite3, random
from collections import Counter, defaultdict

import os
DB = os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))), "content", "content.db")
SLOTS = ["morning", "day", "evening_night"]
WEATHER_P = {"cloudy": .34, "clear": .22, "rain": .20, "wind": .12,
             "fog": .06, "snow": .03, "drizzle": .02, "thunder": .01}


def fnv1a64(s):
    h = 14695981039346656037
    for b in s.encode():
        h = ((h ^ b) * 1099511628211) & 0xFFFFFFFFFFFFFFFF
    return h


con = sqlite3.connect(DB)
pools = defaultdict(list)
for eid, tod, pack in con.execute("SELECT id, time_of_day, pack_id FROM entries"):
    for (g,) in con.execute("SELECT weather_group FROM entry_weather_groups WHERE entry_id=?", (eid,)):
        for s in (SLOTS if tod is None else [tod]):
            pools[(g, s, pack)].append(eid)


def pool_for(group, slot, paid):
    ids = list(pools[(group, slot, "starter-2026")])
    if paid:
        ids += pools[(group, slot, "core-2026")]
    return sorted(set(ids))


def simulate(days, opens_per_day, use_readcounts, paid, seed=1):
    rng = random.Random(seed)
    history, read_count = [], Counter()
    read_entries = []
    for day in range(days):
        date = f"2027-{1 + day // 30:02d}-{1 + day % 30:02d}"
        opened = set(rng.sample(SLOTS, opens_per_day)) if opens_per_day else set()
        for slot in SLOTS:
            group = rng.choices(list(WEATHER_P), weights=list(WEATHER_P.values()))[0]
            ordered = pool_for(group, slot, paid)
            if not ordered:
                continue
            s = fnv1a64(f"{date}|{slot}|{group}|install")

            excl, cap = set(), min(30, len(ordered) - 1)
            for e in history:
                if len(excl) >= cap: break
                if e in ordered: excl.add(e)
            recent = [e for e in ordered if e not in excl] or ordered

            if use_readcounts:
                m = min(read_count[e] for e in recent)
                cand = [e for e in recent if read_count[e] == m]
            else:
                cand = recent

            pick = cand[s % len(cand)]
            history.insert(0, pick); del history[40:]
            if slot in opened:                     # 앱으로 실제로 읽은 슬롯만
                read_count[pick] += 1
                read_entries.append(pick)
    return read_entries, read_count


for paid in (False, True):
    total = len({e for k, v in pools.items() if paid or k[2] == "starter-2026" for e in v})
    print(f"\n{'=' * 72}\n{'유료 428편' if paid else '무료 86편'} · 1년 · 전체 {total}편\n{'=' * 72}")
    print(f"{'유저 습관':16}{'정책':12}{'읽은 글(종)':>12}{'못 읽은 글':>12}{'같은 글 최다':>12}")
    for opens, label in ((1, "하루 1번 엶"), (3, "슬롯마다 엶")):
        for use in (False, True):
            reads, cnt = simulate(365, opens, use, paid)
            distinct = len(set(reads))
            print(f"{label:16}{'균등 노출' if use else '현재':12}"
                  f"{distinct:>12}{total - distinct:>12}{max(cnt.values()):>12}")
