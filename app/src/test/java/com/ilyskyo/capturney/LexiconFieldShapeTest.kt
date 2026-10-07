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
            "may" to "愿能",
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
