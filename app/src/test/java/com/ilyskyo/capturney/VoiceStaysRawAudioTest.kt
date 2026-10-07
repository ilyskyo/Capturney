// Copyright (c) 2026 ilyskyo
// SPDX-License-Identifier: MIT

package com.ilyskyo.capturney

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 语音日记那条设计是**故意不做语音识别**的：`Entry.audioPath` 的注释写着「**不转文字。**
 * …所以这里没有语音识别、没有云端、也没有后台服务：录、存、放、删」。
 *
 * 这三句各守一刀，因为它们坏起来都不响：
 *
 * - **识别**：接一个 `SpeechRecognizer` 或系统的 `RecognizerIntent` 只要几十行，界面上还很好用，
 *   但它把这本日记从「记下你看到与听到的」变成「把你的话变成待办文本」——那是另一类产品，
 *   而这条边界写在模型注释里、没有任何东西拦着。
 * - **上传**：这台设备上唯一会出网的代码是用户自带密钥的视觉后端。照片发给它是**写在设置页里的取舍**
 *   （默认关、要用户自己的 key、强制 https），而**录下来的声音从来没有被同意过要出去**——
 *   所以那条网络路径不许引用音频相关的任何东西。
 * - **后台**：录音是用户在某一页上主动按的，不该有常驻服务替他决定什么时候录。
 *
 * 断言的是**名字**而不是行为：静态扫源码。这类边界一旦越过去，行为测试往往也跟着改了，
 * 只有名字会在 diff 里先露出来。
 */
class VoiceStaysRawAudioTest {

    private val module: File by lazy { findModuleDir() }
    private val sources: File get() = File(module, "src/main/java")

    @Test
    fun nothing_transcribes_the_memo() {
        val files = sourceFiles()
        val hits = files.flatMap { file ->
            file.readLines().mapIndexedNotNull { index, line ->
                TRANSCRIBING.firstOrNull { line.contains(it) }
                    ?.let { "${relative(file)}:${index + 1} 用到 $it" }
            }
        }
        assertTrue(
            "语音被转成文字了：$hits。`Entry.audioPath` 的注释明写「不转文字」——" +
                "转写会把这本日记变成文本待办，那是另一个产品",
            hits.isEmpty(),
        )
    }

    @Test
    fun the_network_path_never_touches_audio() {
        val networkFiles = sourceFiles().filter { file -> file.readText().contains("setRequestProperty") }
        assertTrue(
            "找不到任何出网的代码——这条测试的前提变了，去看 `CloudVisionEngine` 是否改名或删掉",
            networkFiles.isNotEmpty(),
        )
        val leaks = networkFiles.flatMap { file ->
            file.readLines().mapIndexedNotNull { index, line ->
                if (AUDIO_REFERENCE.containsMatchIn(line)) "${relative(file)}:${index + 1}" else null
            }
        }
        assertTrue(
            "唯一出网的那条路径里出现了音频引用：$leaks。照片发给用户自己的密钥是设置页里写明的取舍，" +
                "而录下来的声音没有被同意过要离开这台设备",
            leaks.isEmpty(),
        )
    }

    @Test
    fun recording_does_not_need_a_background_service() {
        val files = sourceFiles()
        val offenders = files.flatMap { file ->
            file.readLines().mapIndexedNotNull { index, line ->
                // containsMatchIn 而不是 matches：后者要求整行匹配，那样这一条永远扫不出东西，
                // 而「没扫到」与「没有违规」在报告里长得一模一样。
                if (BACKGROUND.containsMatchIn(line)) "${relative(file)}:${index + 1}" else null
            }
        }
        assertTrue(
            "出现了常驻的录音服务：$offenders。录音是用户在某一页上主动按的，" +
                "不该有后台替他决定什么时候在录",
            offenders.isEmpty(),
        )
    }

    /** 每次都数一下扫到了多少文件：解析器一坏，空集会伪装成「一切正常」。 */
    private fun sourceFiles(): List<File> {
        val files = sources.walk().filter { it.isFile && it.name.endsWith(".kt") }.toList()
        assertTrue(
            "只扫到 ${files.size} 个 .kt——扫描根目录大概是错了（当前是 ${sources.absolutePath}）",
            files.size >= 40,
        )
        return files
    }

    private fun relative(file: File): String =
        module.toPath().relativize(file.toPath()).toString().replace('\\', '/')

    private fun findModuleDir(): File {
        var cursor: File? = File("").absoluteFile
        while (cursor != null) {
            if (File(cursor, "src/main/AndroidManifest.xml").isFile) return cursor
            cursor = cursor.parentFile
        }
        throw IllegalStateException("找不到模块目录；工作目录是 ${File("").absolutePath}")
    }

    private companion object {
        /**
         * 只列**转写**用的 API。`android.speech.tts.*` 故意不在这里：那是把词念给用户听（输出），
         * 与「把话变成文字」是相反的一条路。
         */
        val TRANSCRIBING = listOf(
            "SpeechRecognizer",
            "RecognizerIntent",
            "RecognitionListener",
            "VoiceInteractionService",
            "SpeechTranslationOptions",
            "TranscriptionSession",
        )

        val AUDIO_REFERENCE = Regex("""(?i)audio|录音|voice""")

        /** 前台服务与常驻 worker。`WorkManager` 只给每日提醒用，那条不碰麦克风。 */
        val BACKGROUND = Regex("""(?i)\bclass\s+\w*(Mic|Record\w*)Service\b|startForeground\(""")
    }
}
