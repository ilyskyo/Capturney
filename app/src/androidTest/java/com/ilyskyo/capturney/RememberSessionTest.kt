// Copyright (c) 2026 ilyskyo
// SPDX-License-Identifier: MIT

package com.ilyskyo.capturney

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.isEnabled
import androidx.compose.ui.test.isNotEnabled
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.ilyskyo.capturney.data.model.WordCard
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ExternalResource
import org.junit.rules.RuleChain
import org.junit.rules.TestRule
import org.junit.runner.RunWith

/**
 * 复习这一圈必须**在真机上完整走一遍**：翻开 → 四颗评级键亮起来 → 评一次 → 读数前进 → 下一张。
 *
 * ## 为什么 JVM 侧那些测试盖不住这里
 *
 * 「点卡片打不开背面」「翻过之后正面还在说点开看答案」「没翻开就能评级」——这几条修的时候
 * 都是靠渲染出来才发现的，而它们的共同点是判定对象不是数据，是**此刻这棵树上有没有一个
 * 能按的东西**。`revealed` 在 JVM 里算得再对，也说不清那个 `enabled` 有没有真的传到底。
 *
 * ## 为什么夹具要用「看得见词」的那几张卡
 *
 * 页签与胶囊合并过语义，按文字找会落空；卡片不一样，它的词就在被合并的那一节里，
 * 于是「可点 + 写着 cup」正好是这张卡唯一的身份。方向（认词/回忆）由设备设置决定，
 * 正面可能是词头也可能是释义，两种都算命中，这条就不跟着设备的语言与方向漂。
 *
 * ## 评完一次要看的不是「少了」而是「没多」
 *
 * 分母是 `已答 + 队列里剩下的`：评一张之后分子 0→1，分母**必须还是同一个数**。
 * 它变小说明这张被抹掉了而没被记成已答（进度条从此永远走不满），
 * 它变大说明同一张被重复入队——这两个症状都是这一圈里最贵的一类错。
 */
@RunWith(AndroidJUnit4::class)
class RememberSessionTest {

    private val compose = createAndroidComposeRule<MainActivity>()

    @get:Rule
    val rules: TestRule = RuleChain.outerRule(PreserveAppFiles())
        .around(DeleteFixtureCards())
        .around(compose)

    /** 与 `RememberProgressTest` 同一套收尾：内存里的牌组文档态不会被磁盘还原救到。 */
    private class DeleteFixtureCards : ExternalResource() {
        override fun after() {
            val deck = app().container.deck
            runBlocking { FIXTURE_IDS.forEach { deck.delete(it) } }
        }
    }

    @Test
    fun theRatingButtonsStayLockedUntilTheCardIsRevealed() {
        val shown = seedQueueAndOpenRemember()

        assertEquals(
            "还没翻开就能评级：四颗评级键里此刻可点的有 %d 颗".format(enabledRatingCount()),
            0,
            enabledRatingCount(),
        )
        assertEquals(
            "还没翻开时四颗评级键都该明确带着「禁用」语义（按不动与不存在是两件事，" +
                "读屏用户要知道的是前者）",
            RATINGS.size,
            lockedRatingCount(),
        )
        assertEquals(
            "屏幕上的提示语应当还是未翻开那一句（[R.string.review_flip_hint]）",
            str(R.string.review_flip_hint),
            visibleHint(),
        )

        compose.onNode(cardWith(shown)).performClick()
        compose.waitForIdle()

        assertEquals(
            "翻过之后四颗评级键应当全部可点",
            RATINGS.size,
            enabledRatingCount(),
        )
        assertEquals(
            "翻过之后不该还有处于「禁用」语义的评级键，此刻锁着的有 %d 颗".format(lockedRatingCount()),
            0,
            lockedRatingCount(),
        )
        assertEquals(
            "翻过之后提示语应当换成已翻开那一句（[R.string.review_flip_hint_returned]）——" +
                "还留着未翻开那一句就是在指一件已经做完的事",
            str(R.string.review_flip_hint_returned),
            visibleHint(),
        )
    }

    @Test
    fun gradingAdvancesTheReadoutWithoutMovingTheDenominator() {
        val shown = seedQueueAndOpenRemember()
        val total = denominator()
        assertEquals("夹具应该有 $TOTAL 张进队列", TOTAL, total)
        assertEquals("评之前分子应当是 0", 0, numerator())

        compose.onNode(cardWith(shown)).performClick()
        compose.waitForIdle()
        // 翻开之后、评级之前：提示语必须已经改口，否则它还在指一件做完的事（#41 那一类）。
        assertEquals(
            "翻开之后、评级之前的提示语",
            str(R.string.review_flip_hint_returned),
            visibleHint(),
        )
        compose.onNode(ratingEnabled(R.string.review_good)).performClick()
        compose.waitForIdle()

        assertEquals("评一张之后分子应当是 1", 1, numerator())
        val after = denominator()
        assertEquals(
            "评一张之后分母从 $total 变成了 $after——这张要么被抹掉了，要么被重复入队了",
            total,
            after,
        )

        val next = visibleCardTerm()
        assertNotEquals(
            "评完还是同一张卡（$next）——队列没有往前走",
            shown,
            next,
        )
        assertTrue(
            "下一张的正反面里没有一条是夹具里的词（读到的是 $next）",
            next != null && next in FIXTURE_TERMS,
        )
        assertEquals(
            "下一张的提示语应当回到未翻开那一句——它还没被翻开",
            str(R.string.review_flip_hint),
            visibleHint(),
        )
        assertEquals(
            "下一张应当回到没翻开的样子：此刻可点的评级键有 %d 颗".format(enabledRatingCount()),
            0,
            enabledRatingCount(),
        )
    }

    /** 种三张卡、进「记住」页，回「此刻那张卡上显示的那个词」。 */
    private fun seedQueueAndOpenRemember(): String {
        val deck = app().container.deck
        runBlocking {
            FIXTURE_CARDS.forEach { assertTrue("夹具卡没进牌组：${it.id}", deck.add(it)) }
        }
        openRememberTab()
        return visibleCardTerm() ?: error("屏幕上没有一张写着夹具词的卡")
    }

    /** 屏幕上那张卡：可点，而且正反面之一带着夹具里的某个词。 */
    private fun cardWith(term: String) = SemanticsMatcher("文本是「$term」") { node ->
        node.texts().contains(term)
    } and hasClickAction()

    private fun ratingEnabled(res: Int) = term(str(res)) and isEnabled() and hasClickAction()

    private fun ratingLocked(res: Int) = term(str(res)) and isNotEnabled()

    /** 此刻「亮着、按得动」的评级键数量。 */
    private fun enabledRatingCount() = RATINGS.sumOf { nodes(ratingEnabled(it)).size }

    /** 此刻明确带着 disabled 语义的评级键数量。两条一起看才分得清「按不动」与「不存在」。 */
    private fun lockedRatingCount() = RATINGS.sumOf { nodes(ratingLocked(it)).size }

    private fun visibleCardTerm(): String? =
        nodes(termInFixtures() and hasClickAction())
            .flatMap { it.texts() }
            .firstOrNull { it in FIXTURE_TERMS }

    private fun visibleHint(): String? =
        nodes(SemanticsMatcher("提示语") { node -> node.texts().any { it in HINTS } })
            .firstOrNull()?.texts()?.firstOrNull { it in HINTS }

    private fun term(value: String) = SemanticsMatcher("文本是「$value」") { node -> node.texts().contains(value) }

    private fun termInFixtures() = SemanticsMatcher("文本是夹具里的某个词") { node ->
        node.texts().any { it in FIXTURE_TERMS }
    }

    // ── 进度读数：分子与分母 ──────────────────────────────────────────────
    //
    // 分母按「/数字」找，应当唯一；分子不靠数值找（评完是 1，而页面上别处也可能有 1），
    // 靠位置找：分母左边、同一行、最靠右的那一颗纯数字。

    private fun denominator(): Int = denominatorNode().let { node ->
        node.texts().first { it.matches(DENOMINATOR_REGEX) }.drop(1).toInt()
    }

    private fun denominatorNode(): SemanticsNode {
        val found = nodes(SemanticsMatcher("文本形如「/数字」") { node ->
            node.texts().any { it.matches(DENOMINATOR_REGEX) }
        })
        assertTrue("屏幕上形如「/数字」的节点有 ${found.size} 个，应当恰好 1 个", found.size == 1)
        return found.single()
    }

    private fun numerator(): Int {
        val divisor = denominatorNode().boundsInRoot
        val candidate = nodes(SemanticsMatcher("文本是纯数字") { node -> node.texts().any { it.matches(PURE_NUMBER_REGEX) } })
            .map { it to it.boundsInRoot }
            .filter { (_, bounds) -> bounds.right <= divisor.left + 1f * density() && overlaps(bounds, divisor) }
            .maxByOrNull { (_, bounds) -> bounds.right }
        assertTrue("分母左边没有同行、相邻的纯数字节点", candidate != null)
        val (node, _) = candidate!!
        return node.texts().first { it.matches(PURE_NUMBER_REGEX) }.toInt()
    }

    private fun overlaps(a: Rect, b: Rect) = a.top < b.bottom && b.top < a.bottom

    private fun density() = compose.activity.resources.displayMetrics.density

    private fun openRememberTab() {
        compose.waitForIdle()
        compose.onNodeWithContentDescription(str(R.string.home_tab_remember)).performClick()
        compose.waitForIdle()
    }

    private fun nodes(matcher: SemanticsMatcher) = compose.onAllNodes(matcher).fetchSemanticsNodes()

    private fun str(res: Int) = compose.activity.getString(res)

    private fun SemanticsNode.texts(): List<String> =
        config.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text }

    private companion object {
        val DENOMINATOR_REGEX = Regex("^/\\d+$")
        val PURE_NUMBER_REGEX = Regex("^\\d+$")

        const val TOTAL = 3

        val RATINGS = listOf(R.string.review_again, R.string.review_hard, R.string.review_good, R.string.review_easy)

        val HINTS = listOf(
            app().getString(R.string.review_flip_hint),
            app().getString(R.string.review_flip_hint_returned),
        )

        fun app() = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext
            as CapturneyApplication

        val FIXTURE_CARDS = listOf(
            fixture("fixture-session-cup", "cup", "杯子", "カップ", "컵"),
            fixture("fixture-session-mug", "mug", "马克杯", "マグカップ", "머그컵"),
            fixture("fixture-session-bowl", "bowl", "碗", "ボウル", "그릇"),
        )

        val FIXTURE_IDS = FIXTURE_CARDS.map { it.id }

        /** 正反面都可能显示的那些词：认词方向是词头，回忆方向是释义。 */
        val FIXTURE_TERMS = FIXTURE_CARDS.flatMap { listOf(it.headword) + it.glosses.values }.toSet()

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
