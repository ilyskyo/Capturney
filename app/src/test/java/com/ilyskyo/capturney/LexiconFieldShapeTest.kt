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
 * 而它每一条都可能直接出现在卡片上。这里守四条界面真正依赖的性质。
 *
 * ## 为什么是这四条
 *
 * - **音标必须恰好被一对斜杠包住**。`CaptureScreen` 是 `text = ipa` 原样渲染的，
 *   所以斜杠是数据的一部分而不是界面加的。`clean_ipa` 的规约就是「剥掉已有的一对、
 *   再包上一对」——遇到 ECDICT 里 `n. 甲 / n. 乙` 这种**两个变体**时它会漏，
 *   产出 `//ˈkʌp/ /ˈkʊp//` 那种东西。现在没有一条是这样，但没有任何东西阻止下一次生成变成这样。
 * - **每条都要有中文释义**。那是中文用户要被评级的背面；生成器里 `first_noun_sense` 返回
 *   None 时条目会被跳过，但兜底路径若改了就可能写出空串。
 * - **英文词头不许重复**。搜索页会把两条同名词头都列出来，用户看到的是「同一个词出现两遍」。
 * - **解析器要真的看到东西**。前三条都是「遍历后断言没有坏样本」，一旦解析本身失败，
 *   它们会集体变成真空通过——所以先断言条数。
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

    private fun JsonObject.headword(): String = getValue("words").jsonObject.getValue("en").jsonPrimitive.content

    private fun findModuleDir(): File {
        var cursor: File? = File("").absoluteFile
        while (cursor != null) {
            if (File(cursor, "src/main/AndroidManifest.xml").isFile) return cursor
            cursor = cursor.parentFile
        }
        throw IllegalStateException("找不到含 src/main/AndroidManifest.xml 的模块目录；工作目录是 ${File("").absolutePath}")
    }
}
