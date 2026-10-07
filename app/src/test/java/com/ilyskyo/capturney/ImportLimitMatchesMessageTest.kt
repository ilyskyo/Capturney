// Copyright (c) 2026 ilyskyo
// SPDX-License-Identifier: MIT

package com.ilyskyo.capturney

import com.ilyskyo.capturney.data.repository.MAX_IMPORT_BYTES
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element

/**
 * 「文件太大（上限 8 MB）」这句是**代码里的一个数字**，而它被抄进了四种语言的文案里。
 *
 * 抄的东西会漂：有人把 `MAX_IMPORT_BYTES` 调成 16，四句文案仍然说 8 MB，于是 App 会
 * 接受一个它自己宣布拒绝不了的大小——用户照着文案以为 12 MB 传不进来，结果传进来了；
 * 或者反过来，一个 9 MB 的备份被拒，而屏幕上那句上限已经悄悄改成 16。两种都是文案在说谎，
 * 而说谎的文案比没有文案更糟：用户就是照它决定的。
 *
 * 所以这里不查文案写得好不好，只查**那一个数字与代码是否同一个数**，四语各自查一次。
 * 某一句里根本没有 MB 字样也算红：那说明有人改了措辞，这条测试就该被重新看过一次。
 */
class ImportLimitMatchesMessageTest {

    private val module: File by lazy { findModuleDir() }
    private val res: File get() = File(module, "src/main/res")

    @Test
    fun every_locale_states_the_same_limit_the_code_enforces() {
        val expectedMb = MAX_IMPORT_BYTES / (1024 * 1024)
        assertTrue(
            "上限不足 1 MB，这条测试的整除假设不成立：$MAX_IMPORT_BYTES",
            MAX_IMPORT_BYTES % (1024 * 1024) == 0,
        )

        val problems = LOCALES.mapNotNull { dir ->
            val message = string(File(res, "$dir/strings.xml"), "settings_import_bad")
                ?: return@mapNotNull "$dir：没有 settings_import_bad"
            val stated = MEGABYTE.findAll(message).map { it.groupValues[1].toInt() }.toList().distinct()
            when {
                stated.isEmpty() -> "$dir：文案里找不到「N MB」这样的写法（$message）"
                stated != listOf(expectedMb) -> "$dir：文案说 $stated，代码用的是 $expectedMb"
                else -> null
            }
        }

        assertTrue(
            "四种语言的导入上限与代码不一致：$problems。改 `MAX_IMPORT_BYTES` 的人不会想到去改四份文案，" +
                "而用户是照文案决定传多大的",
            problems.isEmpty(),
        )
    }

    private fun string(file: File, key: String): String? {
        val document = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file)
        val nodes = document.getElementsByTagName("string")
        for (index in 0 until nodes.length) {
            val element = nodes.item(index) as Element
            if (element.getAttribute("name") == key) return element.textContent
        }
        return null
    }

    private fun findModuleDir(): File {
        var cursor: File? = File("").absoluteFile
        while (cursor != null) {
            if (File(cursor, "src/main/res/values/strings.xml").isFile) return cursor
            cursor = cursor.parentFile
        }
        throw IllegalStateException("找不到 src/main/res/values/strings.xml；工作目录是 ${File("").absolutePath}")
    }

    private companion object {
        /** 默认包是英文，其余三种各有自己的目录。 */
        val LOCALES = listOf("values", "values-zh", "values-ja", "values-ko")

        val MEGABYTE = Regex("""(\d+)\s*[Mm][Bb]""")
    }
}
