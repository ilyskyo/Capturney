// Copyright (c) 2026 ilyskyo
// SPDX-License-Identifier: MIT

package com.ilyskyo.capturney

import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.junit.rules.ExternalResource

/**
 * 把 `filesDir` 原样备份、测试跑完再放回去。
 *
 * ## 为什么设备测试需要这个
 *
 * 跑 `connectedDebugAndroidTest` 的那台机器上，被测应用用的就是它自己的私有目录：
 * `deck.json`、`diary.json`、照片、贴纸、录音、DataStore 全在那里面。
 * 现在这几条测试只做导航与读语义，不改数据——**但那是巧合，不是设计**。
 * 谁往任何一条里加一次「按评级键」「删掉这条」「保存这张卡」，
 * 就会在跑测试的人毫无察觉的情况下改掉他的日记；而如果在自己正在用的手机上跑
 * （装机核对时这是常态），改掉的还是真实数据。
 * 所以这里把整棵 `filesDir` 快照下来，跑完还原：让「测试会不会动数据」不再取决于测试写了什么。
 *
 * ## 为什么必须由调用方 `RuleChain.outerRule(...)` 套在最外层
 *
 * 还原必须发生在 Activity 已经拆掉**之后**，否则应用还可能带着旧句柄把内容再写一遍。
 * 多个 `@get:Rule` 字段之间的执行顺序 JUnit 并不保证，
 * 靠「把这条声明在前面」来定顺序，就是一条会在某天悄悄失效的假设。
 * 所以用法固定为：
 *
 * ```
 * private val compose = createAndroidComposeRule<MainActivity>()
 * @get:Rule
 * val rules: TestRule = RuleChain.outerRule(PreserveAppFiles()).around(compose)
 * ```
 *
 * ## 备份放在 cacheDir 而不是 filesDir
 *
 * 放在被还原的那棵树里，等于自己吃自己。
 */
class PreserveAppFiles : ExternalResource() {

    private val targetContext = InstrumentationRegistry.getInstrumentation().targetContext
    private val filesDir: File = targetContext.filesDir
    private val backupRoot: File = File(targetContext.cacheDir, "files-snapshot")

    override fun before() {
        backupRoot.deleteRecursively()
        backupRoot.mkdirs()
        if (filesDir.isDirectory) filesDir.copyRecursively(backupRoot, overwrite = true)
    }

    override fun after() {
        // 先删再拷，而不是只覆盖：测试新增的文件（比如一次导入落地的照片）
        // 若只靠覆盖是清不掉的，那样「还原」就只还原了一半。
        filesDir.deleteRecursively()
        filesDir.mkdirs()
        if (backupRoot.isDirectory) backupRoot.copyRecursively(filesDir, overwrite = true)
        backupRoot.deleteRecursively()
    }
}
