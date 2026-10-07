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
 * `res/xml/locales_config.xml` 里那个文件**自己写着**一条约定：列出的语言必须与 `res/values*`
 * 里真正有翻译的那几种一致。这条约定以前没有东西守着，而它坏掉的样子恰好是最难发现的那种：
 *
 * - 多列一个没有资源的 locale（比如有人加了 `de` 准备将来翻译）：系统在设置里**给得出**这一格，
 *   用户选了，界面回落到默认包——语言没变，而他以为自己选的没生效，或者以为 App 坏了。
 *   比压根不给这一格更糟。
 * - 少列一个已经翻译好的：Android 13+ 的「按应用设置语言」不会显示它，一个学日语的人
 *   要先把整台手机换成日语才能用这款学日语的 App，而翻译就躺在仓库里。
 *
 * 所以两边都断言，用集合相等而不是「至少包含」：一条双向的相等能同时逮住多与少，
 * 而「列出的都在资源里」那种单向写法只能逮住一半。
 *
 * 顺带守住接线本身：清单必须真的指着这个文件，而且**不带 `.xml` 后缀**——
 * 写成 `@xml/locales_config.xml` 会让 AAPT 直接报错，这条不是理论风险。
 */
class LocaleConfigMatchesResourcesTest {

    private val module: File by lazy { findModuleDir() }
    private val res: File get() = File(module, "src/main/res")

    @Test
    fun `the declared locales are exactly the languages that actually have strings`() {
        val declared = declaredLocales()
        val translated = translatedLanguages()

        assertEquals(
            "locales_config 与真正有翻译的语言不一致。多出来的那一格会被系统接受、" +
                "却回落到默认包（用户选了没反应）；少掉的那一格是让已经翻好的语言没法被选中",
            translated,
            declared,
        )
        assertTrue(
            "至少要有一种非默认语言可选，否则这份 localeConfig 没有存在的理由：$declared",
            declared.size >= 2,
        )
    }

    @Test
    fun `the default resource bundle is english so an uncovered device language lands there`() {
        // 未覆盖语种的回落**只能靠默认包是英文**来保证（系统语言换成 fr 之后截图那次没能证实，
        // 因为 per-app locale 的解析还受系统列表影响）。这里守的是它能守的那一半。
        //
        // 逐行 grep 会得到一条假红：这个仓库的注释按规矩写中文，而 XML 注释不是资源值。
        // 所以解析成 DOM，只看 `<string>` / `<item>` 里的文字。
        val document = DocumentBuilderFactory.newInstance().newDocumentBuilder()
            .parse(File(res, "values/strings.xml"))
        val values = mutableListOf<String>()
        document.getElementsByTagName("string").let { nodes ->
            for (index in 0 until nodes.length) values += (nodes.item(index) as Element).textContent
        }
        document.getElementsByTagName("item").let { nodes ->
            for (index in 0 until nodes.length) values += (nodes.item(index) as Element).textContent
        }
        val cjk = Regex("""[\u3040-\u30ff\u4e00-\u9fff\uac00-\ud7af]""")
        val offending = values.filter { cjk.containsMatchIn(it) }
        assertTrue(
            "默认包里有中日韩文字的资源值，那么一台法语设备的用户会先看到这些：$offending",
            offending.isEmpty(),
        )
    }

    @Test
    fun `the manifest wires the locale config by resource name not by file name`() {
        val manifest = File(module, "src/main/AndroidManifest.xml").readText()
        assertTrue(
            "清单里没有 android:localeConfig=\"@xml/locales_config\"——没有它，Android 13+ " +
                "的按应用语言设置整块不出现",
            manifest.contains("""android:localeConfig="@xml/locales_config""""),
        )
        assertTrue(
            "资源引用写成带 .xml 的后缀会让 AAPT 报错，这里必须是裸名",
            !manifest.contains("@xml/locales_config.xml"),
        )
        assertTrue("被指着的那个文件不存在", File(res, "xml/locales_config.xml").isFile)
    }

    private fun declaredLocales(): List<String> {
        val document = DocumentBuilderFactory.newInstance().newDocumentBuilder()
            .parse(File(res, "xml/locales_config.xml"))
        val nodes = document.getElementsByTagName("locale")
        return (0 until nodes.length)
            .map { (nodes.item(it) as Element).getAttribute("android:name") }
            .filter { it.isNotBlank() }
            .sorted()
    }

    /**
     * 「有翻译的语言」= `values-<2~3 字母>` 里带 `strings.xml` 的那些，**外加默认包算 en**。
     *
     * 默认包必须并进来：把中文搬去 `values-zh` 之后 `values-en` 被删掉了，
     * 英文从此就住在 `res/values` 里。漏掉它会让这条测试报出「locales_config 多列了 en」——
     * 而那个 en 恰恰是四种语言里唯一有全量资源的那一种。
     *
     * 要求目录里真的有 strings.xml，是为了把 `values-night` 这类**限定符复合目录**自然排除：
     * 它没有文字资源，把它当成一种语言会得到一条正确但莫名其妙的红。
     */
    private fun translatedLanguages(): List<String> = (
        res.listFiles().orEmpty()
            .filter { it.isDirectory }
            .mapNotNull { dir ->
                val tag = Regex("^values-([a-z]{2,3})$").find(dir.name)?.groupValues?.get(1)
                if (tag != null && File(dir, "strings.xml").isFile) tag else null
            } + DEFAULT_LANGUAGE
        ).sorted()

    private companion object {
        const val DEFAULT_LANGUAGE = "en"
    }

    private fun findModuleDir(): File {
        var cursor: File? = File("").absoluteFile
        while (cursor != null) {
            if (File(cursor, "src/main/res/xml/locales_config.xml").isFile) return cursor
            cursor = cursor.parentFile
        }
        throw IllegalStateException("找不到 res/xml/locales_config.xml；工作目录是 ${File("").absolutePath}")
    }
}
