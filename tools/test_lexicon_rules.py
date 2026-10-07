#!/usr/bin/env python3
# Copyright (c) 2026 ilyskyo
# SPDX-License-Identifier: MIT
"""释义匹配规则的回归测试——不联网，喂给它构造好的候选快照就行。

为什么需要这个文件
------------------
`fetch_wikidata.py` 里那条「只认中文子串」的规则，是这一轮踩了四个坑之后退回来的位置：

1. 按词频取前 N → 捞回 will/one/time/people 这类功能词；
2. 按「ECDICT 首块是 n.」筛 → 捞回 aaron/abel 这类专名；
3. 「候选唯一就收」的兜底 → 产出 `all → オール`、`good → グッド`；
4. 「按共享汉字打分 + 唯一胜出」→ 产出 `airport → エアポート駅 (MARTA)`。

这些结论过去只写在注释里。注释不会拦住下一个人（也不会拦住两周后的我），
所以把每一条错法都写成一条会红的用例。**跑法**：`python3 -m unittest discover tools -v`
（CI 里没有 Python 步骤，这个文件是给人手动跑工具改动时用的那道闸。）
"""

from __future__ import annotations

import unittest
from pathlib import Path
import sys

sys.path.insert(0, str(Path(__file__).resolve().parent))

import fetch_wikidata as F  # noqa: E402


def labels(**per_entity: dict[str, str]) -> dict[str, dict[str, str]]:
    return {qid: {"en": "", "zh": "", "ja": "", "ko": "", **values} for qid, values in per_entity.items()}


class StrictRuleTest(unittest.TestCase):
    """当前生效的规则：候选的中文标签必须是 ECDICT 释义的子串，且这样的候选只能有一个。"""

    def test_accepts_a_strict_chinese_substring_match(self) -> None:
        got = F.pick("cup", "杯子, 茶杯", ["Q88"], labels(Q88={"zh": "杯子", "ja": "カップ", "ko": "잔"}))
        self.assertEqual({"ja": "カップ", "ko": "잔"}, got)

    def test_rejects_the_unique_candidate_fallback_that_produced_all_and_good(self) -> None:
        # 当年「候选唯一就收」就是在这里放过了 all → オール（一个桨 / 一切）与 good → グッド。
        # 中文标签对不上，无论候选多唯一都不许收。
        self.assertEqual({}, F.pick("all", "所有的, 全部", ["Q1"], labels(Q1={"zh": "萬物", "ja": "オール", "ko": "올"})))
        self.assertEqual({}, F.pick("good", "好的", ["Q2"], labels(Q2={"zh": "善", "ja": "グッド", "ko": "굿"})))

    def test_rejects_traditional_simplified_mismatch_rather_than_guessing(self) -> None:
        # airport 的正解中文标签是繁体「機場」，与简体释义「飞机场」一个字符都不共享。
        # 这条用例钉的是「我们知道它会漏」，而不是「它错了」——漏是可接受的，猜不是。
        snap = labels(Q1248784={"zh": "機場", "ja": "空港", "ko": "공항"}, Q409022={"zh": "國際機場", "ja": "大空港"})
        self.assertEqual({}, F.pick("airport", "飞机场", ["Q1248784", "Q409022"], snap))

    def test_rejects_when_two_candidates_both_match(self) -> None:
        # 两个候选的中文都落在释义里 → 无从判断是哪个 → 放弃。
        snap = labels(Q1={"zh": "杯子", "ja": "カップ"}, Q2={"zh": "茶杯", "ja": "ティーカップ"})
        self.assertEqual({}, F.pick("cup", "杯子, 茶杯", ["Q1", "Q2"], snap))

    def test_a_bracketed_named_instance_cannot_win_because_it_is_not_a_substring(self) -> None:
        # 「计算机 (杂志)」这类带限定语的条目是**某个具体东西**，不是那个概念。
        # 子串规则天然把它挡在外面：「电脑 (杂志)」不是「电脑」的子串，只有裸的「电脑」是。
        # 这条也是「为什么不该换成打分规则」的一半答案——打分会给它 2 分，和正解并列。
        snap = labels(Q68={"zh": "电脑", "ja": "コンピュータ"}, Q5157408={"zh": "电脑 (杂志)", "ja": "コンピュータ誌"})
        self.assertEqual({"ja": "コンピュータ"}, F.pick("computer", "电脑", ["Q5157408", "Q68"], snap))

    def test_no_candidates_at_all(self) -> None:
        self.assertEqual({}, F.pick("cafe", "咖啡馆", [], {}))

    def test_empty_gloss_cannot_match_anything(self) -> None:
        self.assertEqual({}, F.pick("cup", "", ["Q1"], labels(Q1={"zh": "杯子", "ja": "カップ"})))


class ScriptFilterTest(unittest.TestCase):
    """值必须真的是那个语言写的——拉丁转写与英文都不许进卡片背面。"""

    def test_a_bad_japanese_label_is_dropped_without_poisoning_the_korean_one(self) -> None:
        # 两种语言的检查各管各的：ja 是拉丁转写就该只丢 ja，同一条里合法的 ko 必须留下。
        # （我第一版把预期写成 {}，那是要求「一处脏就整条丢」——会把好数据一起扔掉。）
        self.assertEqual({"ko": "잔"}, F.pick("cup", "杯子", ["Q1"], labels(Q1={"zh": "杯子", "ja": "CUP", "ko": "잔"})))
        self.assertEqual({"ja": "コップ"}, F.pick("cup", "杯子", ["Q1"], labels(Q1={"zh": "杯子", "ja": "コップ"})))

    def test_korean_needs_hangul(self) -> None:
        self.assertEqual({}, F.pick("cup", "杯子", ["Q1"], labels(Q1={"zh": "杯子", "ko": "jan"})))
        self.assertEqual({"ko": "잔"}, F.pick("cup", "杯子", ["Q1"], labels(Q1={"zh": "杯子", "ko": "잔"})))


class ScoringRuleIsRejectedTest(unittest.TestCase):
    """记录那条被退回的规则**为什么会错**，这样没人再把它当改进提上来。

    这里不调用 `F.pick`（它已经不是打分实现了），而是把当时那份真实候选快照摊开：
    证明「按共享汉字打分」在**同一份快照**上会选中车站而不是机场。
    """

    AIRPORT_SNAPSHOT = labels(
        Q1248784={"zh": "機場", "ja": "空港", "ko": "공항"},        # 正解，但繁体
        Q409022={"zh": "国际机场", "ja": "大空港"},                  # 简体，共享 2 字
        Q4698883={"zh": "机场站", "ja": "エアポート駅 (MARTA)"},      # 一个轨道交通站
    )

    def _shared_counts(self, gloss: str) -> dict[str, int]:
        want = set(gloss)
        return {qid: len(want & set(value.get("zh", ""))) for qid, value in self.AIRPORT_SNAPSHOT.items()}

    def test_the_correct_entity_scores_zero_because_of_traditional_chinese(self) -> None:
        counts = self._shared_counts("飞机场")
        self.assertEqual(0, counts["Q1248784"], "繁体「機場」与简体「飞机场」不该被判为相似")

    def test_a_railway_station_wins_that_score(self) -> None:
        counts = self._shared_counts("飞机场")
        best = max(counts.values())
        winners = [qid for qid, score in counts.items() if score == best]
        self.assertNotIn("Q1248784", winners, "打分规则下正解至少该进并列——它没进，说明规则本身有问题")
        self.assertIn("Q4698883", winners, "车站应当是赢家之一，这正是它产出错释义的机制")


if __name__ == "__main__":
    unittest.main()
