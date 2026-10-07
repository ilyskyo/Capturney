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
2. 候选里**恰好一个**的中文标签出现在该词的中文释义里 → 收；判不出来就放弃。

第 2 条是唯一依据，不再有「候选唯一就直接收」的兜底。试过，那条兜底会放进错的东西：
`all` 与 `good` 在维基数据里各有一个同名实体（一个是桨/一切，一个是评价或牌子），
中文标签分别是「所有/全部」之外的具体物，于是兜底收下后给出 `オール`、`グッド`——
看着像译词，其实不是那个形容词/限定词。而 `time`、`year` 靠中文对齐稳稳命中「時間/시간」「年/년」。
错一个释义比少一个释义糟得多，所以宁可让命中率掉下来。

3. ja 标签必须含假名或汉字、ko 必须含谚文，否则视为脏数据丢掉。

宁缺勿滥的理由：释义是**卡片背面要被评级的那一行**。给错一个词比少一个词糟得多——
用户会照着错的东西去记，而界面上一切正常，没人会去查。

用法
----
    python tools/fetch_wikidata.py --limit 1500                 # 按词频取前 N 个词
    python tools/fetch_wikidata.py --words my.txt --out-dir tools/out
    python tools/fetch_wikidata.py --limit 1500 --apply         # 写进 assets/lexicon/
    python tools/fetch_wikidata.py --qids map.tsv --apply       # 人指认 QID，跳过所有自动消歧

`--qids` 是这条路剩下的唯一走法：map.tsv 一行一个「词<TAB>QID」，实体由人看着候选的
英文标签与 P31 类别指认，工具只负责取标签并过一遍字符集检查。它**不做**任何自动挑选，
所以也不会因为候选集抖动而挑错——代价是每个词都要有人读过一遍。

进度存在 `<out-dir>/gloss-progress.json`，中断后重跑只查没查过的词。
网络失败**不记入进度**（记了就等于宣布「这个词没有」，一次抖动会变成永久缺口）。
两种模式都只发 **ja 与 ko 都齐** 的词：`GlossOverlayCoverageTest` 要求两边覆盖同一批 id，
只补一边会让那份数据整体不合格。
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


def value_from(got: dict[str, str]) -> dict[str, str]:
    """从一个已确定身份的实体取出 ja/ko；不认的语言（拉丁转写、空值）留空。"""
    entry: dict[str, str] = {}
    for lang, ranges in (("ja", JA_RANGES), ("ko", KO_RANGES)):
        text = got.get(lang, "")
        if text and has_script(text, ranges):
            entry[lang] = text
    return entry


def pick(word: str, zh_gloss: str, candidates: list[str], labels: dict[str, dict[str, str]]) -> dict[str, str]:
    """从候选里挑一个。**只认「候选的中文标签是 ECDICT 释义的子串」这一条依据**。

    试过更强的做法并**退回**了：改成「按共享汉字数打分 + 唯一胜出」确实救回了
    `bakery`(西餅店 vs 面包店，共享 1 字)、`computer`(电子计算机 vs 电脑，共享 1 字)、`garden`，
    但同时产出 `airport → エアポート駅 (MARTA)` —— 一个**叫 Airport 的轨道交通站**。
    后来把三个词的候选与标签逐个打出来核过，退出的理由比「挑错实体」更硬，有两条：

    1. **繁简不对齐**：`airport` 的正确实体 Q1248784 中文标签是繁体「機場」，与简体的
       ECDICT 释义「飞机场」**共享 0 个字**（機≠机、場≠场），在打分里直接垫底；
       而那个站是简体「机场站」，共享 2 字。也就是说这条规则会**系统性地把正确答案排在后面**。
    2. **候选集不可复现**：`wbsearchentities` 两次调用返回的候选不一样。单独一次能看到
       Q409022「国际机场」也共享 2 字，本该与车站并列而被放弃；抓取那一次没拿到它，
       车站就成了唯一胜者。**一条结论会随网络调用抖动的规则，不能用来生成发布数据。**

    代价是明确的：子串规则会漏掉 airport/computer/bakery 这类「同一概念两种写法」。
    但漏掉的词界面照常显示英文，而错一个释义是**用户会照着去记**——
    这份数据没有人工复核环节，所以只能要一条「宁可漏、不许错」且**可复现**的规则。

    还试过**第三条**更强的：用 P31 (instance of) 的类别把「电影/车站/期刊」挡在外面。
    立论是子串规则不懂实体属于哪一类，而 P31 正好就是这一条断言。取真数据核过并**退回**，
    因为它在同一个方向上错得更狠：`airport` 的正解 Q1248784 的类别是 `type of aerodrome`
    （机场的上位词是 aerodrome 而不是 airport），「类别里含该词」会把它丢掉，
    却把两个 `airport railway station` 留下——**恰好挑反**。而 `bakery` 的正解 Q274393
    **一条 P31 都没有**（维基数据里标签比声明齐得多，越常见的小概念越可能没断言），
    所以「要求有类别」会丢掉已知正解，「不要求」就放进所有无断言候选：两头都堵不住。
    这三条错法都在 `tools/test_lexicon_rules.py` 里有对应的用例钉着，别只信这段注释。
    """
    if not candidates or not zh_gloss:
        return {}
    agreeing = [qid for qid in candidates if labels.get(qid, {}).get("zh") and labels[qid]["zh"] in zh_gloss]
    if len(agreeing) != 1:
        return {}
    return value_from(labels.get(agreeing[0], {}))


def write_outputs(progress: dict[str, dict[str, str]], out_dir: Path, apply: bool) -> int:
    """把进度落成 gloss-ja/ko.json，--apply 时合并进 assets。"""
    # GlossOverlayCoverageTest 要求 ja 与 ko 覆盖**同一批** id（少一边就是两边都不许发），
    # 所以只收两种语言都齐的词，并在这里把「只差一边」的那些摊出来给人看。
    both = {w: v for w, v in progress.items() if v.get("ja") and v.get("ko")}
    half = sorted(w for w, v in progress.items() if bool(v.get("ja")) != bool(v.get("ko")))
    ja = [{"id": f"en.{w}", "word": v["ja"]} for w, v in sorted(both.items())]
    ko = [{"id": f"en.{w}", "word": v["ko"]} for w, v in sorted(both.items())]
    if half:
        print(f"只有一种语言、本轮不发（{len(half)}）：{' '.join(half[:40])}", file=sys.stderr)
    for name, entries in (("gloss-ja.json", ja), ("gloss-ko.json", ko)):
        (out_dir / name).write_text(
            json.dumps({"schemaVersion": 1, "language": name.split("-")[1].split(".")[0], "entries": entries},
                       ensure_ascii=False), encoding="utf-8")
    print(f"ja {len(ja)} 条 / ko {len(ko)} 条 → {out_dir}", file=sys.stderr)

    if apply:
        # 合并而不是覆盖：assets 里那份是手工攒下来的，而本次进度可能只覆盖了几十个词。
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


def from_qids(path: Path) -> dict[str, dict[str, str]]:
    """按人工指认的「词<TAB>QID」表取标签——剩下那批判不出来的词只能走这条（见 pick 的 docstring）。

    规则挑实体之所以不能用，是因为候选集不可复现；而**指认**是可核对的：
    看一眼那个 QID 的英文标签和 P31 类别就能确认它是那个概念而不是某部电影或轨道站。
    标签本身仍来自 Wikidata（CC0），不由这里生成，所以这条路径不引入任何新数据源。
    """
    rows = []
    for line in path.read_text(encoding="utf-8").splitlines():
        if line.strip().startswith("#"):
            continue  # 这张表要提交进仓库，每一行都得带上「为什么是这一个实体」，而注释只能写在行里
        word, _, qid = line.partition("\t")
        if word.strip() and qid.strip():
            rows.append((word.strip().lower(), qid.strip()))
    labels = labels_for(sorted({qid for _, qid in rows}))
    progress: dict[str, dict[str, str]] = {}
    for word, qid in rows:
        got = labels.get(qid, {})
        # 英文标签与查询词不一致，多半是指认错了实体（把某个车站或电影当成了那个概念）。
        # 只报不丢：标签可能有历史写法，最终由读表的人判。
        if got.get("en", "").lower() != word:
            print(f"  注意：{word} 指认到 {qid}，其英文标签是 {got.get('en', '')!r}", file=sys.stderr)
        progress[word] = value_from(got)
    return progress


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--limit", type=int, default=1500, help="按词频取前 N 个词")
    parser.add_argument("--words", default="", help="可选：自己给一份词表（一行一个词，可跟一个 TAB 加中文释义）")
    parser.add_argument("--out-dir", default="tools/out")
    parser.add_argument("--apply", action="store_true", help="写进 assets/lexicon/gloss-*.json")
    parser.add_argument("--replay", default="",
                        help="不发任何请求，只按这份候选快照重新判定（用来验规则可复现）")
    parser.add_argument("--qids", default="",
                        help="人工指认的「词<TAB>QID」表：跳过所有自动消歧，只取这些实体的标签")
    args = parser.parse_args()

    out_dir = Path(args.out_dir)
    out_dir.mkdir(parents=True, exist_ok=True)
    progress_path = out_dir / "gloss-progress.json"
    progress: dict[str, dict[str, str]] = (
        json.loads(progress_path.read_text(encoding="utf-8")) if progress_path.exists() else {}
    )


    if args.replay:
        # 不发任何请求，只按快照重新判一遍，并把每个词的候选标签与得分摊开。
        # 存在的意义就是证伪「规则可复现」这件事：同一份快照跑两次，结论必须逐字相同。
        snap = json.loads(Path(args.replay).read_text(encoding="utf-8"))
        rows = []
        for word, record in sorted(snap.items()):
            entry = pick(word, record.get("gloss", ""), record["candidates"], record["labels"])
            rows.append({"word": word, "verdict": entry, "candidates": record["candidates"],
                         "zh": {q: (record["labels"].get(q) or {}).get("zh", "") for q in record["candidates"]}})
        Path(args.replay).with_name("replay.json").write_text(json.dumps(rows, ensure_ascii=False, indent=1), encoding="utf-8")
        hits = sum(1 for r in rows if r["verdict"])
        print(f"replay：{len(rows)} 词，判定 {hits} 条有值 -> {Path(args.replay).with_name('replay.json')}", file=sys.stderr)
        return 0

    if args.qids:
        # 走这条时一个候选都不查：自动消歧的五种规则都已否证（见 pick 的 docstring），
        # 剩下的缺口由人指认实体，工具只负责把 CC0 标签取回来并过一遍字符集检查。
        return write_outputs(from_qids(Path(args.qids)), out_dir, args.apply)

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
    snapshot: dict[str, dict] = {}
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
        for word, _ in batch:
            snapshot[word.lower()] = {
                "gloss": dict(batch).get(word, ""),
                "candidates": candidates.get(word, []),
                "labels": {qid: labels.get(qid, {}) for qid in candidates.get(word, [])},
            }
        done = min(start + 20, len(todo))
        print(f"  已查 {done}/{len(todo)}，命中 {sum(1 for v in progress.values() if v)}", file=sys.stderr)
        progress_path.write_text(json.dumps(progress, ensure_ascii=False), encoding="utf-8")

    (out_dir / "candidates.json").write_text(json.dumps(snapshot, ensure_ascii=False), encoding="utf-8")

    if skipped:
        print(f"因服务不可用跳过 {len(skipped)} 个词（未记入进度，重跑会再试）", file=sys.stderr)
    return write_outputs(progress, out_dir, args.apply)


if __name__ == "__main__":
    raise SystemExit(main())
