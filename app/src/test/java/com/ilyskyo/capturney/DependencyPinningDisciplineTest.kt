// Copyright (c) 2026 ilyskyo
// SPDX-License-Identifier: MIT

package com.ilyskyo.capturney

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 这个仓库的依赖规矩是用户定的：**全部钉死，不用 `@latest` / `+`，版本集中在 catalog 里**。
 * 规矩写在文档里，但没有任何东西守着它——而它坏掉的方式恰好是「今天还能构建」：
 *
 * - 一条 `implementation("androidx.foo:bar:1.2.3")` 混进 build 文件，catalog 就再也不是唯一来源，
 *   同一个库从此可以在两处写着不同版本；
 * - 一个 `[libraries]` 条目漏了版本，只有当 BOM 恰好管着它时才碰巧能构建，
 *   而下一个人看不出这条是「靠 BOM」还是「忘了写」；
 * - 一个 `1.2+` 或 `latest` 让构建变成时间的函数：同一份 commit 上周能构建、这周未必，
 *   而开源项目的 CI 红成随机事件时，人们开始忽略它——那才是真正的损失。
 *
 * 所以这里断言的是**形状**：每条依赖都要有版本来源、版本串里不许有浮动写法、
 * build 文件里不许出现内联坐标。三条都是集合级/全文级的检查，不针对某个具体库，
 * 因此升级依赖时不会碍事。
 */
class DependencyPinningDisciplineTest {

    private val module: File by lazy { findModuleDir() }
    private val catalog: File get() = File(module, "gradle/libs.versions.toml")

    @Test
    fun every_catalog_entry_declares_a_version_source() {
        val offenders = catalog.readLines().entriesOfSections("libraries", "plugins")
            // 认两种确切的写法，不认「这一行里出现过 version 这个字」：
            // 后者会被 `name = "no-version"` 这种artifact 名白白满足掉（我第一次证伪就是这么没红成）。
            .filter { entry -> !entry.line.contains("version.ref") && !entry.line.contains("version =") }
            .map { "${it.section} 第 ${it.lineNumber} 行" }

        assertTrue(
            "catalog 里这些条目没有版本来源：$offenders。漏了版本只有在「BOM 恰好管着它」时才碰巧能构建，" +
                "而下一个人分不出这条是刻意靠 BOM 还是忘了写——要真靠 BOM，就写一行注释说明",
            offenders.isEmpty(),
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
                FLOATING.find(line)?.value?.let { "${relative(file)}:${index + 1} 用了 $it" }
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
     * 取给定 section 里的**条目**（一条依赖），并带上起始行号。
     *
     * 必须按块读而不是按行读：catalog 里有的条目写成一行，有的写成三行
     * （`name = {` 换行 `group = …` 换行 `version.ref = …` `}`）。按行判会把后两种的
     * 续行当成独立条目，于是「版本写在下一行」的合法写法会被报成没版本——
     * 一条天天误报的守卫，最后只会被人直接忽略掉。
     */
    private fun List<String>.entriesOfSections(vararg sections: String): List<EntryLine> {
        val wanted = sections.toSet()
        val found = mutableListOf<EntryLine>()
        var section = ""
        var buffer: String? = null
        var startAt = 0
        forEachIndexed { index, line ->
            if (line.startsWith("[")) {
                section = line.trim('[', ']', ' ')
                return@forEachIndexed
            }
            if (section !in wanted) return@forEachIndexed
            val open = buffer
            if (open == null) {
                if (ENTRY_START.matches(line)) {
                    if (line.contains("}")) found += EntryLine(section, line, index + 1) else {
                        buffer = line
                        startAt = index + 1
                    }
                }
            } else {
                val merged = open + line
                if (line.contains("}")) {
                    found += EntryLine(section, merged, startAt)
                    buffer = null
                } else {
                    buffer = merged
                }
            }
        }
        return found
    }

    private class EntryLine(val section: String, val line: String, val lineNumber: Int)

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
        /** 条目的起始行：`some-lib = {`。版本可能写在同一行，也可能写在后面的续行里。 */
        val ENTRY_START = Regex("""^\s*[A-Za-z0-9_.\-]+\s*=\s*\{""")

        /**
         * 只在**版本位置**上认浮动写法，避免把 `noCompress += listOf(...)` 这种代码当成违规。
         *
         * 两种形状都要认：`[versions]` 里是裸的 `coreKtx = "1.2.+"`，
         * 条目里是内联的 `version = "1.2.+"`。只写后者会漏掉最常漂的那一种。
         */
        val FLOATING = Regex("""^\s*[A-Za-z0-9_.\-]+(\.ref)?\s*=\s*"[^"]*(\+|[Ll]atest|SNAPSHOT)[^"]*"|version\s*=\s*"[^"]*(\+|[Ll]atest|SNAPSHOT)[^"]*"""")

        /** `"group.art:artifact:1.2"`：带点号的组名 + 两段冒号 + 以数字开头的版本。 */
        val INLINE_COORDINATE = Regex(""""[A-Za-z0-9_.\-]+\.[A-Za-z0-9_.\-]+:[A-Za-z0-9_.\-]+:[0-9]""")
    }
}
