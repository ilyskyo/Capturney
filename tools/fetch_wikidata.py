#!/usr/bin/env python3
"""用 Wikidata 的标签给日语/韩语补释义，产出 assets/lexicon/gloss-<lang>.json。

为什么是 Wikidata
-----------------
它的标签以 **CC0** 释放，放进 MIT 仓库没有许可摩擦，也不需要署名条款跟着数据走；
而这些标签是各语言社区的人写下的，不是机器翻出来的。
`tools/build_lexicon.py` 的 docstring 一直引用这个文件名，但文件从来没被写过——
所以那 157 条 ja/ko 释义是手工来的，也没有任何工具能重生成它。这条补上。

拿什么消歧：我们自己已有的中文释义
----------------------------------
`cup` 在 Wikidata 里有好几个同名实体（杯子、杯赛、容量单位…），只按「英文标签完全相等」
会要么挑错、要么全放弃（实测放弃到只剩 2/12）。而 `en.json` 每条都带着 ECDICT 的**中文释义**，
那是人写的、我们本来就信任的数据。所以规则是：

1. 英文标签必须与查询词完全相等（忽略大小写）——先排掉「Book (职业)」这类；
2. 候选里**恰好一个**的中文标签出现在该词的中文释义里 → 收；
3. 中文那条判不出来时，只有在「候选唯一」的情况下才收；
4. ja 标签必须含假名或汉字、ko 必须含谚文，否则视为脏数据丢掉。

宁缺勿滥的理由：释义是**卡片背面要被评级的那一行**。给错一个词比少一个词糟得多——
用户会照着错的东西去记，而界面上一切正常，没人会去查。

用法
----
    python tools/fetch_wikidata.py --limit 1500                 # 按词频取前 N 个词
    python tools/fetch_wikidata.py --words my.txt --out-dir tools/out
    python tools/fetch_wikidata.py --limit 1500 --apply         # 写进 assets/lexicon/

进度存在 `<out-dir>/gloss-progress.json`，中断后重跑只查没查过的词。
网络失败**不记入进度**（记了就等于宣布「这个词没有」，一次抖动会变成永久缺口）。
"""

from __future__ import annotations

import argparse
import json
import sys
import time
import urllib.parse
import urllib.request
from pathlib import Path

# Wikimedia 要求带联系方式的 User-Agent；空 UA 会被 403。
USER_AGENT = "CapturneyLexiconBuilder/1.0 (https://github.com/ilyskyo/Capturney; data build)"
API = "https://www.wikidata.org/w/api.php"
IDS_PER_REQUEST = 50
SEARCH_LIMIT = 8
REQUEST_INTERVAL_S = 2.5
TIMEOUT_S = 25

LEXICON = Path("app/src/main/assets/lexicon/en.json")
ASSET_DIR = Path("app/src/main/assets/lexicon")

# 「这条标签真的是那个语言写的吗」。维基数据里不少 ja/ko 条目其实是拉丁转写或英文。
JA_RANGES = [(0x3040, 0x30FF), (0x31F0, 0x31FF), (0x4E00, 0x9FFF)]  # 平/片假名、假名扩展、汉字
KO_RANGES = [(0xAC00, 0xD7A3), (0x1100, 0x11FF)]                    # 谚文音节、谚文字母


class ApiUnavailable(RuntimeError):
    """网络/服务不可用。与「查不到」严格分开：后者是可以记进进度的结论，前者不行。"""


def has_script(text: str, ranges: list[tuple[int, int]]) -> bool:
    return any(lo <= ord(ch) <= hi for ch in text for lo, hi in ranges)


def api_get(params: dict[str, str], attempts: int = 4) -> dict:
    """带退避重试。失败**不**返回空 dict——那会被调用方误读成「没有」。"""
    query = urllib.parse.urlencode({**params, "format": "json"})
    last: Exception | None = None
    for attempt in range(attempts):
        try:
            request = urllib.request.Request(f"{API}?{query}", headers={"User-Agent": USER_AGENT})
            with urllib.request.urlopen(request, timeout=TIMEOUT_S) as response:
                payload = json.loads(response.read().decode("utf-8"))
            # 限速放在这里而不是调用点：漏掉一次就等于 20 个请求连发，
            # Wikimedia 的礼貌上限是按请求算的，被限流之后整批都要重来。
            time.sleep(REQUEST_INTERVAL_S)
            return payload
        except Exception as error:  # URLError / 超时 / 5xx / 被截断的 JSON
            last = error
            time.sleep(2.0 * (attempt + 1))
    raise ApiUnavailable(f"Wikidata 暂不可用：{last}")


def load_words(limit: int) -> list[tuple[str, str]]:
    """按词频排名返回 (英文词, 中文释义) 对；中文释义是消歧用的唯一依据。"""
    document = json.loads(LEXICON.read_text(encoding="utf-8"))
    ranked = sorted(document["entries"], key=lambda e: e.get("frequency", 10_000))
    out = []
    for entry in ranked:
        word = (entry.get("words") or {}).get("en")
        if not word:
            continue
        zh = (entry.get("glosses") or {}).get("zh") or (entry.get("words") or {}).get("zh") or ""
        out.append((word, zh))
        if len(out) >= limit:
            break
    return out


def search_candidates(word: str) -> list[str]:
    """英文标签与 word 完全相等的候选实体 id（按相关度顺序）。"""
    payload = api_get({
        "action": "wbsearchentities", "search": word, "language": "en",
        "uselang": "en", "limit": str(SEARCH_LIMIT), "type": "item",
    })
    return [h["id"] for h in payload.get("search", []) if h.get("label", "").strip().lower() == word.lower()]


def labels_for(ids: list[str]) -> dict[str, dict[str, str]]:
    """一次取回 en/zh/ja/ko 四种标签，按 IDS_PER_REQUEST 分片。"""
    out: dict[str, dict[str, str]] = {}
    langs = ["en", "zh", "ja", "ko"]
    for start in range(0, len(ids), IDS_PER_REQUEST):
        payload = api_get({
            "action": "wbgetentities", "ids": "|".join(ids[start:start + IDS_PER_REQUEST]),
            "props": "labels", "languages": "|".join(langs),
        })
        for qid, entity in (payload.get("entities") or {}).items():
            labels = entity.get("labels") or {}
            out[qid] = {lang: ((labels.get(lang) or {}).get("value") or "").strip() for lang in langs}
    return out


def pick(word: str, zh_gloss: str, candidates: list[str], labels: dict[str, dict[str, str]]) -> dict[str, str]:
    """从候选里挑一个，规则见模块 docstring。挑不出来就返回空 dict。"""
    if not candidates:
        return {}
    chosen = candidates[0] if len(candidates) == 1 else None
    if chosen is None and zh_gloss:
        agreeing = [qid for qid in candidates if labels.get(qid, {}).get("zh") and labels[qid]["zh"] in zh_gloss]
        chosen = agreeing[0] if len(agreeing) == 1 else None
    if chosen is None:
        return {}
    got = labels.get(chosen, {})
    value_ja, value_ko = got.get("ja", ""), got.get("ko", "")
    entry: dict[str, str] = {}
    if value_ja and has_script(value_ja, JA_RANGES):
        entry["ja"] = value_ja
    if value_ko and has_script(value_ko, KO_RANGES):
        entry["ko"] = value_ko
    return entry


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--limit", type=int, default=1500, help="按词频取前 N 个词")
    parser.add_argument("--words", default="", help="可选：自己给一份词表（一行一个词，可跟一个 TAB 加中文释义）")
    parser.add_argument("--out-dir", default="tools/out")
    parser.add_argument("--apply", action="store_true", help="写进 assets/lexicon/gloss-*.json")
    args = parser.parse_args()

    out_dir = Path(args.out_dir)
    out_dir.mkdir(parents=True, exist_ok=True)
    progress_path = out_dir / "gloss-progress.json"
    progress: dict[str, dict[str, str]] = (
        json.loads(progress_path.read_text(encoding="utf-8")) if progress_path.exists() else {}
    )

    pairs = load_words(args.limit)
    if args.words:
        manual = []
        for line in Path(args.words).read_text(encoding="utf-8").splitlines():
            if not line.strip():
                continue
            word, _, zh = line.partition("\t")
            manual.append((word.strip(), zh or dict(pairs).get(word.strip(), "")))
        pairs = manual

    todo = [(w, zh) for w, zh in pairs if w.lower() not in progress]
    print(f"{len(pairs)} 词待覆盖，其中 {len(todo)} 个没查过", file=sys.stderr)

    skipped: list[str] = []
    for start in range(0, len(todo), 20):
        batch = todo[start:start + 20]
        try:
            candidates = {word: search_candidates(word) for word, _ in batch}
        except ApiUnavailable as error:
            # 不写进度：写了就等于宣布「这些词查过了、没有」，一次抖动会变成永久缺口。
            skipped.extend(word for word, _ in batch)
            print(f"  跳过一批（{error}）", file=sys.stderr)
            continue
        ids = sorted({qid for qids in candidates.values() for qid in qids})
        try:
            labels = labels_for(ids)
        except ApiUnavailable as error:
            skipped.extend(word for word, _ in batch)
            print(f"  跳过一批标签（{error}）", file=sys.stderr)
            continue
        for word, zh in batch:
            progress[word.lower()] = pick(word, zh, candidates.get(word, []), labels)
        done = min(start + 20, len(todo))
        print(f"  已查 {done}/{len(todo)}，命中 {sum(1 for v in progress.values() if v)}", file=sys.stderr)
        progress_path.write_text(json.dumps(progress, ensure_ascii=False), encoding="utf-8")

    ja = [{"id": f"en.{w}", "word": v["ja"]} for w, v in sorted(progress.items()) if v.get("ja")]
    ko = [{"id": f"en.{w}", "word": v["ko"]} for w, v in sorted(progress.items()) if v.get("ko")]
    for name, entries in (("gloss-ja.json", ja), ("gloss-ko.json", ko)):
        (out_dir / name).write_text(
            json.dumps({"schemaVersion": 1, "language": name.split("-")[1].split(".")[0], "entries": entries},
                       ensure_ascii=False), encoding="utf-8")
    print(f"ja {len(ja)} 条 / ko {len(ko)} 条 → {out_dir}", file=sys.stderr)
    if skipped:
        print(f"因服务不可用跳过 {len(skipped)} 个词（未记入进度，重跑会再试）", file=sys.stderr)

    if args.apply:
        # 合并而不是覆盖：assets 里那份是手工攒下来的 157 条，而本次进度可能只覆盖了几十个词。
        # 直接写整个文件会把手工数据冲掉——那种丢失在文件行数上看不出来（行数照样「很多」），
        # 只有拿旧文件比才发现，所以这里就按 id 合并，并**拒绝**任何让条数变小的写入。
        for name, generated in (("gloss-ja.json", ja), ("gloss-ko.json", ko)):
            target = ASSET_DIR / name
            existing = (
                json.loads(target.read_text(encoding="utf-8")).get("entries", []) if target.exists() else []
            )
            by_id = {item["id"]: item["word"] for item in existing}
            added = 0
            for item in generated:
                if item["id"] in by_id:
                    continue  # 已有的值优先：手工挑过的释义比生成的更可信
                by_id[item["id"]] = item["word"]
                added += 1
            merged = [{"id": k, "word": v} for k, v in sorted(by_id.items())]
            if len(merged) < len(existing):
                raise SystemExit(f"拒绝写入 {name}：合并后 {len(merged)} 条 < 现有 {len(existing)} 条")
            target.write_text(
                json.dumps({"schemaVersion": 1, "language": name.split("-")[1].split(".")[0], "entries": merged},
                           ensure_ascii=False) + "\n",
                encoding="utf-8")
            print(f"{name}: 现有 {len(existing)} + 新增 {added} = {len(merged)} 条", file=sys.stderr)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
