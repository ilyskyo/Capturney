// Copyright (c) 2026 ilyskyo
// SPDX-License-Identifier: MIT

package com.ilyskyo.capturney

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element

/**
 * 「照片与 API Key 不出设备」是说明书里写死的产品边界（§8.4、§8.5），而它今天**只靠两份 XML 没被改坏**。
 *
 * ## 为什么要用测试钉住它
 *
 * 这条边界有两个方向都会出错，而且两个方向都无声：
 *
 * - **多传**：谁把云端白名单写成 `path="."`（或者只改了 31+ 那份、忘了 API ≤30 那份），
 *   照片和明文 API Key 就跟着 Google 的备份上了云端——Android 的默认云备份不是端到端加密的。
 *   界面上不会有任何异常，日志里也不会有。
 * - **少传**：谁新加一个写进 `filesDir` 的 JSON（用户词典、同步游标……）而没加进白名单，
 *   换机之后那份数据就安静地不存在。而 `AppContainer` 里那一行 `File(filesDir, "x.json")`
 *   看起来与备份配置毫无关系，没有任何东西会提醒他这两处是一个约定。
 *
 * 所以这里把**代码实际往 filesDir 顶层写的 JSON 文件名**与**两份备份规则里的 include**
 * 放在一起比：它们必须一模一样。这是唯一能让「加了一个文件」和「改了一份规则」同时变红的形状。
 *
 * ## 为什么 31+ 与 ≤30 两份都要比
 *
 * `dataExtractionRules` 管 API 31 以上，`fullBackupContent` 管以下。只断言其中一份，
 * 另一份可以悄悄漂成「全备」，而漂掉的那一半恰好是还在用的老设备。
 */
class BackupWhitelistDisciplineTest {

    private val module: File by lazy { findModuleDir() }
    private val res: File by lazy { File(module, "src/main/res") }

    @Test
    fun `the cloud whitelist is exactly the json files the app writes into filesDir`() {
        val declared = File(module, "src/main/java/com/ilyskyo/capturney/core/AppContainer.kt")
            .readText()
            // 只认「直接写在 filesDir 根上的文件」：目录（photos / entries / stickers / audio）
            // 本来就不该进白名单，而它们的名字不以 .json 结尾。
            .let { FILES_IN_FILES_DIR.findAll(it).map { m -> m.groupValues[1] }.toList() }
            .filter { it.endsWith(".json") }
            .distinct()
            .sorted()

        assertTrue("扫不到任何写进 filesDir 的 JSON——那条正则大概被重构挪走了：$declared", declared.isNotEmpty())

        val legacy = includesOf(File(res, "xml/backup_rules.xml"), inSection = null)
        val modern = includesOf(File(res, "xml/data_extraction_rules.xml"), inSection = "cloud-backup")

        assertEquals(
            "API ≤30 那份云白名单与代码实际写的 JSON 不一致。多出来的一律是隐私问题，" +
                "少掉的一律是换机后数据不见的问题",
            declared,
            legacy,
        )
        assertEquals(
            "API 31+ 那份 cloud-backup 与代码实际写的 JSON 不一致",
            declared,
            modern,
        )
    }

    @Test
    fun `neither rule file whitelists anything that could carry media or the key`() {
        val banned = listOf("photos", "entries", "stickers", "audio", "datastore", "cache")
        mapOf(
            "xml/backup_rules.xml" to includesOf(File(res, "xml/backup_rules.xml"), inSection = null),
            "xml/data_extraction_rules.xml" to includesOf(
                File(res, "xml/data_extraction_rules.xml"),
                inSection = "cloud-backup",
            ),
        ).forEach { (name, paths) ->
            paths.forEach { path ->
                val leaks = banned.filter { path.contains(it, ignoreCase = true) }
                assertTrue(
                    "$name 的云端白名单里有 `$path`，它会把媒体或明文密钥带出设备：$leaks",
                    leaks.isEmpty(),
                )
                assertTrue(
                    "$name 的云端白名单里出现了 `$path`：一条指向整目录或根的规则等于「全传」，" +
                        "而这本日记的全部价值在于照片只在这台机器上",
                    path != "." && !path.endsWith("/"),
                )
            }
        }
    }

    @Test
    fun `device transfer still carries the whole diary on purpose`() {
        val transfer = includesOf(File(res, "xml/data_extraction_rules.xml"), inSection = "device-transfer")
        // 面对面迁移是端到端加密的、且发生在用户主动换机那一刻，所以整本带过去（含照片）。
        // 这一条被改掉一定是有人决定收紧迁移范围，那该是一次看得见意图的改动。
        assertEquals("换机迁移的范围不再是我们承诺的那个整份带走", listOf("."), transfer)
    }

    @Test
    fun `the manifest points at both rule files that actually exist`() {
        val manifest = File(module, "src/main/AndroidManifest.xml").readText()
        assertTrue("allowBackup 不再是 true，两份规则文件都白写了", manifest.contains("""android:allowBackup="true""""))
        listOf(
            """android:fullBackupContent="@xml/backup_rules"""" to "xml/backup_rules.xml",
            """android:dataExtractionRules="@xml/data_extraction_rules"""" to "xml/data_extraction_rules.xml",
        ).forEach { (attribute, file) ->
            assertTrue("清单里没有 $attribute（少了它，那一档系统会回到「默认全备份」）", manifest.contains(attribute))
            assertTrue("$file 不存在，而清单指着它", File(res, file).isFile)
        }
    }

    /**
     * 取某个区段下的 include 路径。
     *
     * [inSection] 为 null 时取全部（`full-backup-content` 只有一个区段的语义），
     * 否则只取那个区段（31+ 那份同时有 cloud-backup 与 device-transfer，两半的边界正好相反）。
     */
    private fun includesOf(file: File, inSection: String?): List<String> {
        val document = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file)
        val roots = if (inSection == null) {
            listOf(document.documentElement)
        } else {
            document.getElementsByTagName(inSection).let { nodes ->
                (0 until nodes.length).map { nodes.item(it) as Element }
            }
        }
        return roots.flatMap { section ->
            section.getElementsByTagName("include").let { nodes ->
                (0 until nodes.length).mapNotNull { index ->
                    (nodes.item(index) as? Element)?.getAttribute("path")?.takeIf { it.isNotEmpty() }
                }
            }
        }.distinct().sorted()
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
        /** `File(appContext.filesDir, "deck.json")` 这一类：往 filesDir 根上落的那个文件名。 */
        val FILES_IN_FILES_DIR = Regex("""File\(\s*\w*\.?filesDir\s*,\s*"([^"]+)"\s*\)""")
    }
}
