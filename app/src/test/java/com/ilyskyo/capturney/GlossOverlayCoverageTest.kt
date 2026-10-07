// Copyright (c) 2026 ilyskyo
// SPDX-License-Identifier: MIT

package com.ilyskyo.capturney

import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `assets/lexicon/gloss-<lang>.json` 是叠在 `en.json` 上的**释义补丁**：
 * 日语/韩语用户翻到卡片背面看到的那一行就来自这里。它此前没有任何东西守着——
 * 唯一的检查在设备测试里（`GlossOverlayAssetTest`），而它只数条数是否相等。
 *
 * ## 四件事，每件都有各自的坏法
 *
 * - **id 必须还在词典里**。加载器遇到不认识的 id 只记一条 warning
 *   （`gloss-<lang>: no such entry …`）就跳过，于是「某个词被改名、或被重建挤掉」之后
 *   那一条释义**静默消失**：用户看到的是背面少了一行，而所有测试照样绿。
 * - **ja 与 ko 覆盖的必须是同一批词**。界面语言四种可选，母语释义不能一种有一种没有——
 *   那会让韩语用户拿到英文背面而日语用户不会。条数相等拦不住「A 有 ja 没 ko、B 有 ko 没 ja」，
 *   所以要按集合比。
 * - **条数不许倒退**。这份补丁是手工攒出来的，重新生成时最容易少带几条。
 * - **值必须真的是那个语言写的**。维基数据里不少 ja/ko 条目其实是拉丁转写或英文；
 *   那种值进卡片背面就是错的，而它在 JSON 里看起来完全正常。
 */
class GlossOverlayCoverageTest {

    private val lexicon: File by lazy { File(findModuleDir(), "src/main/assets/lexicon") }

    @Test
    fun everyOverlayIdStillResolvesToAnEntryInTheShippedLexicon() {
        val known = objectList("en.json").map { it.string("id") }.toSet()
        assertTrue("en.json 只解析出 ${known.size} 个 id，解析器大概瞎了", known.size >= 10_000)

        for (lang in LANGS) {
            val orphans = objectList("gloss-$lang.json").map { it.string("id") }.filterNot { it in known }
            assertTrue(
                "gloss-$lang 里有 ${orphans.size} 个 id 在 en.json 找不到：${orphans.take(8)}。" +
                    "加载器对这种条目只记一条 warning 就跳过，于是那行释义静默消失而测试照样绿",
                orphans.isEmpty(),
            )
        }
    }

    @Test
    fun japaneseAndKoreanCoverTheSameWords() {
        val byLang = LANGS.associateWith { lang -> objectList("gloss-$lang.json").map { it.string("id") }.toSet() }
        val ja = byLang.getValue("ja")
        val ko = byLang.getValue("ko")
        assertTrue("只在 ja 有的有 ${(ja - ko).size} 个：${(ja - ko).take(8)}", (ja - ko).size <= TOLERANCE)
        assertTrue("只在 ko 有的有 ${(ko - ja).size} 个：${(ko - ja).take(8)}", (ko - ja).size <= TOLERANCE)
    }

    @Test
    fun theOverlayNeverShrinks() {
        for (lang in LANGS) {
            val count = objectList("gloss-$lang.json").size
            assertTrue(
                "gloss-$lang 只剩 $count 条，低于已知下限 $MIN_ENTRIES——" +
                    "这份补丁是手工攒出来的，重新生成时最容易少带几条",
                count >= MIN_ENTRIES,
            )
        }
    }

    @Test
    fun everyValueIsActuallyWrittenInTheLanguageItClaims() {
        for (lang in LANGS) {
            val entries = objectList("gloss-$lang.json")
            assertTrue("只解析到 ${entries.size} 条，解析器大概瞎了", entries.isNotEmpty())
            val bad = entries.filter { item -> !item.string("word").any { char -> isOfScript(char, lang) } }
                .map { "${it.string("id")}=${it.string("word")}" }
            assertTrue(
                "gloss-$lang 里有 ${bad.size} 条值根本不含该语言的字符：${bad.take(8)}。" +
                    "这种值进卡片背面就是错的，而它在 JSON 里看起来完全正常",
                bad.isEmpty(),
            )
        }
    }

    private fun objectList(name: String): List<JsonObject> =
        Json.parseToJsonElement(File(lexicon, name).readText()).jsonObject.getValue("entries").jsonArray.map { it.jsonObject }

    private fun JsonObject.string(key: String): String = getValue(key).jsonPrimitive.content

    /** 假名/汉字（ja）与谚文（ko）。区间与 tools/fetch_wikidata.py 保持一致。 */
    private fun isOfScript(char: Char, lang: String): Boolean = when (lang) {
        "ja" -> char in '぀'..'ヿ' || char in 'ㇰ'..'ㇿ' || char in '一'..'鿿'
        "ko" -> char in '가'..'힣' || char in 'ᄀ'..'ᅟ'
        else -> false
    }

    private fun findModuleDir(): File {
        var cursor: File? = File("").absoluteFile
        while (cursor != null) {
            if (File(cursor, "src/main/AndroidManifest.xml").isFile) return cursor
            cursor = cursor.parentFile
        }
        throw IllegalStateException("找不到含 src/main/AndroidManifest.xml 的模块目录；工作目录是 ${File("").absolutePath}")
    }

    private companion object {
        val LANGS = listOf("ja", "ko")

        /** 已知下限：这份补丁手工攒到了 157 条，留一点余量但不许塌回去。 */
        const val MIN_ENTRIES = 150

        /** ja/ko 允许差几个词——维基数据里总有一个语言缺标签。差得多了才是漏。 */
        const val TOLERANCE = 40
    }
}
