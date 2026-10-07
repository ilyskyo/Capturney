// Copyright (c) 2026 ilyskyo
// SPDX-License-Identifier: MIT

package com.ilyskyo.capturney

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 设置页里有一句对用户做出的承诺：`there is no export or share entry point`（§8.4）。
 * 备份那一半已经有测试守着（`BackupWhitelistDisciplineTest`），这一半以前只靠没人写那行代码。
 *
 * ## 为什么静态扫而不是跑一遍界面
 *
 * 要守的不止「界面上有没有一颗按钮」，而是**应用有没有能力把一张照片递出去**。加这种能力
 * 可以小到只有一个 `FileProvider` 配置或清单里一句 `grantUriPermissions="true"`，
 * 两者都没有界面，UI 测试永远碰不到。而 `grantUriPermissions` 恰恰是 Android 上
 * 「把私有文件临时交给别的 App」的标准做法：将来有人为了「把这张存进相册」加它，
 * 隐私边界就从这里漏掉，界面上看起来仍然一切正常。
 *
 * ## 为什么不禁 `ACTION_SEND` / `EXTRA_TEXT`
 *
 * 那两个名字**收和发两边都用**：本应用就是分享的目标（别人把一段词递进这本日记，§4）。
 * 把它们列成禁词会挡住将来正确写出来的接收分支（`when (intent.action) { ACTION_SEND -> … }`），
 * 那种红只会把人推向「绕开测试」而不是「想清楚边界」。
 * 所以这里只列**只在往外给时才存在的 API**：选择器、FileProvider、往媒体库插图。
 *
 * ## 注释里出现也算
 *
 * 扫描逐字、不解析语法：提到也算。方向刻意保守——代价是多一句注释会让它红，
 * 换来的是「注释里写着将来要分享」这种意图也逮得住。要说明边界请避开这些确切的 API 名。
 */
class NoOutgoingShareSurfaceTest {

    private val module: File by lazy { findModuleDir() }

    @Test
    fun `no kotlin code uses an api that only exists to hand content outward`() {
        val offenders = File(module, "src/main/java").walk()
            .filter { it.isFile && it.name.endsWith(".kt") }
            .flatMap { file ->
                val lines = file.readLines()
                OUTGOING_ONLY.flatMap { token ->
                    lines.mapIndexedNotNull { index, line ->
                        if (line.contains(token)) "${file.name}:${index + 1} 用到 $token" else null
                    }
                }
            }
            .toList()

        assertTrue(
            "出现了把内容交出去的通道：$offenders。这本日记的价值建立在「照片只在这台机器上」（§8.4），" +
                "而且设置页已经对用户那么写了。真要加导出，先改那句承诺，而不是悄悄加一颗按钮",
            offenders.isEmpty(),
        )
    }

    @Test
    fun `the manifest grants no uri permissions and declares no content provider`() {
        val manifest = File(module, "src/main/AndroidManifest.xml").readText()
        assertTrue(
            "清单里出现 grantUriPermissions：那是把私有文件（含照片）临时交给别的 App 的那扇门",
            !manifest.contains("grantUriPermissions"),
        )
        assertTrue(
            "清单里出现了 <provider>：本应用没有任何对外的内容提供方，而它唯一的用处是把私有目录暴露出去",
            !Regex("<provider[\\s>]").containsMatchIn(manifest),
        )
        // 接收方向照旧要在：那是别人递给这本日记，不是日记递出去。
        assertTrue(
            "分享接收端不见了——§4 那条「在别的 App 里选中一段词、交给见词」的路就此断了",
            manifest.contains("android.intent.action.SEND"),
        )
    }

    @Test
    fun `there is no file-provider path configuration`() {
        val configs = File(module, "src/main/res").walk().filter { it.isFile }
            .filter { it.name.contains("file_paths", ignoreCase = true) || it.name.contains("file-paths", ignoreCase = true) }
            .map { it.name }
            .toList()
        assertTrue(
            "res 下有 FileProvider 的路径配置 $configs：它存在的唯一理由就是让别的 App 读到这里的文件",
            configs.isEmpty(),
        )
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
        /** 只在「往外给」时才存在的 API 名；收发通用的那些故意不在这里，理由见类注释。 */
        val OUTGOING_ONLY = listOf(
            "createChooser",
            "ChooserAction",
            "FileProvider",
            "getUriForFile",
            "insertImage",
            "MediaStore.Images.Media.insert",
            "android.permission.WRITE_EXTERNAL_STORAGE",
            "MediaStore.Images.Media.EXTERNAL_CONTENT_URI",
        )
    }
}
