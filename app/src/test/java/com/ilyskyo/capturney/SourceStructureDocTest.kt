// Copyright (c) 2026 ilyskyo
// SPDX-License-Identifier: MIT

package com.ilyskyo.capturney

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * `PRODUCT_SPEC.md` 第 10/11 节是对**读这个仓库的人**做的承诺：代码在哪里、有几个自绘图标、
 * 词典有多大。这类承诺坏掉的方式不是编译不过，而是**下次有人照着它找东西找不到**——
 * 而找不到的人只会认为文档没用，从此不读，于是这份文件变成一个装饰。
 *
 * 2026-10-09 审计时它已经在说谎，三处各自不同的数：
 * 图标 §10 说 7 个、§11 说 11 个、`THIRD_PARTY_NOTICES.md` 说 fourteen，实际是 15 个；
 * 词典写「ECDICT 12000 条」而发出去的是 13055 条——**12000 恰好是生成器那个默认 `--limit`**，
 * 也就是本仓库为它写过一整节误会的数（见 `docs/BUILD.md` 的「重生成不再静默换血」）。
 *
 * ## 守的是三条能精确判定的事
 *
 * 刻意**没有**写「§11 里出现的每个驼峰词都必须是一个真声明」这种看着更强的断言：
 * 结构树里写的是 `repository/ Deck · Diary · Lexicon · Settings` 这种**文件简称**
 * （真实文件叫 `DeckRepository.kt`），中文说明行里还会自然出现 `WorkManager`、`MediaPipe`
 * 这些外部名字。把它们都算违规得到的是一条天天误报的守卫，而那玩意的下场是被忽略——
 * 一条只在真漏时报的守卫才有机会活着。
 */
class SourceStructureDocTest {

    private val root: File by lazy { findRepositoryRoot() }
    private val spec: String by lazy { File(root, "docs/PRODUCT_SPEC.md").readText(Charsets.UTF_8) }
    private val notices: String by lazy { File(root, "THIRD_PARTY_NOTICES.md").readText(Charsets.UTF_8) }

    /** §11 的正文：从 `## 11.` 到 `## 12.`。 */
    private val structureSection: String
        get() = section(spec, "## 11.", "## 12.")

    @Test
    fun everySourceSubpackageIsNamedInTheStructureSection() {
        val pkgRoot = File(root, "app/src/main/java/com/ilyskyo/capturney")
        assertTrue("找不到包根，量具肯定不对", pkgRoot.isDirectory)
        val dirs = pkgRoot.walkTopDown()
            .filter { it.isDirectory && it != pkgRoot }
            .map { it.relativeTo(pkgRoot).path.replace(File.separatorChar, '/') }
            .toList()
        assertTrue("只枚举到 ${dirs.size} 个子包，解析大概是空的", dirs.size >= 20)

        val section = structureSection
        val undocumented = dirs.filterNot { rel ->
            // 树里写的是 `├── core/voice/`、`│   ├── detection/` 这种形式：按最后一段找即可，
            // 但必须要求它后面跟着 `/`，否则 `data` 会因为正文里出现 "database" 而被算成已记录。
            section.contains(rel.split('/').last() + "/")
        }
        assertTrue(
            "这些真实存在的子包在 §11 的结构图里没有位置：$undocumented。" +
                "结构图的价值是「不用翻目录就能知道东西在哪」，缺一个包就是缺一片",
            undocumented.isEmpty(),
        )
    }

    @Test
    fun everyKotlinFileNamedInTheStructureSectionExists() {
        val named = Regex("""[A-Za-z][A-Za-z0-9_]*\.kt""").findAll(structureSection).map { it.value }.toSet()
        assertTrue("§11 里只点到 ${named.size} 个 .kt 文件，正则大概是空的", named.size >= 15)
        val present = File(root, "app/src").walkTopDown().filter { it.isFile }.map { it.name }.toSet()
        val phantom = named.filterNot { it in present }
        assertTrue(
            "§11 点了这些不存在的文件名：$phantom。幽灵条目比缺条目更坏——" +
                "读者会去找一个从来就没有的东西，然后怀疑的是自己",
            phantom.isEmpty(),
        )
    }

    @Test
    fun theHandDrawnIconCountAgreesAcrossEveryPlaceThatStatesIt() {
        val icons = File(root, "app/src/main/java/com/ilyskyo/capturney/ui/icons/CapturneyIcons.kt")
        // `^` 在 Kotlin 的正则里默认只匹配整个字符串的开头，必须显式 MULTILINE 才逐行生效。
        // 少了这个标志，下面那条「量具别是空的」断言会立刻红——那正是它存在的理由：
        // 解析器瞎掉与真的没有图标，给出的信号一模一样。
        val actual = Regex("""^\s{4}val ([A-Z][A-Za-z0-9]*)\s*:\s*ImageVector""", RegexOption.MULTILINE)
            .findAll(icons.readText(Charsets.UTF_8)).map { it.groupValues[1] }.toList()
        assertTrue("图标文件里只认出 ${actual.size} 个 ImageVector，量具肯定不对", actual.size >= 10)

        val specIcons = Regex("""(\d+) 个图标全部自绘""").find(section(spec, "## 10.", "## 11."))?.groupValues?.get(1)
        val specTree = Regex("""CapturneyIcons（(\d+) 个手绘）""").find(structureSection)?.groupValues?.get(1)
        val noticesCount = Regex("""The (one|two|three|four|five|six|seven|eight|nine|ten|eleven|twelve|thirteen|fourteen|fifteen|sixteen|seventeen|eighteen|nineteen|twenty)\b icons""", RegexOption.IGNORE_CASE)
            .find(notices)?.groupValues?.get(1)

        assertEquals("§10 的图标数和代码不一致", actual.size.toString(), specIcons)
        assertEquals("§11 的图标数和代码不一致", actual.size.toString(), specTree)
        assertEquals(
            "THIRD_PARTY_NOTICES 的图标数与代码不一致（英文数词，见 WORD_NUMBERS）",
            WORD_NUMBERS[noticesCount?.lowercase()] ?: "读不到",
            actual.size,
        )
    }

    /**
     * §9.1 那四行配色必须对得上**它所指的那个定义处**，不是「文件里某处出现过」。
     *
     * 这条是被一次真实纠错逼出来的：`Background` 那一行原本写 `#FFF8F3`，
     * 而那个数确实**存在于代码里**——它是取景页一张 `@Preview` 的画布色
     * （`backgroundColor = 0xFFFFF8F3`）。所以「这个 hex 在代码里出现过」这种断言
     * 根本抓不到它：值是真的，用途是错的。真正的应用背景是 `wl_background`，
     * 浅色 `#FFFBF7`、深色 `#FF1A1512`，而深色那一半以前在规范里压根没有。
     */
    @Test
    fun the_palette_section_names_the_values_the_theme_actually_uses() {
        val colorCode = File(root, "app/src/main/java/com/ilyskyo/capturney/ui/theme/Color.kt").readText(Charsets.UTF_8)
        val section = section(spec, "## 9.", "## 10.")
        for (hex in listOf("FF8A65", "4DB6AC", "FFD54F")) {
            assertTrue("§9.1 写了 #$hex，但 Color.kt 里没有这个值", colorCode.contains(hex, ignoreCase = true))
            assertTrue("§9.1 里没有 #$hex 这一行", section.contains("#$hex", ignoreCase = true))
        }

        for ((file, label) in listOf("values" to "浅色", "values-night" to "深色")) {
            val xml = File(root, "app/src/main/res/$file/colors.xml")
            assertTrue("${label}的 colors.xml 找不到", xml.isFile)
            val declared = Regex("""name="wl_background"\s*>\s*#([0-9A-Fa-f]{6,8})\s*<""")
                .find(xml.readText(Charsets.UTF_8))?.groupValues?.get(1)
            assertTrue("${label}的 wl_background 读不到，量具有问题", declared != null)
            // 允许写 #RRGGBB 或 #AARRGGBB 两种形式，尾数不同不算错。
            val bare = declared!!.removePrefix("FF").take(6)
            assertTrue(
                "§9.1 的 Background 行没有写出${label}真正在用的 #$bare（themes/小组件/冷启动都读它）",
                section.contains("#$bare", ignoreCase = true) || section.contains(declared, ignoreCase = true),
            )
        }
    }

    private fun section(text: String, from: String, until: String): String {
        val a = text.indexOf(from)
        assertTrue("文档里找不到「$from」这一节", a >= 0)
        val b = text.indexOf(until, a)
        assertTrue("文档里找不到「$until」这一节的结尾", b > a)
        return text.substring(a, b)
    }

    private fun findRepositoryRoot(): File {
        var cursor: File? = File("").absoluteFile
        while (cursor != null) {
            if (File(cursor, "docs/PRODUCT_SPEC.md").isFile && File(cursor, "THIRD_PARTY_NOTICES.md").isFile) return cursor
            cursor = cursor.parentFile
        }
        throw IllegalStateException("找不到仓库根；工作目录是 ${File("").absolutePath}")
    }

    private companion object {
        /** 声明文件用英文写数词，所以要能翻回整数再比。 */
        val WORD_NUMBERS = mapOf(
            "one" to 1, "two" to 2, "three" to 3, "four" to 4, "five" to 5, "six" to 6,
            "seven" to 7, "eight" to 8, "nine" to 9, "ten" to 10, "eleven" to 11,
            "twelve" to 12, "thirteen" to 13, "fourteen" to 14, "fifteen" to 15,
            "sixteen" to 16, "seventeen" to 17, "eighteen" to 18, "nineteen" to 19, "twenty" to 20,
        )
    }
}
