// Copyright (c) 2026 ilyskyo
// SPDX-License-Identifier: MIT

package com.ilyskyo.capturney

import com.ilyskyo.capturney.vision.CloudVisionEngine
import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「不自带在线 AI：要用户自己的密钥，而且默认关闭」是计划里写死的一条，也是这个开源仓库的信誉所在。
 * 它今天成立（`cloudEnabled = false`、`cloudReady` 要求密钥非空、端点强制 HTTPS），
 * 但**每一次翻转都不会有界面异常**，所以这里把三处各钉一刀。
 *
 * ## 三个方向都会无声
 *
 * - **默认值被翻成 true**：某个重构把 data class 的默认改了，或读取那侧的 `?: false` 被写成 `?: true`。
 *   于是一台新装机的手机开始带着用户从没同意过的流量往外发，而用户从来没碰过那个开关。
 *   两条分别断言——只断言其中一条，另一条可以单独翻掉。
 * - **密钥被铸进仓库**：`DEFAULT_ENDPOINT` 这种「默认值」离「默认密钥」只差一次便利的提交，
 *   而一旦带上真密钥，这条开源承诺立刻变成一句假话，并且 git 历史里删不干净。
 *   所以扫的是**整个仓库的文本**，测试与 Gradle 脚本也算。
 * - **明文出站**：把 API Key 走 http 发出去，跟把它写进备份文件是一件事的两种写法。
 *   端点必须 https，清单里也不许开 `usesCleartextTraffic`。
 */
class UserSuppliedAiOnlyTest {

    private val module: File by lazy { findModuleDir() }
    private val repoRoot: File by lazy { module.parentFile ?: module }

    @Test
    fun `the cloud backend is off by default both in the model and on the way out of storage`() {
        val settings = File(
            module,
            "src/main/java/com/ilyskyo/capturney/data/repository/SettingsRepository.kt",
        ).readText()

        assertTrue(
            "Settings 数据类里 cloudEnabled 的默认值不再是 false——新装机的设备会自己往外发",
            Regex("val\\s+cloudEnabled:\\s*Boolean\\s*=\\s*false").containsMatchIn(settings),
        )
        assertTrue(
            "从偏好里读 cloudEnabled 时兜底不再是 false——第一次读旧数据会把云端打开",
            Regex("""CLOUD_ON\]\s*\?:\s*false""").containsMatchIn(settings),
        )
        assertTrue(
            "cloudReady 不再同时要求「开着」与「有密钥」：只有一个是拦不住往外发的",
            Regex("""cloudEnabled\s*&&\s*cloudApiKey\.isNotBlank\(\)""").containsMatchIn(settings),
        )
    }

    @Test
    fun `no credential-looking literal is committed anywhere in the repository`() {
        val hits = repoRoot.walk()
            .filter { it.isFile && it.extension in TRACKED_EXTENSIONS }
            .filterNot { it.isInBuildDir() }
            .flatMap { file ->
                val lines = file.readLines()
                SECRET_SHAPES.mapIndexedNotNull { shape, regex ->
                    val index = lines.indexOfFirst { regex.containsMatchIn(it) }
                    if (index >= 0) "${relativeToRepo(file)}:${index + 1} 像 ${SECRET_NAMES[shape]}" else null
                }
            }
            .toList()

        assertTrue(
            "仓库里出现了像密钥的东西：$hits。这个 App 只接受用户自己填的密钥（默认关闭），" +
                "而铸进来的密钥除了毁掉那条承诺之外，在 git 历史里也删不干净",
            hits.isEmpty(),
        )
    }

    @Test
    fun `outgoing ai traffic cannot be plaintext`() {
        val engine = File(module, "src/main/java/com/ilyskyo/capturney/vision/CloudVisionEngine.kt").readText()
        assertTrue(
            "默认的视觉端点不再是 https——密钥会跟着正文一起被人看见",
            Regex("""DEFAULT_ENDPOINT\s*=\s*"https://""").containsMatchIn(engine),
        )
        val manifest = File(module, "src/main/AndroidManifest.xml").readText()
        assertTrue(
            "清单开了 usesCleartextTraffic：那等于允许填进去的密钥走明文网络",
            !manifest.contains("usesCleartextTraffic"),
        )
    }

    /**
     * 「端点不是 https 就不发」这条**用行为验**，而不是在源码里 grep 那句 `startsWith`。
     *
     * 原来那一条 grep 守卫的问题是它只证明那串字符还在：把条件写成 `!startsWith("http")`
     * （于是 `http://` 也能过）、或者把这句检查从 `unavailableReason()` 挪到一个永远走不到的
     * 分支里，字符串都还在、测试都还绿，而用户的密钥与照片已经在往外发了。
     * 这里直接把三种端点喂进真的构造函数，读它自己给出的答复。
     */
    @Test
    fun `a non-https endpoint makes the engine refuse instead of sending the key`() {
        val plaintext = CloudVisionEngine(apiKey = "user-key", model = "a-model", endpoint = "http://example.invalid/v1")
        assertTrue(
            "http 端点没有被拒绝：这条请求会把 API Key 以明文发出去",
            plaintext.unavailableReason() != null,
        )

        // 一个连 scheme 都没有的端点同样不许发——URL() 会按 http 解析它。
        val schemeless = CloudVisionEngine(apiKey = "user-key", model = "a-model", endpoint = "example.invalid/v1")
        assertTrue(
            "没有 scheme 的端点没有被拒绝，而它会被当成明文发出去",
            schemeless.unavailableReason() != null,
        )

        val https = CloudVisionEngine(apiKey = "user-key", model = "a-model", endpoint = "https://example.invalid/v1")
        assertTrue(
            "https + 有密钥 + 有模型 仍然被拒绝：那条承诺不该顺手把能用的路也堵掉。实际理由：${https.unavailableReason()}",
            https.unavailableReason() == null,
        )
    }

    /** 失败消息里要的是「仓库里的哪一处」，不是整条绝对路径。 */
    private fun relativeToRepo(file: File): String = repoRoot.toPath().relativize(file.toPath()).toString()

    /** 构建产物里会有「像密钥」的十六进制串与打包进来的第三方文本，那些不是我们提交的。 */
    private fun File.isInBuildDir(): Boolean =
        absolutePath.split('\\', '/').any { it == "build" || it == ".gradle" }

    private fun findModuleDir(): File {
        var cursor: File? = File("").absoluteFile
        while (cursor != null) {
            if (File(cursor, "src/main/AndroidManifest.xml").isFile) return cursor
            cursor = cursor.parentFile
        }
        throw IllegalStateException("找不到含 src/main/AndroidManifest.xml 的模块目录；工作目录是 ${File("").absolutePath}")
    }

    private companion object {
        val TRACKED_EXTENSIONS = setOf("kt", "kts", "xml", "json", "md", "gradle", "properties", "toml", "yml", "yaml")

        /**
         * 形状而不是内容：Anthropic 的 `sk-ant-`、OpenAI 的 `sk-`、AWS 的长期 id、PEM 私钥头、
         * 以及「键名 = 一长串」这种手抄密钥最常见的样子。
         * 每条都要求**引号里的字面量**，所以文档里谈到 `sk-xxx` 这类占位写法不会误伤。
         */
        val SECRET_SHAPES = listOf(
            Regex(""""sk-(?:ant-)?[A-Za-z0-9_\-]{12,}"""),
            Regex(""""AKIA[0-9A-Z]{16}"""),
            Regex(""""-----BEGIN [A-Z ]*PRIVATE KEY-----"""),
            Regex("""(?i)(api[_-]?key|secret|token|authorization)\s*=\s*"[A-Za-z0-9_\-\.]{20,}\""""),
        )

        val SECRET_NAMES = listOf("服务商密钥", "AWS 长期密钥", "私钥", "赋了字面量的密钥字段")
    }
}
