#!/usr/bin/env python3
"""Build the shipped Capturney lexicon from ECDICT.

Source
------
ECDICT — https://github.com/skywind3000/ECDICT — MIT licence, © the ECDICT authors.
Fetched separately; this script only reads the local CSV.

    curl -L -o ecdict.csv \\
      https://raw.githubusercontent.com/skywind3000/ECDICT/master/ecdict.csv

Columns used
------------
word       the English headword
phonetic   Dictionary phonetics. ECDICT mixes two systems here: modern IPA
           ("/ˈkʌp/", with ˈ ʃ θ ð ŋ ə …) and older Jones/KK-style strings
           ("/hi:/", "/sei/" — plain Latin letters with a length mark).
           `clean_ipa` normalises both to exactly one wrapping pair of slashes,
           which is what the UI renders verbatim.
translation Chinese senses, inline POS markers: "n. 杯子, 茶杯\\nvt. 使成杯状"
pos        empty in the current dump; kept for forward compatibility
collins    Collins rating 1-5
oxford     1 if the word is in the Oxford 3000/5000
bnc        rank in the British National Corpus word list (lower = more common)
frq        a second frequency ranking
exchange   inflections, e.g. "s:apples/i:appling"

Why no "is this photographable?" filter
----------------------------------------
It looks necessary and it is not. Entries are only ever reached through a match against an
image labeller's output, and labellers emit concrete nouns ("Coffee cup"), never "quality" or
"system". A concreteness filter would shrink the dictionary for no behavioural gain, and would
make the *manual* search worse. If a future recogniser gets broader, that is the moment to
add a filter — see `--require-concrete` below for the hook.

Usage
-----
    python tools/build_lexicon.py ecdict.csv \\
        --out app/src/main/assets/lexicon/en.json \\
        --limit 12000

    # Optional Japanese/Korean enrichment from a Wikidata dump (see tools/fetch_wikidata.py)
    python tools/build_lexicon.py ecdict.csv --wikidata tools/out/wikidata.json \\
        --out app/src/main/assets/lexicon/en.json
"""

from __future__ import annotations

import argparse
import csv
import json
import re
import sys
import unicodedata
from pathlib import Path

csv.field_size_limit(16 * 1024 * 1024)

# A headword worth shipping.
#
# Hyphens are rejected on purpose: ECDICT stores derivational variants as separate rows
# ("a-ba", "a-feared", "a-frame"), and admitting them costs ~119k junk entries that match
# nothing. Real dictionary headwords are one or two bare lowercase words.
WORD_RE = re.compile(r"^[a-z][a-z]{1,14}(?: [a-z][a-z]{1,14})?$")

# ECDICT stores line breaks as a *literal* backslash-n inside a quoted CSV field, and mixes
# half-width and full-width separators. Both have to be handled or the parser silently keeps
# reading into the next part-of-speech block.
BLOCK_SPLIT_RE = re.compile(r"\\n|\r?\n")
SENSE_SPLIT_RE = re.compile(r"[,，、;；]")
PAREN_RE = re.compile(r"[\(（][^)）]*[\)）]")
# 配对括号剥完之后仍可能剩下的孤括号（源数据里就有 `伊顿（姓氏））` 这种多写一只闭括号的行）。
STRAY_BRACKETS_RE = re.compile(r"[\(（\)）]")
BRACKET_RE = re.compile(r"\[[^\]]*\]")

# POS markers ECDICT writes inline. Anything from a non-noun marker onwards is a different
# part of speech and would pollute a noun gloss.
#
# 这份表是**数出来的**：扫一遍 CSV 里 40 万个块的首个 `xxx.` 记号、按出现次数排序，
# 才看到 ECDICT 主要写 `a.`（37043 个块，仅次于 `n.`）而这里只有 `adj.`（1454 次）。
# 后果是形容词条一律发到**第二个**义项（`new`→陌生的、`big`→重要的、`early`→早熟的），
# 单义项的形容词块更糟：整块作废、继续往下落到 `[机]`/`[计]` 技术块里
# （`different` 的中文背面因此是「差动」，原文那行是 `a. 不同的` 加 `[机] 差动, 微分的`）。
# 补进来的每个记号都先看过程长样本，确认是语法标签而不是别的东西：
# `un.`=不可数名词、`pl.`=复数（`glasses` 这类靠它）、`st.`=谚语整句、`comb.`=构词成分、
# `vbl.`=动名词、`na.`=名词性修饰、`pp.`=过去分词。
# 每个标记都含句点，所以不会出现 `n.`/`na.`、`v.`/`vbl.` 抢前缀匹配的问题（这点单独核过）。
POS_MARKERS = ("n.", "a.", "v.", "vt.", "vi.", "adj.", "adv.", "prep.", "conj.", "pron.", "num.",
               "art.", "int.", "interj.", "abbr.", "aux.", "modal.",
               "un.", "na.", "pl.", "pla.", "pp.", "vbl.", "comb.", "pref.", "suf.", "suff.",
               "vt.vi.", "vi.vt.", "st.")

# Translation prefixes that mark a non-word sense: 网络 (web/Internet slang), 地名 (place
# name), 姓 (surname), 医 (medicine), 机 (computing), 计 (computing), etc. These are correct
# dictionary data but useless as a "what is this object" gloss.
REJECT_PREFIXES = ("[网络]", "[地名]", "[网络用语]", "[俚]", "[缩]", "[语]", "[音]",
                   "[美]", "[英]", "[俚语]", "[缩写]")


def strip_accents(value: str) -> str:
    return "".join(
        ch for ch in unicodedata.normalize("NFKD", value) if not unicodedata.combining(ch)
    )


# COCO 类名与词典头词的拼写差：收录了 A 就给 B 开别名，让检测标签能落到同一个词条上。
EXTRA_ALIASES = {
    "doughnut": ["donut"],
    "dryer": ["drier"],
}

# 逐词指定的主义项。ECDICT 把 `n.` 块排在前面**与这个词实际怎么用无关**，只要它有名词义就先列，
# 于是全英语最常用的一批词拿到生僻名词义：`give=弹性`、`good=善行`、`still=蒸馏室`、
# `leave=许可`、`want=需要的东西`、`like=同样的`、`mean=卑贱的`、`high=高度`、`many=多数`、
# `most=最多`、`old=以前`。下面每个取值都是该词**同一行里非名词块的原词**，逐条读过才写下来的。
#
# 为什么是这张表而不是一条规则，两条通用捷径都被实测否证过：
#   * 「取首个非名词块的首个义项」有 4 个直接错（still→蒸馏、like→相似的、mean→低劣的、most→大多数的）；
#   * 按中文串在 ECDICT 全语料里的频次排序，会改写 4531 条且条条变差（see→游览、take→抓）。
# 改这里任何一条，必须连同 `LexiconFieldShapeTest.theMostFrequentWordsAreGlossedWithTheirCommonSense`
# 的期望值一起改——那条测试就是用来让「悄悄改回去」变红的。
PRIMARY_SENSES = {
    "give": "给",        # vt. 给, 授予, 供给
    "good": "好的",      # a. 好的, 优良的
    "still": "静止的",   # a. 静止的, 不动的
    "leave": "离开",     # vt. 离开, 剩下
    "want": "要",        # vt. 要, 希望, 应该
    "like": "喜欢",      # vt. 喜欢, 愿意
    "mean": "意谓",      # vt. 意谓, 想要
    "high": "高的",      # a. 高的, 高级的
    "many": "许多的",    # a. 许多的
    "most": "最",        # adv. 最, 最多
    "old": "老的",       # a. 老的, 旧的
    # 第二批：把频次 80～310 那一整段逐个读过后挑出来的。全都是「动词/形容词被发了同行那个
    # 生僻名词义」——`lead` 是引导不是金属铅、`keep` 是保持不是生计、`start` 是开始不是惊起、
    # `carry` 是携带不是进位、`set` 是放不是日落。取值同样必须是该行**非名词块**里的原词。
    # 反过来，`time=时间`、`people=人`、`car=汽车`、`water=水` 这类看着「像缺陷」的其实是对的：
    # 具体名词的首块本来就该是名词块，所以这条签名只能用来找候选，不能当成规则用。
    "may": "可以",       # aux. 愿能, 可以, 愿意（首个义项「愿能」是文言说法）
    "well": "很好地",    # adv. 很好地, 适当地
    "lead": "引导",      # vt. 引导, 带领, 领导
    "keep": "保持",      # vt. 保持, 保存, 遵守
    "start": "开始",     # vi. 开始, 出发, 启动
    "carry": "携带",     # vt. 携带, 运送, 支持
    "set": "放",         # vt. 放, 安置, 放置
    "pay": "支付",       # vt. 支付, 付清, 补偿
    "produce": "产生",   # vt. 产生, 生产, 提出
    "meet": "遇见",      # vt. 遇见, 引见, 认识
    "right": "正确的",   # a. 正确的, 对的, 恰当的
    "full": "充满的",    # a. 充满的, 完全的, 丰富的
    "little": "小的",    # a. 小的, 很少的, 幼小的
    "within": "在...之内",  # prep. 在...之内
    "offer": "提供",     # vt. 提供, 出价, 奉献
    # 第七批：频次 1100～2600 这一段有 553 条符合「首块 n. 而另有动词/形容词块」的签名，
    # 但逐条读下来只有这 3 条是真错（26 条里 3 条，约 1/8；前几批接近 1/3）。
    # 签名到这个频段已经主要是**真名词**（corner/forest/video/judge/labor/credit/hill/sky…），
    # 所以别拿它去批量改——那会把一批本来正确的释义换掉。
    "southern": "来自南方的",  # a. 向南方的, 来自南方的（首块是 n. 南方人, 男风——还带着一个粗俗义项）
    "fair": "公平的",    # a. 公平的, 按规则进行的, 晴朗的（首块 n. 展览会, 市集）
    "gain": "得到",      # vt. 得到, 增进, 赚到（首块 n. 增益 是电子学那个技术义项）
    # 第三批（频次 200～600 那一段）。这批的性质不同：不是「块排错」，而是**同一块里的第一个
    # 义项不是最常用那个**——`state` 那块的义项序是 州, 状态, 情形, 国家…，用户要的是 状态；
    # `line` 是 列, 线, 绳…，要的是 线。所以这一批的取值全在同一行内，逐条对着原行读过。
    "state": "状态",     # n. 州, 状态, 情形, 国家（美国「州」是较窄的那个义项）
    "line": "线",        # n. 列, 线, 绳, 电线
    "second": "第二",    # num. 第二（n. 那块的首义项是「秒」）
    "information": "信息",  # n. 消息, 知识, 通知, 情报, 信息
    "even": "甚至",      # adv. 甚至, 实际上, 完全（首块是 a. 平坦的, 相等的…）
    "issue": "问题",     # n. 发行, 问题, 后果
    "lot": "许多",       # n. 运气, 签, 抽签, 份额, 许多（a lot of 那个用法）
    "stand": "站",       # vi. 站, 立, 坐落（首块 n. 站立 是名词化那个）
    "hold": "握住",      # vt. 保存, 握住, 拿住（首块 n. 把握）
    "effect": "影响",    # n. 结果, 影响, 效果, 印象
    "party": "聚会",     # n. 宴会, 党, 政党, 团体, 当事人, 聚会
    "white": "白色的",   # a. 白色的, 纯洁的（首块 n. 白色）
    "black": "黑色的",   # a. 黑色的（首块 n. 黑色）
    "power": "力量",     # n. 力, 体力, 力量, 势力（单字「力」作为释义太薄）
    # 第四批（频次 300～760 那一段，243 个「名词块打头」的候选里只挑出这 7 个真错的）。
    # 关键判断是**反方向的**：那一段里绝大多数首块本来就是名词、发的也正是那个意思
    # （`light=光`、`heart=心`、`voice=声音`、`trade=贸易`、`material=材料`、`season=季节`……），
    # 把它们当缺陷改掉就是倒退。所以签名只能用来找候选，逐条读完才动。
    "special": "特别的",  # a. 特别的, 专门的, 特殊的（原发的是 n. 那块的首义项「专辑」）
    "military": "军事的",  # a. 军事的, 军人的, 适于战争的（原 n. 军队）
    "account": "帐目",   # n. 报告, 解释, 估价, 理由, 利润, 算账, 帐目（我先前写的「账户」用的是帐字的新字形，
    # 原文里是「帐目」；这一条被「取值必须出现在自己那一行」那道断言拦下过，见 077feac 之后那次返工）
    "range": "范围",     # n. 排, 行, 山脉, 范围, 行列, 射程（原发「排」）
    "lie": "说谎",       # vi. 躺着, 说谎, 位于（n. 那块是「谎言」这个名词化）
    "break": "打破",     # vt. 打破, 弄破, 弄坏, 破坏, 违反（原 n. 休息）
    "pass": "通过",      # vt. 经过, 越过, 通过, 批准（原 n. 经过）
    # 第五批：这一批不是「块排错」也不是「首义项偏」的旧账，而是**放进来的一千个核心虚词**里
    # 最常被看到的六个（`the/be/and/of` 那批的释义本来就大体对，这六个不然）。
    # 每条都对着**不截断**的整行读过（上一次我从截断的 6 个义项里把 account 补成「账户」，
    # 原文其实是「帐目」——那一错进了记忆，这一批的读法因此是整行打印出来再挑）。
    "have": "有",        # vt. 有, 怀有, 拿, 进行（规则取到的首块首义项是 aux. 的「已经」）
    "but": "但是",       # conj. 但是（首块是 prep. 除了）
    "up": "向上",        # adv. 向上, 上涨（首块是 a. 向上的, 起床的, 涨的）
    "out": "在外",       # adv. 在外, 熄灭, 出现（首块是 a. 外面的, 熄灭的, 结束的）
    "about": "关于",     # prep. 在...周围, 大约, 有关, 关于
    "as": "当作",        # prep. 做为, 当作（首块是 adv. 同样地, 例如）
    # 第六批：根因不是「块排错」也不是「首义项偏」，而是 **POS_MARKERS 里没有 `a.`**
    # （ECDICT 用 `a.` 表示形容词，表里只有 `adj.`）。于是首块 `a. 新的, 陌生的…` 匹配不上标记，
    # 第一串「a. 新的」含拉丁字母被当成转写丢掉，取到的是**第二个**义项；更糟的是只有一个义项的
    # 形容词块（`a. 不同的`）会整块作废、往下落到 `[机] 差动, 微分的` 这种技术块里去。
    # 所以这一批的共同形状是「形容词词条发出生僻的第二/技术义项」，12 条全部取自该行原词。
    # 注：big/different/early/far/important/live/national/new/sure 这 9 条原本在表里，
    # 补全 POS_MARKERS 之后规则自己就能给出一样的值（逐条比对过），所以摘掉——
    # 表只留**规则还给不出**的那些，减小手工面。
    "just": "刚刚",      # adv. 刚刚, 正好, 仅仅（首块 a. 的第一义项是「正直的」）
    "very": "非常",      # adv. 非常, 完全（首块 a. 真正的, 恰好的…）
    "over": "在...之上",  # prep. 在...之上, 遍于...之上（首块 adv. 结束, 越过）
}

# efficientdet_lite0 输出的 COCO-80 类名里的单词成分。这些词必须在词典里：
# 缺一个，检测器认出那个物体时就只能显示一枚「不认识」的词片。
# 除常规频率门槛外它们享有额外通道（见 build()）：允许缺音标、允许非名词释义。
DETECTOR_VOCAB = frozenset({
    "person", "bicycle", "car", "motorcycle", "airplane", "bus", "train", "truck", "boat",
    "traffic", "light", "fire", "hydrant", "stop", "sign", "parking", "meter", "bench",
    "bird", "cat", "dog", "horse", "sheep", "cow", "elephant", "bear", "zebra", "giraffe",
    "backpack", "umbrella", "handbag", "tie", "suitcase", "frisbee", "skis", "snowboard",
    "sports", "ball", "kite", "baseball", "bat", "glove", "skateboard", "surfboard",
    "tennis", "racket", "bottle", "wine", "glass", "cup", "fork", "knife", "spoon", "bowl",
    "banana", "apple", "sandwich", "orange", "broccoli", "carrot", "hot", "pizza", "donut",
    "doughnut", "cake", "chair", "couch", "potted", "plant", "bed", "dining", "table",
    "toilet", "tv", "television", "laptop", "mouse", "remote", "keyboard", "cell", "phone",
    "microwave", "oven", "toaster", "sink", "refrigerator", "book", "clock", "vase",
    "scissors", "teddy", "bear", "hair", "drier", "dryer", "toothbrush",
})


def parse_exchange(raw: str) -> dict[str, str]:
    """'s:apples/i:appling' -> {'s': 'apples', 'i': 'appling'}."""
    out: dict[str, str] = {}
    for part in (raw or "").split("/"):
        if not part or ":" not in part:
            continue
        key, _, value = part.partition(":")
        if value:
            out[key.strip()] = value.strip()
    return out


def first_noun_sense(translation: str) -> str | None:
    """Extract one short Chinese gloss for the word's **primary** sense.

    'n. 杯子, 茶杯\\nvt. 使成杯状' -> '杯子'
    'vt. 看见\\nn. 主教的职位'      -> '看见'

    以前这里是「扫到任意一个 `n.` 块为止」，于是 12006 条里有 828 条拿到了一个生僻的
    名词化义项：`see` 变成「主教的职位」、`if` 变成「条件」、`get` 变成「救球」、
    `he` 变成「男孩」。挑名词的初衷是对的（这个 App 的记录以可摄名词为主），
    但**具体名词的首块本来就是 `n.`**，所以「只取首块」既留着那个好处，
    又不再为动词/介词/代词硬凑一个冷门名词。

    Deliberately keeps a single sense: a card front wants one clean answer, and the remaining
    senses are exactly what makes raw dictionary output unusable as vocabulary.

    Three ECDICT quirks this has to survive, all of which silently destroyed entries when
    handled naively:
      * line breaks are the *literal* two-character sequence ``\\n``, not a newline;
      * senses are separated by half-width ``,`` **and** full-width ``，``;
      * a sense often carries a bracketed tag or a parenthesised English gloss, e.g.
        ``n. 亚伯（男子名，等于Abraham）`` — those must be stripped, not treated as a reason
        to reject the whole word.
    """
    if not translation:
        return None

    blocks = [b for b in BLOCK_SPLIT_RE.split(translation) if b.strip()]
    # ECDICT 的块序**不是**义项序。行内带 aux./modal. 块的那 6 个词（can/may/might/will/must/need，
    # 全英语最高频的一批）里有 4 个的首块是别的东西：`may` 是 n.五月、`can` 是 vt.装罐、
    # `might` 是 n.力量、`will` 是 n.意志——而用户在真实句子里遇到的几乎只能是情态动词那个用法。
    # 所以情态块存在时让它排第一。这条不是「按类别猜」：那 6 行是逐行读过原始 CSV 才下的结论，
    # 取值仍逐字来自 ECDICT 自己的块，没有一处是我代拟的译法。
    for index, block in enumerate(blocks):
        if index and block.strip().lower().startswith(("aux.", "modal.")):
            blocks.insert(0, blocks.pop(index))
            break

    for block in blocks:
        candidate = block.strip()
        if not candidate:
            continue
        lowered = candidate.lower()
        # 只认首块：第一个带词性标记的块就是这个词的主义项，后面的块属于别的词性/别的义项。
        marker = next((m for m in POS_MARKERS if lowered.startswith(m)), None)
        if marker is None:
            body = candidate
        elif not lowered.startswith("n."):
            body = candidate[len(marker):]
        else:
            body = candidate[2:]
        # 先剥括号里的补充说明，**再**按逗号切义项。顺序反了就会切进括号内部：
        # `詹姆斯（姓氏, 男子名）` 先切就得到 `詹姆斯（姓氏` 这半截——一个括号不配对的
        # 释义发到用户手机上，而界面上看不出任何异常。（全表有 137 条这样。）
        sense_group = BRACKET_RE.sub(" ", body)
        sense_group = PAREN_RE.sub(" ", sense_group)
        # 源数据里存在 `伊顿（姓氏））` 这种**多一个闭括号**的行：剥掉配对的一只之后还剩一只孤的，
        # 于是发出去是 `伊顿 ）`。孤括号不携带任何信息，直接删——留着它比删掉更容易被误读成释义的一部分。
        sense_group = STRAY_BRACKETS_RE.sub("", sense_group)
        for raw_sense in SENSE_SPLIT_RE.split(sense_group):
            sense = raw_sense
            sense = sense.strip().strip("。．.；;，,、 ")
            # 剥掉 `[药]`/`（…）` 之后会在中间留下空格，于是出现 `类鸦片 的` 这种带空格的释义。
            # 中文释义里的空格没有区分作用（会变宽的是含拉丁的串，而那种本来就被下面拦掉），
            # 所以不含 ASCII 字母时直接去掉——卡片背面是原样渲染这一行的，中间空格看着像排版坏了。
            sense = re.sub(r"\s+", " ", sense).strip()
            if sense and not any(ch.isascii() and ch.isalpha() for ch in sense):
                sense = sense.replace(" ", "")
            if not sense or len(sense) > 14:
                continue
            if not any(unicodedata.category(ch).startswith("L") for ch in sense):
                continue
            # Any Latin letter left means the "gloss" is really a transliteration or a
            # proper noun; try the next sense instead of giving up on the word.
            if any(is_latin(ch) for ch in sense):
                continue
            return sense
        # 首块里一个能看的义项都没有（全是超长或全是转写）：放弃这个词，而不是往下一个块
        # 去捡一个生僻名词——那正是 828 条错释义的来路。
            # 首块一个可用义项都没有时**继续往后一个块试**，而不是把这个词判成「没有释义」。
            # 上面那段旧注释（「放弃这个词，而不是往下一个块」）是当时的结论，实测被推翻：
            # ECDICT 的形容词标记是 `a.`，而 `POS_MARKERS` 里只有 `adj.`，于是首块 `a. 向下的`
            # 匹配不上标记、那串又因含拉丁字母被当成转写丢弃——12006 条里有 62 个常用词
            # （down/back/bad/best/deep/international/upstairs/wow/bold/…）就这样整词消失。
            # 少一个词的代价比取到第二个块的义项大得多：检测器认出那个物体时连词片都出不来。
        # 影响面恰好只等于「现在返回 None」的那些词，其余一条不动（用全表重算量过）。
        continue

    return None


def first_any_sense(translation: str) -> str | None:
    """检测器词表专用兜底：不要求名词标记，取第一个能看的中文释义。"""
    if not translation:
        return None
    for block in BLOCK_SPLIT_RE.split(translation):
        candidate = block.strip()
        for marker in POS_MARKERS:
            if candidate.lower().startswith(marker):
                candidate = candidate[len(marker):].strip()
        sense = BRACKET_RE.sub(" ", candidate)
        sense = PAREN_RE.sub(" ", sense).strip().strip("。．.；;，,、 ")
        if sense and len(sense) <= 14 and not any(is_latin(ch) for ch in sense):
            return sense
    return None


def is_latin(ch: str) -> bool:
    return "LATIN" in unicodedata.name(ch, "")


def clean_ipa(raw: str) -> str | None:
    if not raw:
        return None
    value = raw.strip().strip('"').strip("'").strip()
    if not value:
        return None
    # ECDICT marks primary stress with a leading apostrophe; keep it, it is standard in
    # Collins-style transcriptions and harmless inside slashes.
    if value.startswith("/") and value.endswith("/"):
        value = value[1:-1]
    return f"/{value}/"


def frequency_rank(row: dict[str, str]) -> int:
    """Lower is more common. 取两个排名里较小的那个：BNC 是老式英语料，
    laptop / backpack 这类现代生活词被严重低估，FRQ 更贴近日常。"""
    best = 0
    for key, cap in (("bnc", 60_000), ("frq", 120_000)):
        raw = (row.get(key) or "").strip()
        if not raw.isdigit():
            continue
        value = int(raw)
        if 0 < value <= cap and (best == 0 or value < best):
            best = value
    return best


def build(args: argparse.Namespace) -> int:
    entries: list[dict] = []
    seen: set[str] = set()
    skipped = {"shape": 0, "ipa": 0, "noun": 0, "freq": 0, "proper": 0, "dupe": 0}

    wikidata: dict[str, dict[str, str]] = {}
    if args.wikidata and Path(args.wikidata).exists():
        wikidata = json.loads(Path(args.wikidata).read_text(encoding="utf-8"))
        print(f"loaded Wikidata enrichment for {len(wikidata)} words", file=sys.stderr)

    with Path(args.csv).open("r", encoding="utf-8", newline="", errors="replace") as fh:
        for row in csv.DictReader(fh):
            raw_word = (row.get("word") or "").strip()
            if not raw_word:
                continue

            # Reject proper nouns: ECDICT does not mark them, but a capitalised first letter
            # with no lowercase form in the corpus is a good proxy.
            first = raw_word[0]
            if first.isupper():
                # 这条本意是把人名/姓氏挡在外面（aaron/abel/kennedy 那种「男子名」释义），
                # 可 ECDICT 把 **十二个月名与七个星期名** 也写成首字母大写，于是它们一起被挡掉了
                # ——用户拍到「Sunday」时词典里根本没有这个词，而这是日记应用最核心的词汇。
                # 判据用 ECDICT 自己带的常见度信号：oxford=1（牛津核心词）或有考纲 tag（zk/gk/cet4…）
                # 的是「要学的词」，两个都没有的专名照旧不收。改完后 april/monday 进得来，aaron 进不来。
                has_signal = (row.get("oxford") or "").strip() == "1" or bool((row.get("tag") or "").strip())
                if not (has_signal or raw_word.lower() in DETECTOR_VOCAB):
                    skipped["proper"] += 1
                    continue
                raw_word = raw_word.lower()

            word = strip_accents(raw_word.lower())
            if not WORD_RE.match(word):
                skipped["shape"] += 1
                continue
            if word in seen:
                skipped["dupe"] += 1
                continue

            # 检测器词表享有额外通道：缺音标就留空、没有名词释义就取第一条中文释义。
            # 宁可音标空着，也不能让「bottle」这种词从词典里消失。
            required = word in DETECTOR_VOCAB

            ipa = clean_ipa(row.get("phonetic") or "")
            if not ipa and not required:
                skipped["ipa"] += 1
                continue

            gloss = PRIMARY_SENSES.get(word) or first_noun_sense(row.get("translation") or "")
            if not gloss and required:
                gloss = first_any_sense(row.get("translation") or "")
            if not gloss:
                skipped["noun"] += 1
                continue

            freq = frequency_rank(row)
            if freq == 0:
                # 「没有排名」不等于「不是常用词」：ECDICT 对 **april / monday / cannot / email /
                # glasses / dvd** 这类词根本记 frq/bnc（值是 0），而它们全都带 oxford=1 或考纲 tag。
                # 旧代码把 0 当成「最差排名」直接 skip，于是十三个月名、七个星期名和一批核心词
                # 就这样从词典里消失了（实测已发数据缺 1153 个 oxford=1 的词）。
                # 现在只在「另一个常见度信号存在」时收下，并排到最后——既不冒充它是高频词，
                # 也不让用户拍到「Sunday」时被告知词典里没有这个词。
                has_signal = (row.get("oxford") or "").strip() == "1" or bool((row.get("tag") or "").strip())
                if not has_signal and word not in DETECTOR_VOCAB:
                    skipped["freq"] += 1
                    continue
                freq = 10_000_000

            seen.add(word)

            entry: dict = {
                "id": f"en.{word}",
                "words": {"en": word, "zh": gloss},
                # 检测器词允许没有音标：宁缺毋null——Map<String, String> 里放 null 会让
                # 运行时的 kotlinx 解析直接抛异常。
                "ipa": ({"en": ipa} if ipa else {}),
                "glosses": {"zh": gloss},
                "labelAliases": [],
                "frequency": freq,
                "source": "ECDICT",
            }

            plural = parse_exchange(row.get("exchange") or "").get("s")
            if plural and plural.lower() != word:
                entry["labelAliases"].append(plural.lower())
            for alias in EXTRA_ALIASES.get(word, []):
                if alias != word:
                    entry["labelAliases"].append(alias)

            extra = wikidata.get(word)
            if extra:
                for lang in ("zh", "ja", "ko"):
                    value = extra.get(lang)
                    if value:
                        entry["words"][lang] = value
                extra_ipa = extra.get("ipa")
                if extra_ipa:
                    entry["ipa"].setdefault("en", extra_ipa)

            entries.append(entry)

    entries.sort(key=lambda e: e["frequency"])
    if args.limit and len(entries) > args.limit:
        kept = entries[: args.limit]
        # 频率截断不能截掉检测器词表：那 80 个类名是「拍照出词」这条主路的下限。
        have = {e["words"]["en"] for e in kept}
        kept += [e for e in entries[args.limit:] if e["words"]["en"] in DETECTOR_VOCAB and e["words"]["en"] not in have]
        entries = kept

    payload = {
        "schemaVersion": 2,
        "language": "en",
        "entries": entries,
    }

    out = Path(args.out)
    out.parent.mkdir(parents=True, exist_ok=True)

    # 重建词典最容易犯的错不是写坏内容，而是**悄悄换掉一批条目**。实测：拿当前 ECDICT
    # 重跑一次，会有 3625 个旧 id 消失、3633 个新 id 出现——因为已发布那份是用另一个
    # 版本生成的。而 `gloss-<lang>.json` 与场景词表都是**按 id** 找条目的，它们不会报错，
    # 只会让对应的背面释义静默变空。这条真发生过（`en.toothpaste` 被 GlossOverlayCoverageTest 抓到）。
    if out.exists() and not args.force:
        previous = {e["id"] for e in json.loads(out.read_text(encoding="utf-8")).get("entries", [])}
        kept = {e["id"] for e in entries}
        dropped = sorted(previous - kept)
        referenced: set[str] = set()
        for overlay in sorted(out.parent.glob("gloss-*.json")):
            referenced |= {i["id"] for i in json.loads(overlay.read_text(encoding="utf-8")).get("entries", [])}
        fatal = [i for i in dropped if i in referenced]
        if fatal:
            raise SystemExit(
                f"拒绝写出：这次会删掉 {len(fatal)} 个仍被释义补丁引用的条目（如 {fatal[:5]}）。"
                f"被删条目的背面释义不会报错，只会静默变空。"
            )
        if len(dropped) > args.max_dropped:
            raise SystemExit(
                f"拒绝写出：这次会删掉 {len(dropped)} 个旧条目（超过 --max-dropped {args.max_dropped}）。"
                f"要接受一次大换血就显式加 --force，并同步重建 gloss-*.json。"
            )

    # Write compactly: this file is parsed at every cold start, and pretty-printing a
    # 12k-entry document roughly doubles both the bytes and the parse time.
    out.write_text(
        json.dumps(payload, ensure_ascii=False, separators=(",", ":")),
        encoding="utf-8",
    )

    with_ja = sum(1 for e in entries if "ja" in e["words"])
    with_ko = sum(1 for e in entries if "ko" in e["words"])
    size_kb = out.stat().st_size / 1024
    print(f"wrote {len(entries)} entries -> {out} ({size_kb:.0f} KB)", file=sys.stderr)
    print(f"  with ja={with_ja} ko={with_ko}", file=sys.stderr)
    print(f"  skipped: {skipped}", file=sys.stderr)
    print(f"  top 15: {[e['words']['en'] for e in entries[:15]]}", file=sys.stderr)
    have = {e["words"]["en"] for e in entries}
    # 多词类名（sports ball 等）按空格拆开逐词核对；虚词不在词表里，单独放行。
    aliases = {a for e in entries for a in e.get("labelAliases", [])}
    ignore = {"traffic", "fire", "stop", "parking", "sports", "potted", "dining", "cell", "teddy", "hot", "donut", "drier"}
    missing = sorted(w for w in DETECTOR_VOCAB if w not in have and w not in aliases and w not in ignore)
    if missing:
        print(f"  WARNING detector vocab missing: {missing}", file=sys.stderr)
    return 0


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("csv", help="path to ecdict.csv")
    parser.add_argument("--out", default="app/src/main/assets/lexicon/en.json")
    parser.add_argument("--wikidata", default="tools/out/wikidata.json",
                        help="optional JSON of {english_word: {zh,ja,ko,ipa}}")
    parser.add_argument("--limit", type=int, default=12000,
                        help="cap entries by frequency rank; 0 for everything")
    # Reserved for a future concreteness filter (e.g. WordNet lexicographer files). See the
    # module docstring for why it is not needed today.
    parser.add_argument("--force", action="store_true",
                        help="接受一次大换血：跳过「会删掉多少旧条目」这道写出前检查")
    parser.add_argument("--max-dropped", type=int, default=50,
                        help="允许被删掉的旧条目上限（仍被 gloss-*.json 引用的那些一律不许删）")
    parser.add_argument("--require-concrete", action="store_true",
                        help="accepted for forward compatibility; currently a no-op")
    return build(parser.parse_args())


if __name__ == "__main__":
    raise SystemExit(main())
