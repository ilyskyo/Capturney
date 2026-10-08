// Copyright (c) 2026 ilyskyo
// SPDX-License-Identifier: MIT

package com.ilyskyo.capturney

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `THIRD_PARTY_NOTICES.md` 开头承诺的是：**这个项目里不是 MIT 的东西，都在这份文件里**。
 * 那是一句对用户的承诺，而它以前一行代码都没守着——所以它已经在说谎了：
 * `assets/lexicon/` 有四个数据文件，这份文件先前只写了 `en.json`（ECDICT/MIT），
 * 而 `gloss-ja.json` / `gloss-ko.json` / `concepts.json` 那 398 + 14 个值是 Wikidata 标签（CC0），
 * 一个字都没提。CC0 不要求署名，所以**漏了不会有任何人投诉**——这正是它能一直漏着的原因。
 *
 * ## 为什么是「被引到才算数」而不是「不许有陌生的组」
 *
 * 这份声明按命名空间整族写（`androidx.compose.*`、`androidx.camera.*`），而构建脚本里
 * 是一条条具体坐标。所以判据是「**完整组名** 出现在文件里；只有 `androidx.*` 可以放宽到前两级」。
 * `androidx.compose.ui` 因此由 `androidx.compose` 那一族覆盖、不会天天误报，
 * 而今天加一条 `com.squareup.okhttp3:*` 或 `io.coil-kt:*` 会当场红——那一族在文件里根本不存在，
 * 加它的人必须同时把许可写清楚。误报的守卫最后都是被忽略，这条只在真漏时报。
 *
 * ## 为什么每条都断言「我确实看到了多少」
 *
 * 这一族的坑本仓库踩过：`DependencyPinningDisciplineTest` 的解析器一度**一个条目都没解析出来**，
 * 于是「没有违规」和「什么都没看」给出完全一样的信号。所以下面每条都带一个数量下限。
 */
class ThirdPartyNoticesCoverageTest {

    private val root: File by lazy { findRepositoryRoot() }
    private val noticesText: String by lazy { File(root, "THIRD_PARTY_NOTICES.md").readText(Charsets.UTF_8) }

    /**
     * 进包的每一条运行时依赖，其所属组必须在这份文件里出现。
     *
     * 只算 `implementation`：`testImplementation` / `androidTestImplementation` /
     * `debugImplementation` 都不进 release 产物，要求给它们署名只会把这份文件变成
     * 一长串没人读的测试框架名单——而名单一长人们就不看了。
     */
    @Test
    fun every_shipped_dependency_group_is_named_in_the_notices() {
        val groupsByAlias = catalogGroups()
        assertTrue(
            "从 catalog 里只解析出 ${groupsByAlias.size} 条坐标，解析器大概是瞎了",
            groupsByAlias.size >= 20,
        )

        val shipped = File(root, "app/build.gradle.kts").readLines().mapNotNull { line ->
            val trimmed = line.trimStart()
            if (!trimmed.startsWith("implementation(")) return@mapNotNull null
            LIBS_ACCESSOR.find(trimmed)?.groupValues?.get(1)?.replace('.', '-')
        }.distinct()
        assertTrue(
            "从 build 文件里只认出 ${shipped.size} 条 implementation 依赖，量具肯定不对",
            shipped.size >= 15,
        )

        val missing = shipped.mapNotNull { alias ->
            val group = groupsByAlias[alias]
                ?: return@mapNotNull "$alias（catalog 里没这一条：build 文件与 catalog 对不上）"
            if (isCovered(group)) null else "$alias → $group"
        }
        assertTrue(
            "这些进包的依赖没出现在第三方声明里：$missing。这份文件自称覆盖所有非 MIT 内容，" +
                "漏一条就等于它在说谎",
            missing.isEmpty(),
        )
    }

    /**
     * assets 里的第三方数据文件必须逐个被提名；本仓库自己创作的必须写进下面那份名单。
     *
     * 名单是**声明**而不是推断：新增一份自带内容（比如又一张手写词表）时作者要在这里加一行，
     * 那一刻他得回答「这真是我们创作的，还是从哪儿取的？」——而那正是这件事唯一有价值的时刻。
     */
    @Test
    fun every_bundled_third_party_data_file_is_named_and_our_own_data_is_declared() {
        val assetsDir = File(root, "app/src/main/assets")
        val files = assetsDir.walkTopDown().filter { it.isFile }
            .map { it.relativeTo(assetsDir).path.replace('\\', '/') }
            .toList()
        assertTrue("assets 里只枚举到 ${files.size} 个文件，量具肯定不对", files.size >= 8)

        // 本仓库创作的：氛围词表与场景词表，都是按产品判断手写的，不含任何外部数据。
        val ownContent = setOf("scenes/scenes.json", "scenes/ambience.json")
        val thirdParty = files.filterNot { it in ownContent }
        assertTrue("第三方数据文件只剩 ${thirdParty.size} 个，枚举大概是空的", thirdParty.size >= 6)

        val missing = thirdParty.filterNot { noticesText.contains(File(it).name) }
        assertTrue(
            "这些随包分发的数据文件没在声明里被提名：$missing。它们各自的许可不跟着 MIT 走，" +
                "而「不需要署名」从来不是「不需要写」",
            missing.isEmpty(),
        )
    }

    /**
     * 唯一那一条**不是宽松许可**的依赖必须仍然被单独标出来。
     *
     * 它带着 Google Play services，装不装得到、行为会不会变都系在这一条上。哪天有人把它挪进
     * 普通那一行、把那句「不能以宽松许可再分发」删掉，这份文件就从法律文件退化成依赖清单，
     * 而 diff 里看到的只是「表格少了一行说明」。
     */
    @Test
    fun the_one_non_redistributable_licence_is_still_flagged_as_such() {
        assertTrue(
            "声明里不再有「Android Software Development Kit License」——那是本仓库唯一一条" +
                "非宽松许可的依赖（play-services-mlkit-subject-segmentation）",
            noticesText.contains("Android Software Development Kit License"),
        )
        assertTrue(
            "声明里不再有「不是宽松许可可再分发」那句判定：把这条成本写明白就是这份文件存在的意义",
            Regex("not\\*\\* redistributable\\b|not redistributable").containsMatchIn(noticesText),
        )
    }

    /** `[libraries]` 里 alias → group。本仓库的条目全部写成一行，所以按行取就够。 */
    private fun catalogGroups(): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        var section = ""
        for (line in File(root, "gradle/libs.versions.toml").readLines()) {
            if (line.startsWith("[")) {
                section = line.trim('[', ']', ' ')
                continue
            }
            if (section != "libraries") continue
            val alias = ENTRY_START.find(line)?.groupValues?.get(1) ?: continue
            val group = GROUP.find(line)?.groupValues?.get(1) ?: continue
            out[alias] = group
        }
        return out
    }

    /**
     * `androidx.*` 允许认到前两级命名空间，因为声明就是按 `androidx.compose.*`、`androidx.camera.*`
     * 这样一整族写的；**其余组必须整名出现**。
     *
     * 一律放宽到两级会让 `com.google.mediapipe` 被文件里另一句 `com.google…` 蒙混过去，
     * 而 Google 那三条正是这份文件里最需要被单独看见的许可（其中一条还不是宽松许可）。
     */
    private fun isCovered(group: String): Boolean {
        if (noticesText.contains(group)) return true
        if (!group.startsWith("androidx.")) return false
        return noticesText.contains(group.split('.').take(2).joinToString("."))
    }

    private fun findRepositoryRoot(): File {
        var cursor: File? = File("").absoluteFile
        while (cursor != null) {
            if (File(cursor, "gradle/libs.versions.toml").isFile && File(cursor, "THIRD_PARTY_NOTICES.md").isFile) {
                return cursor
            }
            cursor = cursor.parentFile
        }
        throw IllegalStateException("找不到仓库根；工作目录是 ${File("").absolutePath}")
    }

    private companion object {
        /** `[libraries]` 条目行的开头：`alias = {`。 */
        val ENTRY_START = Regex("""^\s*([A-Za-z0-9_.\-]+)\s*=\s*\{""")

        val GROUP = Regex("""group\s*=\s*"([^"]+)"""")

        /** `implementation(libs.androidx.core.ktx)` 里的那个访问器路径。 */
        val LIBS_ACCESSOR = Regex("""libs\.([A-Za-z0-9_.]+)\)""")
    }
}
