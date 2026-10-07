// Copyright (c) 2026 ilyskyo
// SPDX-License-Identifier: MIT

package com.ilyskyo.capturney

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.ilyskyo.capturney.data.model.WordCard
import com.ilyskyo.capturney.ui.theme.Space
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ExternalResource
import org.junit.rules.RuleChain
import org.junit.rules.TestRule
import org.junit.runner.RunWith

/**
 * 「记住」页顶上的进度读数：`N/M` 必须**连成一体**，而 M 必须**数得进队列里每一张卡**。
 *
 * ## 为什么要跑在设备上
 *
 * 这一行原本是三个平铺的兄弟放在一个 `spacedBy(Space.xs)` 的 Row 里，于是渲染成「0 /10」——
 * 斜杠左边有缝、右边没有。四种语言都这样，而它**只有在真实渲染里才看得见**：
 * `done`/`total` 怎么算，JVM 侧早就测过了；布局那边什么都没测。
 *
 * 所以这里量的不是「字符串是不是 0/10」（那是拼字符串，JVM 就能验），
 * 而是**两个文本节点在屏幕上的间距**——那正是当初坏掉的东西。
 *
 * ## 为什么 M 用「加了三张卡」来验
 *
 * 直接断言「/3」会把测试绑死在这台设备原本有几张卡：干净的模拟器上过，别人有数据的真机上就红，
 * 而那次红与被测对象毫无关系。改成**读两次、只看增量**，既与设备无关，
 * 又确实在问「分母有没有跟着队列走」。
 */
@RunWith(AndroidJUnit4::class)
class RememberProgressTest {

    private val compose = createAndroidComposeRule<MainActivity>()

    @get:Rule
    val rules: TestRule = RuleChain.outerRule(PreserveAppFiles())
        .around(DeleteFixtureCards())
        .around(compose)

    /**
     * 夹具只负责**收尾**：种卡必须发生在测试体里（要先读一次没有夹具时的分母），
     * `after()` 无论成败把那三张抹掉。
     *
     * 必须自己抹：`PreserveAppFiles` 只还原磁盘，而同一个进程会连着跑完多个方法，
     * 内存里那份牌组文档态还原不了——留给下一个方法就是「找到 2 个节点」。
     */
    private class DeleteFixtureCards : ExternalResource() {
        override fun after() {
            val deck = app().container.deck
            runBlocking { FIXTURE_CARDS.forEach { deck.delete(it.id) } }
        }
    }

    @Test
    fun numberAndDenominatorSitTightTogether() {
        openRememberTab()
        val denominator = onlyDenominator()
        val right = denominator.boundsInRoot

        // 分子取「分母左边、同一行、最靠右」的那一颗纯数字。页面上别处的数字
        // （连续天数、被滤掉的张数）都不与分母同行相邻，这个选法挑不错。
        val numerator = nodesMatching(PURE_NUMBER)
            .map { it.boundsInRoot }
            .filter { it.right <= right.left + tolerancePx() && verticallyOverlapping(it, right) }
            .maxByOrNull { it.right }
        assertTrue(
            "分母左边没有同行、相邻的纯数字节点——这一行只剩「/${denominatorValue(denominator)}」自己",
            numerator != null,
        )

        val gapDp = (right.left - numerator!!.right) / density()
        assertTrue(
            "数字与分母之间空了 ${"%.1f".format(gapDp)}dp，超过上限 $MAX_GAP_DP" +
                "dp（设计给整行用的间距是 ${Space.xs.value}dp）。斜杠左边带缝、右边不带，" +
                "渲染出来就是「0 /10」那个样子",
            gapDp < MAX_GAP_DP,
        )
    }

    @Test
    fun denominatorCountsEveryCardInTheQueue() {
        openRememberTab()
        val before = denominatorValue(onlyDenominator())

        val deck = app().container.deck
        runBlocking {
            // 词头是目标语，释义给齐四种可能的母语：哪一侧都不会「两面同字」——
            // 塌成同字的卡会被队列滤掉，那样增量就不是 3 了。
            FIXTURE_CARDS.forEach { assertTrue("夹具卡没进牌组：${it.id}", deck.add(it)) }
        }
        compose.waitForIdle()

        val after = denominatorValue(onlyDenominator())
        assertEquals(
            "往队列里加了 ${FIXTURE_CARDS.size} 张卡，分母却从 $before 变成了 $after——" +
                "它没有数进队列里的每一张",
            before + FIXTURE_CARDS.size,
            after,
        )
    }

    private fun onlyDenominator(): SemanticsNode {
        val found = nodesMatching(DENOMINATOR)
        assertTrue(
            "屏幕上形如「/数字」的节点有 ${found.size} 个，应当恰好 1 个" +
                "（0 个说明进度那一行没渲染，多于 1 个说明有别的读数撞了格式）",
            found.size == 1,
        )
        return found.single()
    }

    private fun nodesMatching(matcher: SemanticsMatcher) = compose.onAllNodes(matcher).fetchSemanticsNodes()

    /** 分母那一段形如 `/12`；这里要的是它的值，所以去掉斜杠。 */
    private fun denominatorValue(node: SemanticsNode): Int =
        textsOf(node).first { DENOMINATOR_REGEX.matches(it) }.drop(1).toInt()

    /** 点页签进「记住」。胶囊合并过语义，按文字找会落空，所以按 contentDescription 找。 */
    private fun openRememberTab() {
        compose.waitForIdle()
        compose.onNodeWithContentDescription(compose.activity.getString(R.string.home_tab_remember))
            .performClick()
        compose.waitForIdle()
    }

    private fun verticallyOverlapping(a: Rect, b: Rect) = a.top < b.bottom && b.top < a.bottom

    private fun density() = compose.activity.resources.displayMetrics.density

    /** 允许贴着脸：一个像素的容差，够挡住边界取整的误差，挡不住任何一段真实的间距。 */
    private fun tolerancePx() = 1f * density()

    private companion object {
        /** 修好时实测缝隙是 0，坏掉时是整段 `Space.xs`（4dp）；取一半作分界。 */
        const val MAX_GAP_DP = 2f

        fun app() = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext
            as CapturneyApplication

        val DENOMINATOR_REGEX = Regex("^/\\d+$")
        val PURE_NUMBER_REGEX = Regex("^\\d+$")

        fun textsOf(node: SemanticsNode): List<String> =
            node.config.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text }

        val DENOMINATOR = SemanticsMatcher("文本形如「/数字」") { node ->
            textsOf(node).any { DENOMINATOR_REGEX.matches(it) }
        }

        val PURE_NUMBER = SemanticsMatcher("文本是纯数字") { node ->
            textsOf(node).any { PURE_NUMBER_REGEX.matches(it) }
        }

        val FIXTURE_CARDS = listOf(
            fixture("fixture-progress-cup", "cup", "杯子", "カップ", "컵"),
            fixture("fixture-progress-mug", "mug", "马克杯", "マグカップ", "머그컵"),
            fixture("fixture-progress-bowl", "bowl", "碗", "ボウル", "그릇"),
        )

        fun fixture(id: String, headword: String, zh: String, ja: String, ko: String) = WordCard(
            id = id,
            headword = headword,
            language = "en",
            glosses = mapOf(
                "zh" to zh,
                "ja" to ja,
                "ko" to ko,
                // 母语也可能被设成英语。给一条不成句的释义，免得词头与释义撞成同字而被队列滤掉。
                "en" to "a $headword of some sort",
            ),
        )
    }
}
