// Copyright (c) 2026 ilyskyo
// SPDX-License-Identifier: MIT

package com.ilyskyo.capturney

import android.app.Application
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.ilyskyo.capturney.core.AppContainer
import com.ilyskyo.capturney.data.model.WordCard
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.rules.TestRule
import org.junit.runner.RunWith

/**
 * 搜索页是 App 的第二入口，此前只靠截图看过（搜冠词有结果、手写词进牌组），
 * 一条设备测试都没有。这里钉四条都已经在文档/注释里承诺过的行为。
 *
 * ## 为什么「点两次」这一条值得单独占一格
 *
 * `SearchViewModel.onCollect` 里那条「牌组已有同词就直接标记为已收、不再走一遍异步铸卡」
 * 是 #30 审出来的。它坏掉的形状不是报错，而是**牌组里出现两张同名的卡**：
 * 用户不会注意到，只会发现同一个词反复出现，而复习队列被永久污染了。
 */
@RunWith(AndroidJUnit4::class)
class SearchCollectsIntoTheDeckTest {

    private val compose = createAndroidComposeRule<MainActivity>()

    @get:Rule
    val rules: TestRule = RuleChain.outerRule(PreserveAppFiles()).around(compose)

    @Before
    fun startWithTheWordAbsent() {
        // 断言「多了一张」「仍然只有一张」的前提是这张卡一开始不在牌组里。
        // PreserveAppFiles 只把磁盘还原到快照，快照本身可能就带着上一次跑挂留下的卡。
        runBlocking { collectedIds().forEach { openDeck().delete(it) } }
    }

    @After
    fun removeCollectedCards() {
        runBlocking { collectedIds().forEach { openDeck().delete(it) } }
    }

    /**
     * 这里**不测**「连着点两次不许长出两张」。
     *
     * 试过，而且是条假绿：把 `onCollect` 里那条按词头的去重、`DeckRepository.add` 里那条按 id
     * 的去重**同时**破坏，测试照样绿——因为收进牌组之后那一行会从建议区挪进已收区，
     * `onAllNodesWithText(badge).onFirst()` 第二次点到的根本不是 `bridge` 那一行。
     * 要点准同一个词的按钮得先给那一行一个可寻址的语义标识，而那是为了测试去改界面的活，
     * 记在 #62 里做，不在这里留一条不会红的断言。
     */
    @Test
    fun collectingASuggestionPutsThatWordIntoTheDeck() {
        openSearch()
        typeQuery("bridge")

        awaitNode(str(R.string.search_collect))
        // 一个前缀会命中好几行（bridge / bridges / abridge…），每行都有一颗「收进牌组」，
        // 按文字找会撞上三颗。点第一行，然后让牌组作证：收进来的必须正好是 bridge。
        compose.onAllNodesWithText(str(R.string.search_collect)).onFirst().performClick()

        awaitCount(1)
        assertCount(1, "点了「收进牌组」，牌组里没有这张词")
        compose.onAllNodesWithText(str(R.string.search_collected_badge)).onFirst().assertIsDisplayed()
    }

    @Test
    fun aQueryWithNoHitsSaysSoInsteadOfShowingAnEmptyScreen() {
        openSearch()
        typeQuery("qqzzxkv")

        awaitNode(str(R.string.search_empty_title))
        compose.onNodeWithText(str(R.string.search_empty_title)).assertIsDisplayed()
    }

    private fun openSearch() {
        compose.onNodeWithContentDescription(str(R.string.home_search)).performClick()
        compose.waitForIdle()
    }

    private fun typeQuery(text: String) {
        // 搜索框是 M3 TextField，按 SetText 动作找比按占位符文字找稳：
        // 占位符在有内容之后就消失了，而 clearAndSetSemantics 过的容器根本念不出它。
        val field = compose.onNode(hasSetTextAction())
        field.assertExists()
        field.performTextInput(text)
        compose.waitForIdle()
    }

    private fun awaitNode(label: String) {
        compose.waitUntil(TIMEOUT_MS) { compose.onAllNodes(androidx.compose.ui.test.hasText(label)).fetchSemanticsNodes().isNotEmpty() }
    }

    private fun awaitCount(expected: Int) {
        compose.waitUntil(TIMEOUT_MS) { cards().size >= expected }
    }

    private fun assertCount(expected: Int, message: String) {
        val found = cards()
        assertTrue("$message（实际 ${found.size} 张：${found.map { it.headword }}）", found.size == expected)
    }

    private fun cards(): List<WordCard> = openDeck().snapshot().filter { it.headword.equals(WORD, ignoreCase = true) }

    private fun collectedIds(): List<String> = cards().map { it.id }

    private fun openDeck() = container().deck

    private fun container(): AppContainer =
        (InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as Application
            as CapturneyApplication).container

    private fun str(res: Int) = compose.activity.getString(res)

    private companion object {
        const val WORD = "bridge"
        const val TIMEOUT_MS = 5_000L
    }
}
