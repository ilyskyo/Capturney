// Copyright (c) 2026 ilyskyo
// SPDX-License-Identifier: MIT

package com.ilyskyo.capturney

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 这个仓库的依赖规矩是用户定的：**全部钉死，不用 `@latest` / `+`，版本集中在 catalog 里**。
 * 规矩写在文档里，但没有任何东西守着它——而它坏掉的方式恰好是「今天还能构建」：
 *
 * - 一条 `implementation("androidx.foo:bar:1.2.3")` 混进 build 文件，catalog 就再也不是唯一来源，
 *   同一个库从此可以在两处写着不同版本；
 * - 一个 `[libraries]` 条目不带版本，只有当 BOM 恰好管着它时才碰巧能构建，
 *   而下一个人看不出这条是「刻意靠 BOM」还是「忘了写」；
 * - 一个 `1.2+` 或 `latest` 让构建变成时间的函数：同一份 commit 上周能构建、这周未必，
 *   而开源项目的 CI 红成随机事件时，人们开始忽略它——那才是真正的损失。
 *
 * ## 为什么第一条是「集合相等」而不是「不许有漏的」
 *
 * catalog 里确实有一批合法的不带版本条目：九个 Compose 构件由 `androidx-compose-bom` 供版本。
 * 所以规则写成「不带版本的集合必须**等于**下面这份显式名单」：多一个（有人新加依赖忘了写版本，
 * 或者偷偷靠 BOM）与少一个（有人把 BOM 管理的构件删了/补了版本）都会红。
 * 写成「不许出现不带版本的条目」会得到一条天天误报的守卫，而它的下场是被忽略。
 *
 * ## 为什么还要断言「我确实看到了多少条」
 *
 * 这条测试自己就中过一次这个招：条目起始行原本用 `Regex.matches()` 判定，而 `matches` 是**整行**
 * 匹配，条目行在 `{` 后面还跟着 group/name 一长串——于是**一个条目都没解析出来**，
 * 规则从写下第一版起就没检查过任何东西，而「没有违规」和「什么都没看」给出的信号完全一样。
 * 集合级检查必须同时断言输入非空，否则空集永远通过。
 */
class DependencyPinningDisciplineTest {

    private val module: File by lazy { findModuleDir() }
    private val catalog: File get() = File(module, "gradle/libs.versions.toml")

    @Test
    fun the_entries_without_a_version_are_exactly_the_bom_managed_ones() {
        val entries = catalog.readLines().entriesOfSections("libraries", "plugins")
        assertTrue(
            "只从 catalog 里解析出 ${entries.size} 条依赖/插件——解析器大概是瞎了，" +
                "那样的话下面那条集合相等会永远通过（这个坑这条测试自己踩过一次）",
            entries.size >= 30,
        )

        val withoutVersion = entries.filter { !it.hasVersionSource }.map { it.alias }.toSet()
        assertEquals(
            "不带版本的条目集合与显式的 BOM 名单不一致。多出来的那些要么忘了写版本、" +
                "要么在偷偷靠 BOM——两种都得摆到台面上来说明",
            BOM_MANAGED,
            withoutVersion,
        )
    }

    @Test
    fun no_floating_version_is_declared_anywhere() {
        val files = listOf(
            catalog,
            File(module, "build.gradle.kts"),
            File(module, "app/build.gradle.kts"),
            File(module, "settings.gradle.kts"),
        )
        val offenders = files.flatMap { file ->
            file.readLines().mapIndexedNotNull { index, line ->
                val hit = FLOATING.find(line)?.value ?: FLOATING_INLINE.find(line)?.value
                hit?.let { "${relative(file)}:${index + 1} 用了 $it" }
            }
        }
        assertTrue(
            "出现浮动版本：$offenders。这让构建变成时间的函数——同一个 commit 上周能构建、这周未必，" +
                "而 CI 红成随机事件之后人们就开始忽略它",
            offenders.isEmpty(),
        )
    }

    @Test
    fun build_files_never_write_a_coordinate_by_hand() {
        val offenders = listOf(
            File(module, "build.gradle.kts"),
            File(module, "app/build.gradle.kts"),
            File(module, "settings.gradle.kts"),
        ).flatMap { file ->
            file.readLines().mapIndexedNotNull { index, line ->
                if (INLINE_COORDINATE.containsMatchIn(line)) "${relative(file)}:${index + 1}" else null
            }
        }
        assertTrue(
            "build 文件里出现了内联坐标：$offenders。版本集中在一处才有意义——写在这里的那个数字" +
                "不会跟着 catalog 升级，同一个库从此可以在两处不同版本",
            offenders.isEmpty(),
        )
    }

    /**
     * 取给定 section 里的**条目**，并带上别名与起始行号。
     *
     * 按块读而不是按行读：条目可能写成一行，也可能写成三行（`name = {` 换行 `group = …`
     * 换行 `version.ref = …` `}`）。按行判会把续行当成独立条目，于是「版本写在下一行」的
     * 合法写法被报成没版本——一条天天误报的守卫，最后只会被人忽略掉。
     */
    private fun List<String>.entriesOfSections(vararg sections: String): List<Entry> {
        val wanted = sections.toSet()
        val found = mutableListOf<Entry>()
        var section = ""
        var open: Pair<String, StringBuilder>? = null
        var startAt = 0
        forEachIndexed { index, line ->
            if (line.startsWith("[")) {
                section = line.trim('[', ']', ' ')
                return@forEachIndexed
            }
            if (section !in wanted) return@forEachIndexed
            val current = open
            if (current == null) {
                val alias = ENTRY_START.find(line)?.groupValues?.get(1)
                if (alias != null) {
                    if (line.contains("}")) {
                        found += Entry(section, alias, line, index + 1)
                    } else {
                        open = alias to StringBuilder(line)
                        startAt = index + 1
                    }
                }
            } else {
                val merged = current.second.append(line).toString()
                if (line.contains("}")) {
                    found += Entry(section, current.first, merged, startAt)
                    open = null
                }
            }
        }
        return found
    }

    /** catalog 里的一条依赖/插件。`line` 是整块拼起来之后的文本，多行写法也在一起。 */
    private class Entry(val section: String, val alias: String, val line: String, val lineNumber: Int) {
        /**
         * 只认两种确切写法。不认「这一行里出现过 version 这个字」——
         * 那种写法会被 `name = "no-version"` 之类的 artifact 名白白满足（这条测试第一次证伪就是这么没红成）。
         */
        val hasVersionSource: Boolean get() = line.contains("version.ref") || line.contains("version =")
    }

    private fun relative(file: File): String = file.name

    private fun findModuleDir(): File {
        var cursor: File? = File("").absoluteFile
        while (cursor != null) {
            if (File(cursor, "gradle/libs.versions.toml").isFile) return cursor
            cursor = cursor.parentFile
        }
        throw IllegalStateException("找不到 gradle/libs.versions.toml；工作目录是 ${File("").absolutePath}")
    }

    private companion object {
        /**
         * 由 `androidx-compose-bom` 供版本的那九个构件。
         *
         * 这份名单是**声明**而不是推断：新加一个靠 BOM 的构件，就得同时把它写进这里，
         * 那一刻作者会被迫想一下「我是故意的吗」。
         */
        val BOM_MANAGED = setOf(
            "androidx-compose-ui",
            "androidx-compose-ui-graphics",
            "androidx-compose-ui-tooling",
            "androidx-compose-ui-tooling-preview",
            "androidx-compose-ui-test-junit4",
            "androidx-compose-ui-test-manifest",
            "androidx-compose-material3",
            "androidx-compose-material3-window-size",
            "androidx-compose-material-icons-extended",
        )

        /** 条目的起始行，捕获组 1 是别名。用 `find` 而不是 `matches`：后者要求整行匹配。 */
        val ENTRY_START = Regex("""^\s*([A-Za-z0-9_.\-]+)\s*=\s*\{""")

        /**
         * 只在**版本位置**上认浮动写法，避免把 `noCompress += listOf(...)` 这种代码当成违规。
         *
         * 两种形状都要认：`[versions]` 里是裸的 `coreKtx = "1.2.+"`，条目里是内联的
         * `version = "1.2.+"`。只写后者会漏掉最常漂的那一种。
         */
        val FLOATING = Regex(
            """^\s*[A-Za-z0-9_.\-]+\s*=\s*"[^"]*(\+|[Ll]atest|SNAPSHOT)[^"]*"""",
        )

        /** 单行写法里的 `version = "1.2.+"` 不在行首，上面那条按行首匹配认不到，单独认一次。 */
        val FLOATING_INLINE = Regex("""version\s*=\s*"[^"]*(\+|[Ll]atest|SNAPSHOT)[^"]*"""")

        /** `"group.art:artifact:1.2"`：带点号的组名 + 两段冒号 + 以数字开头的版本。 */
        val INLINE_COORDINATE = Regex(""""[A-Za-z0-9_.\-]+\.[A-Za-z0-9_.\-]+:[A-Za-z0-9_.\-]+:[0-9]""")
    }
}
