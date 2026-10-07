// Copyright (c) 2026 ilyskyo
// SPDX-License-Identifier: MIT

package com.ilyskyo.capturney

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.FixMethodOrder
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.MethodSorters

/**
 * `PreserveAppFiles` 自己也得有守卫，否则它只是一段「看起来会还原」的代码。
 *
 * ## 为什么必须是同一个类里的两条测试
 *
 * 跑完 `connectedDebugAndroidTest` 之后从 adb 去查 `files/` 是**查不到的**：
 * AGP 在跑完后会把被测应用卸载掉，`run-as <包名>` 只会回一句 unknown package。
 * 我第一版就是这么验的，两条 md5 都取到了同一句报错，`diff` 于是「IDENTICAL」——
 * 一次比没验还糟的假通过。
 *
 * 所以还原这件事只能在**同一次运行内**、下一条测试的眼皮底下证明：
 * A 往 `filesDir` 写哨兵（含一个子目录里的），它的 `after` 还原；
 * B 开头先看哨兵在不在。B 自己的 `before` 快照的是 A 还原之后的状态，
 * 所以只要还原漏了任何东西，B 就会直接看见。
 *
 * `@FixMethodOrder` 不是洁癖：JUnit 默认的方法顺序是哈希序，
 * 不钉住名字顺序，B 可能先跑，于是这条测试永远通过却什么都没测。
 */
@RunWith(AndroidJUnit4::class)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class PreserveAppFilesTest {

    @get:Rule
    val preserve = PreserveAppFiles()

    private val filesDir: File
        get() = InstrumentationRegistry.getInstrumentation().targetContext.filesDir

    @Test
    fun aWritesIntoTheAppDataDirectory() {
        val sentinel = File(filesDir, "__sentinel__.txt")
        val nestedDir = File(filesDir, "entries")
        nestedDir.mkdirs()
        sentinel.writeText("mutated")
        File(nestedDir, "__sentinel_nested__.txt").writeText("mutated")
        // 这条断言是给 B 做准备的：这里必须真的写成了，否则 B 的「看不见」是空的。
        assertTrue("哨兵没写成，B 就什么都测不到", sentinel.isFile && File(nestedDir, "__sentinel_nested__.txt").isFile)
    }

    @Test
    fun bSeesTheDirectoryRestored() {
        assertFalse(
            "PreserveAppFiles 没清掉测试新增的文件：跑过设备测试的机器上，" +
                "用户的 filesDir 里会留下这种东西（更糟的是它可能是一份被改过的日记）",
            File(filesDir, "__sentinel__.txt").exists(),
        )
        assertFalse(
            "子目录里的新增也没被还原——只覆盖顶层等于还原了一半",
            File(filesDir, "entries/__sentinel_nested__.txt").exists(),
        )
    }
}
