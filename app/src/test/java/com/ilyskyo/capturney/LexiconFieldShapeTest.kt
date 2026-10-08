// Copyright (c) 2026 ilyskyo
// SPDX-License-Identifier: MIT

package com.ilyskyo.capturney

import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 词典字段形状守卫。`en.json` 是 12006 条生成数据，**重新生成一次就能悄悄改掉任何东西**，
 * 而它每一条都可能直接出现在卡片上。这里守六条界面真正依赖的性质。
 *
 * ## 为什么是这六条
 *
 * - **音标必须恰好被一对斜杠包住**。`CaptureScreen` 是 `text = ipa` 原样渲染的，
 *   所以斜杠是数据的一部分而不是界面加的。`clean_ipa` 的规约就是「剥掉已有的一对、
 *   再包上一对」——遇到 ECDICT 里 `n. 甲 / n. 乙` 这种**两个变体**时它会漏，
 *   产出 `//ˈkʌp/ /ˈkʊp//` 那种东西。现在没有一条是这样，但没有任何东西阻止下一次生成变成这样。
 * - **每条都要有中文释义**。那是中文用户要被评级的背面；生成器里 `first_noun_sense` 返回
 *   None 时条目会被跳过，但兜底路径若改了就可能写出空串。
 * - **英文词头不许重复**。搜索页会把两条同名词头都列出来，用户看到的是「同一个词出现两遍」。
 * - **解析器要真的看到东西**。前几条都是「遍历后断言没有坏样本」，一旦解析本身失败，
 *   它们会集体变成真空通过——所以先断言条数。
 * - **括号不许只有一半**。生成器一度先按逗号切义项、再剥括号，而 ECDICT 的括号里就写着逗号，
 *   于是 `詹姆斯（姓氏, 男子名）` 发出的是 `詹姆斯（姓氏`；另有源数据自己多写一只闭括号的
 *   （`伊顿（姓氏））` → `伊顿 ）`）。两类都在界面上长得像正常内容。
 * - **情态词必须仍是情态释义**。ECDICT 的块序不是义项序，`can/may/might/will` 的首块是生僻的
 *   名词/动词义，情态用法在第二块——按首块取就等于给最高频的几个词教错意思。
 */
class LexiconFieldShapeTest {

    private val entries: List<JsonObject> by lazy {
        val file = File(File(findModuleDir(), "src/main/assets/lexicon"), "en.json")
        Json.parseToJsonElement(file.readText()).jsonObject.getValue("entries").jsonArray.map { it.jsonObject }
    }

    @Test
    fun theParserActuallySeesTheDictionary() {
        assertTrue("只解析到 ${entries.size} 条，解析器大概瞎了——下面几条会因此全部真空通过", entries.size >= 10_000)
    }

    @Test
    fun everyPhoneticIsWrappedInExactlyOnePairOfSlashes() {
        val bad = entries.mapNotNull { entry ->
            val ipa = (entry["ipa"] as? JsonObject)?.get("en")?.jsonPrimitive?.content ?: return@mapNotNull null
            when {
                !ipa.startsWith("/") || !ipa.endsWith("/") -> "$ipa（没被斜杠包住）"
                ipa.length <= 2 -> "$ipa（里面是空的）"
                '/' in ipa.substring(1, ipa.length - 1) -> "$ipa（里面还有斜杠：多半是两个变体）"
                else -> null
            }
        }
        assertTrue(
            "有 ${bad.size} 条音标形状不对：${bad.take(6)}。卡片上是原样渲染这个字符串的，" +
                "斜杠属于数据而不是界面",
            bad.isEmpty(),
        )
    }

    @Test
    fun everyEntryCarriesAMotherTongueGloss() {
        val blank = entries.filter { (it["glosses"] as? JsonObject)?.get("zh")?.jsonPrimitive?.content.isNullOrBlank() }
            .map { it.headword() }
        assertTrue(
            "有 ${blank.size} 条没有中文释义：${blank.take(6)}。那是中文用户翻过来要被评级的那一行",
            blank.isEmpty(),
        )
    }

    @Test
    fun noEnglishHeadwordAppearsTwice() {
        val counts = entries.map { it.headword().lowercase() }.groupingBy { it }.eachCount()
        val dupes = counts.filterValues { it > 1 }
        assertTrue(
            "有 ${dupes.size} 个英文词头出现不止一次：${dupes.keys.take(6)}。搜索页会把它们各列一行，" +
                "用户看到的是同一个词出现两遍",
            dupes.isEmpty(),
        )
    }

    @Test
    fun noGlossCarriesABracketThatNeverCloses() {
        // `詹姆斯（姓氏, 男子名）` 这类补充说明在 ECDICT 里就带着逗号。生成器一度**先按逗号切义项、
        // 再剥括号**，于是半截 `詹姆斯（姓氏` 被发出去，共 137 条（见 6af765e）。
        // 括号不配对是那种「界面上完全正常、用户却学到一团乱码」的坏数据，所以要钉住。
        val bad = entries.mapNotNull { entry ->
            val gloss = entry.motherTongueGloss() ?: return@mapNotNull null
            when {
                gloss.count { it == '（' } != gloss.count { it == '）' } -> "$gloss（全角括号不配对）"
                gloss.count { it == '(' } != gloss.count { it == ')' } -> "$gloss（半角括号不配对）"
                gloss.startsWith("（") || gloss.startsWith("(") -> "$gloss（整条释义只是个限定语，词本身丢了）"
                else -> null
            }
        }
        assertTrue(
            "有 ${bad.size} 条中文释义的括号形状不对：${bad.take(6)}。卡片背面原样渲染这一行，" +
                "半截括号看起来像内容而不是错误",
            bad.isEmpty(),
        )
    }

    @Test
    fun modalWordsAreGlossedAsModalsNotAsTheirRareNounSenses() {
        // ECDICT 的**块序不是义项序**：`can` 的首块是 `vt. 装罐`、`may` 是 `n. 五月`、
        // `might` 是 `n. 力量`、`will` 是 `n. 意志`，情态用法排在第二块。按首块取就把生僻义发给了
        // 全英语最高频的几个词（见 f3dbf07）。这里不许它退回去。
        val expected = mapOf(
            "can" to "能",
            "may" to "可以",   // 同一条 aux. 块里的「愿能」是文言说法，给用户的是可以
            "might" to "可能",
            "will" to "将",
        )
        val byWord = entries.associateBy { it.headword().lowercase() }
        val wrong = expected.mapNotNull { (word, want) ->
            val actual = byWord[word]?.motherTongueGloss()
            when {
                actual == null || actual == want -> null
                else -> "$word=$actual（应为 $want）"
            }
        }
        assertTrue(
            "有 ${wrong.size} 个情态词的释义不是情态说法：$wrong。" +
                "这些词用户每天遇到，取错块等于教错",
            wrong.isEmpty(),
        )
    }

    @Test
    fun theMostFrequentWordsAreGlossedWithTheirCommonSense() {
        // ECDICT 把 `n.` 块排在前面**与这个词的常见用法无关**：只要它有名词义就先列。
        // 于是全英语最常用的那批词拿到的是生僻名词义：`give=弹性`、`good=善行`、`still=蒸馏室`、
        // `leave=许可`、`want=需要的东西`、`like=同样的`、`mean=卑贱的`、`high=高度`、
        // `many=多数`、`most=最多`、`old=以前`。正确取值都在**同一行的后面几个块**里，
        // 下面每个期望值都是 ECDICT 自己的词，不是任何人代拟的译法。
        //
        // 为什么是「逐词一张表」而不是一条规则，两条捷径都被实测否证过：
        //  * 「取首个非名词块的首个义项」有 4 个直接错——still→蒸馏(v. 蒸馏)、like→相似的(a. 相似的)、
        //    mean→低劣的(a. 低劣的)、most→大多数的(a. 大多数的)；
        //  * 按该中文串在 ECDICT 全语料里的出现次数排序，会改写 4531 条且条条变差（see→游览、take→抓）。
        //
        // 另注意 `first_noun_sense` 目前还**不认识**这些后续块，所以整表重生成会让这条变红。
        // 那是有意的记号：红的时候要么把逐词取值接进生成器，要么逐条重核，
        // 不许把这里的期望值改成生成器的输出。
        val expected = mapOf(
            "give" to "给",        // vt. 给, 授予, 供给
            "good" to "好的",      // a. 好的, 优良的
            "still" to "静止的",   // a. 静止的, 不动的
            "leave" to "离开",     // vt. 离开, 剩下
            "want" to "要",        // vt. 要, 希望, 应该
            "like" to "喜欢",      // vt. 喜欢, 愿意
            "mean" to "意谓",      // vt. 意谓, 想
            "high" to "高的",      // a. 高的, 高级的
            "many" to "许多的",    // a. 许多的
            "most" to "最",        // adv. 最, 最多
            "old" to "老的",       // a. 老的, 旧的
            // 第二批：频次 80～310 逐个读出来的同类缺陷，取值同样是同一行非名词块里的原词。
            "lead" to "引导",      // vt. 引导（原来发的是 n. 铅——金属那个名词）
            "keep" to "保持",      // vt. 保持（原 n. 生计）
            "start" to "开始",     // vi. 开始（原 n. 惊起）
            "carry" to "携带",     // vt. 携带（原 n. 进位）
            "set" to "放",         // vt. 放（原 n. 日落）
            "pay" to "支付",       // vt. 支付（原 n. 薪资）
            "produce" to "产生",   // vt. 产生（原 n. 生产品）
            "meet" to "遇见",      // vt. 遇见（原 n. 会）
            "right" to "正确的",   // a. 正确的（原 n. 权利）
            "full" to "充满的",    // a. 充满的（原 n. 全部）
            "little" to "小的",    // a. 小的（原 n. 一点点）
            "well" to "很好地",    // adv. 很好地（原 n. 井）
            "within" to "在...之内", // prep. 在...之内（原 n. 内部）
            "offer" to "提供",     // vt. 提供（原 n. 给予）
            "may" to "可以",       // aux. 愿能, 可以——首个义项是文言说法
            // 第三批：同一块里「第一个义项不是最常用那个」，取值全在该词自己的行内。
            "state" to "状态",     // 原发 州（n. 州, 状态, 情形, 国家…）
            "line" to "线",        // 原发 列（n. 列, 线, 绳…）
            "second" to "第二",    // 原发 秒
            "information" to "信息", // 原发 消息
            "even" to "甚至",      // 原发 相等的（首块是 a. 平坦的, 相等的…）
            "issue" to "问题",     // 原发 发行
            "lot" to "许多",       // 原发 运气
            "stand" to "站",       // 原发 站立
            "hold" to "握住",      // 原发 把握
            "effect" to "影响",    // 原发 结果
            "party" to "聚会",     // 原发 宴会
            "white" to "白色的",   // 原发 白色
            "black" to "黑色的",   // 原发 黑色
            "power" to "力量",     // 原发 力
            // 第四批：频次 300～760 里 243 个「名词块打头」的候选，逐条读下来只有这 7 个是真错的。
            // 其余那些（light=光、heart=心、trade=贸易、material=材料、season=季节…）本来就该是名词，
            // **照签名批量改掉它们是倒退**，所以这一批评的是「读过」而不是「匹配上签名」。
            "special" to "特别的",  // 原发 n. 那块的首义项「专辑」
            "military" to "军事的", // 原发 n. 军队
            "account" to "帐目",   // 原发 n. 报告；「账户」不在这行里（原文写「帐目」）
            "range" to "范围",     // 原发 n. 排
            "lie" to "说谎",       // 原发 n. 谎言
            "break" to "打破",     // 原发 n. 休息
            "pass" to "通过",      // 原发 n. 经过
            // 第五批：这次新放进来的核心虚词里，最常被看到的六个。
            "have" to "有",        // 原发 aux. 已经
            "but" to "但是",       // 原发 prep. 除了
            "up" to "向上",        // 原发 a. 向上的（「起床的」是它的第二个义项）
            "out" to "在外",       // 原发 a. 外面的（「熄灭的」）
            "about" to "关于",     // 原发 prep. 在...周围
            "as" to "当作",        // 原发 adv. 同样地
            // 第六批：`POS_MARKERS` 缺 `a.`，形容词词条系统性发到第二个（甚至 [机]/[计] 技术）义项。
            "just" to "刚刚",      // 原发 合理的
            "very" to "非常",      // 原发 恰好的
            "over" to "在...之上", // 原发 结束
        )
        val byWord = entries.associateBy { it.headword().lowercase() }
        val wrong = expected.mapNotNull { (word, want) ->
            val actual = byWord[word]?.motherTongueGloss()
            if (actual == want) null else "$word=${actual ?: "(缺)"}（应为 $want）"
        }
        assertTrue(
            "有 ${wrong.size} 个最常用的词发出去的是生僻名词义：$wrong。" +
                "这些是用户每天看到的行，取错块等于教错",
            wrong.isEmpty(),
        )
    }

    @Test
    fun chineseHeadwordAndChineseGlossAreTheSameString() {
        // 生成器里这两个字段本来就是同一个值（`words = {en, zh: gloss}`），
        // 所以它们一旦分开就说明有人改了其中一个。这不是洁癖：`words.zh` 是**搜索匹配用的词头**，
        // `glosses.zh` 是卡片背面那行。我只改过一个字段，于是 206 条留下这样的鬼影：
        // 背面写着「向下的」，但搜「丘陵」还能命中 down。
        val split = entries.mapNotNull { entry ->
            val head = (entry["words"] as? JsonObject)?.get("zh")?.jsonPrimitive?.content
            val gloss = (entry["glosses"] as? JsonObject)?.get("zh")?.jsonPrimitive?.content
            if (head == gloss) null else "${entry.headword()} words.zh=${head ?: "(无)"} glosses.zh=${gloss ?: "(无)"}"
        }
        assertTrue(
            "有 ${split.size} 条的中文词头与中文释义不一致：${split.take(6)}。" +
                "搜索页用前者、卡片用后者，不一致就等于留下搜得到的旧义项",
            split.isEmpty(),
        )
    }

    private fun JsonObject.headword(): String = getValue("words").jsonObject.getValue("en").jsonPrimitive.content

    private fun JsonObject.motherTongueGloss(): String? =
        (this["glosses"] as? JsonObject)?.get("zh")?.jsonPrimitive?.content

    private fun findModuleDir(): File {
        var cursor: File? = File("").absoluteFile
        while (cursor != null) {
            if (File(cursor, "src/main/AndroidManifest.xml").isFile) return cursor
            cursor = cursor.parentFile
        }
        throw IllegalStateException("找不到含 src/main/AndroidManifest.xml 的模块目录；工作目录是 ${File("").absolutePath}")
    }
}
