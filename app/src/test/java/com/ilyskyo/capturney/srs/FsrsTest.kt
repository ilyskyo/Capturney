// Copyright (c) 2026 ilyskyo
// SPDX-License-Identifier: MIT

package com.ilyskyo.capturney.srs

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * Guards the FSRS-6 port against the two classes of bug that are invisible in normal use:
 * a wrong sign in `FACTOR`/`DECAY`, and a dropped term in a rarely-taken branch.
 *
 * The assertions here are *definitional invariants* rather than golden vectors transcribed
 * from another runtime, so they stay meaningful even if the upstream implementation changes:
 * the whole design of FSRS rests on `R(S,S) = 0.9` and `I(0.9,S) = S`.
 */
class FsrsTest {

    private val day = 24L * 60 * 60 * 1000

    /**
     * Anchored to *local* noon so that `t0 - 2h` is guaranteed to stay on the same calendar
     * day in whatever timezone the build machine is in. The engine's same-day branch compares
     * `LocalDate`s, so a UTC-based constant would make these tests fail east of UTC+11.
     */
    private val t0: Long = java.time.LocalDate.now()
        .atTime(12, 0)
        .atZone(java.time.ZoneId.systemDefault())
        .toInstant()
        .toEpochMilli()

    @Test
    fun `default weights match the published FSRS-6 vector`() {
        assertEquals(21, Fsrs.DEFAULT_PARAMS.size)
        assertEquals(
            listOf(
                0.212, 1.2931, 2.3065, 8.2956, 6.4133, 0.8334, 3.0194, 0.001,
                1.8722, 0.1666, 0.796, 1.4835, 0.0614, 0.2629, 1.6483, 0.6014,
                1.8729, 0.5425, 0.0912, 0.0658, 0.1542,
            ),
            Fsrs.DEFAULT_PARAMS,
        )
    }

    /**
     * The whole point of defining `FACTOR = 0.9^(1/DECAY) - 1` is that a card reviewed exactly
     * one stability-interval later sits at 90% retrievability. Getting the exponent sign wrong
     * still produces a plausible-looking curve, so pin it down.
     */
    @Test
    fun `retrievability at one stability interval is exactly ninety percent`() {
        for (s in listOf(0.5, 1.0, 3.0, 21.0, 180.0, 3650.0)) {
            val state = Fsrs.State(stability = s, lastReview = t0, reviewCount = 1)
            val r = Fsrs.retention(state, now = t0 + (s * day).toLong())
            assertEquals("S=$s", 0.9, r, 1e-9)
        }
    }

    /**
     * The mirror image: at the default retention the scheduled interval equals S.
     *
     * The trick is making stability survive the review unchanged, which it does when
     * retrievability is exactly 1 (the `e^((1-R)*w10) - 1` growth term collapses to zero).
     * `due == lastReview` also keeps us off the same-day branch, which would substitute the
     * short-term formula.
     */
    @Test
    fun `interval at ninety percent retention equals stability`() {
        for (s in listOf(1.0, 2.5, 8.0, 45.0, 300.0)) {
            val state = Fsrs.State(stability = s, due = t0, lastReview = t0, reviewCount = 1)
            // 读的是不抖动的那一路：这个恒等式说的是 FACTOR 与 DECAY 的符号，抖动是 ±5%，
            // 混进来就量不准了。取 HARD 而不是 GOOD——三档在 R=1 时算出的稳定性相同，
            // 单调钳制会把 GOOD/EASY 抬到 hard+1、good+1（Anki 在这种情况下也印 1d/2d/3d）。
            val hard = Fsrs.intervals(state, t0, fuzz = false, random = kotlin.random.Random(0))
                .getValue(Fsrs.Rating.HARD)
            assertEquals("S=$s", Math.round(s).toInt(), hard)
        }
    }

    /**
     * 按钮上那个数**就是**会被存进 `due` 的那个数。
     *
     * 这条在两次分头实现里都坏过：一次是 `previewIntervals` 绕过了单调钳制（标签 1 天、
     * 落盘 2 天），一次是抖动取自一个共享的随机源（渲染抽一个、评级抽另一个，标签与 due
     * 差 5%，而同一张卡换一次界面读数还会跳）。用 S=45 这种一定会抖的间隔来问最灵。
     */
    @Test
    fun `what the buttons show is what gets stored`() {
        val state = Fsrs.State(
            stability = 45.0,
            difficulty = 6.0,
            due = t0,
            lastReview = t0 - 45 * day,
            reviewCount = 7,
            lapses = 2,
        )
        val shown = Fsrs.previewIntervals(state, t0)
        for (rating in listOf(Fsrs.Rating.HARD, Fsrs.Rating.GOOD, Fsrs.Rating.EASY)) {
            val stored = Fsrs.review(state, rating, t0)
            assertEquals(
                "$rating 按钮写着 ${shown[rating]} 天，实际排到 ${(stored.due - t0) / day} 天",
                shown.getValue(rating) * day,
                stored.due - t0,
            )
        }
        // 同一个状态再问一次必须给同一个答案，否则「标签即承诺」只在那一次渲染成立。
        assertEquals(shown, Fsrs.previewIntervals(state, t0 + 3_000L))
    }

    /** Raising the target retention must never lengthen the interval. */
    @Test
    fun `higher retention shortens intervals`() {
        val state = Fsrs.State(stability = 30.0, lastReview = t0, reviewCount = 4)
        Fsrs.configure(Fsrs.DEFAULT_PARAMS, 0.9)
        val relaxed = Fsrs.previewIntervals(state, t0 + 30 * day)[Fsrs.Rating.GOOD]!!
        Fsrs.configure(Fsrs.DEFAULT_PARAMS, 0.97)
        val strict = Fsrs.previewIntervals(state, t0 + 30 * day)[Fsrs.Rating.GOOD]!!
        Fsrs.configure(Fsrs.DEFAULT_PARAMS, Fsrs.DEFAULT_REQUEST_RETENTION)

        assertTrue("retention 0.97 ($strict) should not exceed retention 0.90 ($relaxed)", strict <= relaxed)
    }

    /**
     * Regression for the dropped `sinc` floor.
     *
     * Same-day re-show of a card that already has strong stability: `s^-w19` can outweigh the
     * `e^(w17*(G-3+w18))` growth, so an unclamped Good would *reduce* S. The reference
     * implementation clamps `sinc` to 1 for every grade >= GOOD.
     */
    @Test
    fun `same-day good never lowers stability`() {
        // due is far in the future and lastReview is earlier today, so `evolve` takes the
        // short-term branch: same calendar day AND not yet due.
        for (s in listOf(10.0, 50.0, 200.0, 1000.0)) {
            val state = Fsrs.State(
                stability = s,
                difficulty = 5.0,
                due = t0 + 30 * day,
                lastReview = t0 - 2 * 60 * 60 * 1000, // two hours ago, same calendar day as t0
                reviewCount = 6,
            )
            val out = Fsrs.review(state, Fsrs.Rating.GOOD, now = t0)
            assertTrue("S=$s -> ${out.stability}", out.stability >= s)
        }
    }

    /** The floor must not be so aggressive that a same-day Easy stops helping entirely. */
    @Test
    fun `same-day easy still grows stability`() {
        val state = Fsrs.State(
            stability = 10.0,
            difficulty = 5.0,
            due = t0 + 30 * day,
            lastReview = t0 - 2 * 60 * 60 * 1000,
            reviewCount = 6,
        )
        val out = Fsrs.review(state, Fsrs.Rating.EASY, now = t0)
        assertTrue("${out.stability}", out.stability > 10.0)
    }

    /** Difficulty must stay inside the DSR model's domain no matter how the card is abused. */
    @Test
    fun `difficulty stays within one and ten`() {
        var state = Fsrs.State()
        val ratings = listOf(
            Fsrs.Rating.EASY, Fsrs.Rating.EASY, Fsrs.Rating.EASY, Fsrs.Rating.EASY,
            Fsrs.Rating.AGAIN, Fsrs.Rating.AGAIN, Fsrs.Rating.AGAIN,
            Fsrs.Rating.GOOD, Fsrs.Rating.HARD,
        )
        var now = t0
        for (r in ratings) {
            // Advance a day each time so we leave the same-day branch.
            now += day
            state = Fsrs.review(state, r, now)
            assertTrue("D=${state.difficulty}", state.difficulty in 1.0..10.0)
            assertTrue("S=${state.stability}", state.stability > 0.0)
        }
    }

    /** Intervals must be strictly ordered by grade, otherwise the four buttons are a lie. */
    @Test
    fun `previewed intervals are ordered again then hard then good then easy`() {
        val state = Fsrs.State(
            stability = 25.0,
            difficulty = 5.0,
            due = t0 + 25 * day,
            lastReview = t0,
            reviewCount = 5,
        )
        val p = Fsrs.previewIntervals(state, t0 + 25 * day)
        val again = p[Fsrs.Rating.AGAIN]!!
        val hard = p[Fsrs.Rating.HARD]!!
        val good = p[Fsrs.Rating.GOOD]!!
        val easy = p[Fsrs.Rating.EASY]!!
        assertTrue("$again < $hard < $good < $easy", again < hard && hard < good && good < easy)
    }

    /**
     * 按「忘了」必须留在**这一轮**里。
     *
     * 参考实现把这件事说得很清楚：FSRS 只管长期调度，(re)learning steps 由牌组选项负责
     * （fsrs4anki_scheduler.js:17-18，并建议步长短于一天）。我们没有 deck options 那一层，
     * 而 `intervalForStability` 的下限是 1 天，于是这一半行为是**整块缺掉的**：
     * 用户说「没想起来」，得到的却是「明天再来」，一次当场重答的机会都没有。
     *
     * 界面那头其实早就备好了：`IntervalFormat` 有 `days <= 0 -> Now` 一档、四语都有文案、
     * `IntervalFormatTest` 也断言过——只是调度器永远发不出那个 0，所以那条分支一直是死代码。
     * 这里把两头一起钉住：预览给 0（按钮因此写「现在」），落盘的到期日是重学步长。
     */
    @Test
    fun `again keeps the card in this round and previews as now`() {
        val state = Fsrs.State(
            stability = 25.0,
            difficulty = 5.0,
            due = t0,
            lastReview = t0 - 25 * day,
            reviewCount = 5,
        )
        val again = Fsrs.previewIntervals(state, t0).getValue(Fsrs.Rating.AGAIN)
        val out = Fsrs.review(state, Fsrs.Rating.AGAIN, t0)
        assertEquals("按钮上那句「现在」要求预览发 0 天", 0, again)
        assertEquals("到期日必须与预览同源，否则界面和调度会分头改", t0 + Fsrs.relearnDelayMs(), out.due)
        assertTrue("重学步长必须短于一整天，否则它不叫「留在这一轮」", Fsrs.relearnDelayMs() < day)
        // 缺掉的只是「什么时候再来一次」，不是「这次算忘了」：FSRS 的遗忘更新照常发生。
        assertTrue("S=${out.stability}", out.stability < state.stability)
        assertEquals(1, out.lapses)
        // 其余三档仍按天排，且都排在重学步长之后。
        val others = Fsrs.previewIntervals(state, t0).filterKeys { it != Fsrs.Rating.AGAIN }
        others.forEach { (rating, days) -> assertTrue("$rating=$days", days >= 1) }
    }

    /** 新卡按「忘了」同样要当场回来：介绍阶段第二次暴露比一天之后有用得多。 */
    @Test
    fun `a new card graded again also comes back within the round`() {
        val out = Fsrs.review(Fsrs.State(), Fsrs.Rating.AGAIN, t0)
        assertEquals(t0 + Fsrs.relearnDelayMs(), out.due)
        assertEquals(1, out.reviewCount)
    }

    /** A lapse must not leave the card more stable than it was. */
    @Test
    fun `lapsing reduces stability and counts a lapse`() {
        val state = Fsrs.State(
            stability = 100.0,
            difficulty = 5.0,
            due = t0,
            lastReview = t0 - 100 * day,
            reviewCount = 5,
            lapses = 1,
        )
        val out = Fsrs.review(state, Fsrs.Rating.AGAIN, t0)
        assertTrue("${out.stability}", out.stability < state.stability)
        assertEquals(2, out.lapses)
        assertEquals(6, out.reviewCount)
    }

    /** Difficulty must rise when the learner says Again and fall when they say Easy. */
    @Test
    fun `difficulty moves in the expected direction`() {
        val base = Fsrs.State(
            stability = 20.0,
            difficulty = 5.0,
            due = t0,
            lastReview = t0 - 20 * day,
            reviewCount = 5,
        )
        val again = Fsrs.review(base, Fsrs.Rating.AGAIN, t0)
        val easy = Fsrs.review(base, Fsrs.Rating.EASY, t0)
        assertTrue("AGAIN D=${again.difficulty}", again.difficulty > base.difficulty)
        assertTrue("EASY D=${easy.difficulty}", easy.difficulty < base.difficulty)
    }

    /** New cards are due immediately and report no retention (nothing learned yet). */
    @Test
    fun `a fresh state is new, never due and has zero retention`() {
        val s = Fsrs.State()
        assertTrue(s.reviewCount == 0)
        assertEquals(Int.MAX_VALUE, Fsrs.daysUntilDue(s, t0))
        assertEquals(0.0, Fsrs.retention(s, t0), 1e-12)
        assertTrue("新卡就是现在要做的东西", Fsrs.isDue(s, t0))
    }

    /**
     * 「现在有没有得做」的边界正好是重学步长。
     *
     * 这一句被四个读数者共用：复习队列、桌面小组件、每日提醒那条通知、首页统计。
     * 分头写的时候最先露馅的是桌面上那个数——按了「忘了」的卡 `due` 在一分钟之后，
     * `now >= due` 把它读成「没得做」，于是读数会先掉到 0、几十秒后又跳回 1。
     */
    @Test
    fun `a card due within the relearn window still counts as due`() {
        val soon = Fsrs.State(stability = 3.0, due = t0 + 30_000L, lastReview = t0, reviewCount = 4)
        val later = soon.copy(due = t0 + 60_001L)
        assertTrue(Fsrs.isDue(soon, t0))
        assertTrue("超出重学步长就不该算进这一轮", !Fsrs.isDue(later, t0))
    }

    /** Rounding to two decimals must not accumulate visible drift across a long schedule. */
    @Test
    fun `state stays on the two decimal grid after many reviews`() {
        var state = Fsrs.State()
        var now = t0
        repeat(40) { i ->
            now += 3 * day
            state = Fsrs.review(state, Fsrs.Rating.entries[i % 4], now)
            assertEquals("iteration $i D", state.difficulty, round2(state.difficulty), 1e-9)
            assertEquals("iteration $i S", state.stability, round2(state.stability), 1e-9)
        }
    }

    /**
     * 保持率必须**单调地**改变排期：拉高保持率 → 每张卡的间隔变短。
     *
     * 这是设置页那个滑块唯一真正承诺的行为。如果哪天 `intervalForStability` 里的符号写反了，
     * 滑块会变成「越想要记住、越少复习」，而没有任何其他测试会失败。
     */
    @Test
    fun `raising target retention shortens every previewed interval`() {
        val state = learnedState()
        withRetention(Fsrs.MIN_REQUEST_RETENTION) {
            val loose = Fsrs.previewIntervals(state, t0).getValue(Fsrs.Rating.GOOD)
            withRetention(0.95) {
                val tight = Fsrs.previewIntervals(state, t0).getValue(Fsrs.Rating.GOOD)
                assertTrue("tight=$tight must be shorter than loose=$loose", tight < loose)
            }
        }
    }

    /**
     * 越界与非有限输入：夹紧而不是抛，也不能悄悄改成「排期不再响应」。
     *
     * 调用方是一个绑定 DataStore 的滑块，旧版本落盘的取值不该让排期器拒绝工作。
     */
    @Test
    fun `retention input is clamped and never rejects the schedule`() {
        withRetention(0.999) {
            assertEquals(Fsrs.MAX_REQUEST_RETENTION, Fsrs.currentRequestRetention(), 1e-9)
        }
        withRetention(0.0) {
            assertEquals(Fsrs.MIN_REQUEST_RETENTION, Fsrs.currentRequestRetention(), 1e-9)
        }
        // NaN / Infinity 直接忽略：一个坏输入不该把保持率推到区间端点去。
        withRetention(0.85) {
            Fsrs.setRequestRetention(Double.NaN)
            assertEquals(0.85, Fsrs.currentRequestRetention(), 1e-9)
            Fsrs.setRequestRetention(Double.POSITIVE_INFINITY)
            assertEquals(0.85, Fsrs.currentRequestRetention(), 1e-9)
        }
    }

    /**
     * `Fsrs` 是进程级单例，保持率是它的全局状态，所以测试必须自己还原，
     * 否则同一 JVM 里后跑的用例会继承前一个用例的保持率——那种失败看起来完全随机。
     */
    private fun withRetention(value: Double, block: () -> Unit) {
        val saved = Fsrs.currentRequestRetention()
        try {
            Fsrs.setRequestRetention(value)
            block()
        } finally {
            Fsrs.setRequestRetention(saved)
        }
    }

    /**
     * 四颗评分按钮的间隔必须从左到右越来越长。
     *
     * 参考实现算完四个 interval 后紧接着钳一次（`hard=min(hard,good)`、`good=max(good,hard+1)`、
     * `easy=max(easy,good+1)`），因为 ±5% 抖动是每个间隔各自抽的：没有这一步，按「简单」
     * 完全可能拿到比按「良好」更近的到期日。那既不会崩也不会进日志，但它违反的是这个界面
     * 唯一承诺的东西——四个按钮写着越来越长的间隔。
     *
     * 种子是遍历出来的而不是随机一次：要的是「有没有哪一种抖法会翻车」这个答案可复现。
     */
    @Test
    fun scheduledIntervalsStayMonotonicForEveryFuzzDraw() {
        val state = learnedState()
        var crossed = 0   // 供失败时统计用
        for (seed in 0 until 400) {
            val iv = Fsrs.scheduledIntervals(state, t0, kotlin.random.Random(seed))
            val hard = iv.getValue(Fsrs.Rating.HARD)
            val good = iv.getValue(Fsrs.Rating.GOOD)
            val easy = iv.getValue(Fsrs.Rating.EASY)
            if (hard > good || easy <= good) crossed += 1
            assertTrue(
                "seed=$seed 时按钮顺序被抖动打乱了：hard=$hard good=$good easy=$easy。" +
                    "用户点最右边那颗却拿到不比中间那颗长的间隔",
                hard <= good && easy > good,
            )
        }
        // crossed 只用于把「有多少种子会翻车」显示在失败消息里；
        // 真正的守卫是循环里那条按种子断言的 assertTrue。
    }

    /** 一张已经被复习过、有稳定性的卡：预览间隔只有在这种状态下才有意义。 */
    private fun learnedState(): Fsrs.State {
        var state = Fsrs.State()
        var now = t0
        repeat(3) {
            now += 2 * day
            state = Fsrs.review(state, Fsrs.Rating.GOOD, now)
        }
        return state
    }

    private fun round2(v: Double) = kotlin.math.round(v * 100.0) / 100.0
}
