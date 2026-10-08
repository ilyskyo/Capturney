// Copyright (c) 2026 ilyskyo
// SPDX-License-Identifier: MIT

package com.ilyskyo.capturney

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 清单里每写一条 `android:required="false"`，都是在**对用户许诺**：
 * 少了这件硬件，本应用仍然装得上、仍然能用。`AndroidManifest.xml` 的注释把这件事说得很明白
 * （无相机的平板靠牌组/复习/相册导入/手写词，无麦克风的设备只少一种录入方式）。
 *
 * 而 2026-10-08 之前，全仓库 `hasSystemFeature` 出现 **0 次**——也就是说这条许诺是空的：
 * 无相机的设备装得上，走进取景页之后绑定失败，而失败只写了一行 `Log.w`，
 * 屏幕上留下一颗**按下去什么都不会发生的快门**。这类东西没有崩溃、没有报错、
 * 类型检查与单测全绿，所以只有真机拿在手里才会被发现——而那正是它一直留到今天的理由。
 *
 * ## 判据为什么是「查过 或 点名」而不是「都给我查」
 *
 * 有些可选硬件不需要在运行时问：`camera.autofocus` 影响的是取景细节，CameraX 对定焦镜头
 * 本来就有自己的降级路径，为它写一句用户文案反而是噪音。所以规则写成集合相等：
 * 每一条 `required="false"` 要么在源码里出现对应的 `PackageManager.FEATURE_*`，
 * 要么出现在下面这份**写明理由**的名单里。两边都必须精确：
 * 新加一条可选硬件而没想过 → 红；哪天真的开始查了 → 那份名单还在 → 也红（逼你把它删掉）。
 */
class OptionalHardwareFeatureTest {

    @Test
    fun every_optional_hardware_is_either_checked_at_runtime_or_named_with_a_reason() {
        val manifest = File(findRepositoryRoot(), "app/src/main/AndroidManifest.xml").readText(Charsets.UTF_8)
        val optional = USES_FEATURE.findAll(manifest)
            .map { it.groupValues[1] to it.groupValues[2] }
            .filter { it.second == "false" }
            .map { it.first }
            .toList()
        assertTrue(
            "从清单里只认出 ${optional.size} 条 required=\"false\" 的特性——解析器大概是瞎了，" +
                "那样下面这条会永远通过",
            optional.size >= 3,
        )

        val sources = File(findRepositoryRoot(), "app/src/main/java").walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .joinToString("\n") { it.readText(Charsets.UTF_8) }

        val unchecked = optional.filterNot { sources.contains(featureConstantOf(it)) }
        val missingReason = unchecked - DECLARED_UNCHECKED.keys
        assertTrue(
            "这些可选硬件既没在运行时被问过，也没在下面这份名单里写明理由：$missingReason。" +
                "清单里那句 required=\"false\" 是对用户的许诺，不是给商店筛选用的一行配置",
            missingReason.isEmpty(),
        )

        val stale = DECLARED_UNCHECKED.keys - unchecked.toSet()
        assertTrue(
            "这份名单里这几条已经不成立了（代码现在会查它，或清单已经不再声明它）：$stale。" +
                "过期的豁免和新加的漏洞一样危险——它让这条守卫看起来还在管事",
            stale.isEmpty(),
        )
    }

    /**
     * `android.hardware.camera.any` → `FEATURE_CAMERA_ANY`。
     *
     * 顺序是**先换点号再去前缀**：`uppercase()` 不会把点变成下划线，
     * 所以先去前缀的话匹配的是 `ANDROID_HARDWARE_`，而那一刻字符串还是
     * `ANDROID.HARDWARE.CAMERA.ANY`——前缀永远对不上，量具会把每一条都说成「没查过」，
     * 并在一个都不该红的干净仓库上长红（这一条就是这么被抓到的）。
     *
     * 按名字推而不是查表：清单里的特性名与 `PackageManager` 的常量名本来就是同一个来源，
     * 写成映射表的话新加一条硬件时要改两处，而那正是会漏的地方。
     */
    private fun featureConstantOf(name: String): String =
        "FEATURE_" + name.uppercase().replace('.', '_').removePrefix("ANDROID_HARDWARE_")

    private fun findRepositoryRoot(): File {
        var cursor: File? = File("").absoluteFile
        while (cursor != null) {
            if (File(cursor, "app/src/main/AndroidManifest.xml").isFile) return cursor
            cursor = cursor.parentFile
        }
        throw IllegalStateException("找不到仓库根；工作目录是 ${File("").absolutePath}")
    }

    private companion object {
        /**
         * `<uses-feature>` 的两个属性。**清单里它们是分行写的**，
         * 所以这里靠 `\s+`（含换行）而不是 `[ ]+` 来跨过那一次缩进。
         * 组 1 是特性名，组 2 是 `required` 的值。
         */
        val USES_FEATURE = Regex(
            """<uses-feature\s+android:name="([^"]+)"\s+android:required="([^"]+)"""",
        )
        /**
         * 声明了可选、但**故意**不在运行时问的硬件，以及为什么不问。
         *
         * `microphone` 不在这里了：2026-10-09 起 `VoiceRecorder.start()` 先问 `FEATURE_MICROPHONE`，
         * 缺它的设备拿到 `TakeNotice.NO_MIC` 那一句实话，而不是「出问题了，再试一次」。
         * 所以留在这份名单里的那一条是**结论**，不是欠账。
         */
        val DECLARED_UNCHECKED = mapOf(
            "android.hardware.camera.autofocus" to
                "定焦镜头由 CameraX 自己降级，没有需要单独告诉用户的那一种失败",
        )
    }
}
