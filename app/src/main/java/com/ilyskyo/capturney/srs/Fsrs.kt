// Copyright (c) 2026 ilyskyo
// SPDX-License-Identifier: MIT

package com.ilyskyo.capturney.srs

import kotlinx.serialization.Serializable
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.random.Random

/**
 * FSRS-6 spaced repetition.
 *
 * Originally adapted from the Blancall engine (same author, MIT), then **verified line by line
 * against the upstream reference implementations**:
 *
 * - formulas and default weights: `open-spaced-repetition/awesome-fsrs/wiki/The-Algorithm`
 * - executable reference: `open-spaced-repetition/fsrs4anki` -> `fsrs4anki_scheduler.js`
 *
 * That cross-check confirmed the 21 default weights, the
 * difficulty / linear-damping / mean-reversion chain, the post-lapse ceiling
 * `min(S'_f, S/e^(w17*w18))`, and `FACTOR = 0.9^(1/DECAY) - 1` with `DECAY = -w20`. It also
 * caught **two defects the Blancall port had dropped**:
 *
 * 1. the same-day `sinc = max(sinc, 1)` floor for grades >= GOOD (see [nextShortTermStability] —
 *    without it, re-showing a strong card and answering *Good* silently *lowers* stability), and
 * 2. the two-decimal rounding the reference applies after every transition (see [round2]),
 *    without which long schedules drift by a day from Anki's FSRS4Anki.
 *
 * The invariants asserted in `FsrsTest` (`R(S,S) = 0.9`, `I(0.9,S) = S`) are definitional, so
 * they catch a sign error in `FACTOR` or `DECAY` without needing golden vectors from a
 * foreign runtime.
 *
 * ## Formulas (open-spaced-repetition/awesome-fsrs, "The Algorithm")
 * - initial stability: `S0(G) = w[G-1]`
 * - initial difficulty: `D0(G) = w4 - e^(w5*(G-1)) + 1`, clamped to [1, 10]
 * - interval: `I(r,S) = S/FACTOR * (r^(1/DECAY) - 1)`, `FACTOR = 0.9^(1/DECAY) - 1`, `DECAY = -w20`
 * - difficulty update: `dD = -w6*(G-3)`, linear damping `(10-D)/9`, mean reversion toward `D0(4)`
 * - recall stability: `S' = S*(1 + e^w8*(11-D)*S^(-w9)*(e^((1-r)*w10)-1)*hardPenalty*easyBonus)`
 * - post-lapse stability: `S' = min(w11*D^(-w12)*((S+1)^w13-1)*e^((1-r)*w14), S/e^(w17*w18))`
 * - same-day stability: `S' = S*e^(w17*(G-3+w18))*S^(-w19)`, floored at `sinc >= 1` for `G >= 3`
 *
 * ## References
 * - Ye, J., Su, J., & Cao, Y. (2022). *A Stochastic Shortest Path Algorithm for Optimizing
 *   Spaced Repetition Scheduling*. https://doi.org/10.1145/3534678.3539081
 * - https://github.com/open-spaced-repetition/awesome-fsrs/wiki/The-Algorithm
 * - https://github.com/open-spaced-repetition/fsrs4anki
 * - https://github.com/open-spaced-repetition/fsrs-rs
 */
object Fsrs {

    /** Review grades, matching the official `Rating` enum. */
    enum class Rating(val value: Int) {
        AGAIN(1), HARD(2), GOOD(3), EASY(4),
        ;

        companion object {
            fun fromValue(v: Int): Rating = entries.firstOrNull { it.value == v } ?: GOOD
        }
    }

    /**
     * Persisted per-card memory state. A card whose [reviewCount] is 0 is "new" and gets the
     * initial-stability path; the FSRS weight vector already encodes "how learnable is the
     * first exposure of a grade-1/2/3/4 word", so no extra per-deck tuning is needed.
     */
    @Serializable
    data class State(
        /** Difficulty D, 1-10. */
        val difficulty: Double = 0.0,
        /** Stability S, in days. */
        val stability: Double = 0.0,
        /** Epoch millis when the card next becomes due. */
        val due: Long = 0L,
        /** Epoch millis of the last review. */
        val lastReview: Long = 0L,
        val reviewCount: Int = 0,
        /** Number of times the card has been graded AGAIN. */
        val lapses: Int = 0,
    )

    /** FSRS-6 default weights (Anki's open-source defaults, w[0..20]). */
    val DEFAULT_PARAMS: List<Double> = listOf(
        0.212, 1.2931, 2.3065, 8.2956, 6.4133, 0.8334, 3.0194, 0.001,
        1.8722, 0.1666, 0.796, 1.4835, 0.0614, 0.2629, 1.6483, 0.6014,
        1.8729, 0.5425, 0.0912, 0.0658, 0.1542,
    )

    /** Anki's default target retention. */
    const val DEFAULT_REQUEST_RETENTION = 0.9

    /**
     * 保持率的可用区间。
     *
     * 下限 0.70：再低的话排期会拉出一两个月的间隔，复习队列看起来「清空了」，而用户其实正在忘。
     * 上限 0.97：Anki 自己也在这里收口——0.99 意味着几乎每天都要复习新牌，间隔普遍缩到 1 天，
     * 队列会在几天内堆到不可完成。滑块的两端就取这两个值。
     */
    const val MIN_REQUEST_RETENTION = 0.70
    const val MAX_REQUEST_RETENTION = 0.97

    private const val MIN_STABILITY = 0.1
    private const val MS_PER_DAY = 24L * 60 * 60 * 1000
    private const val MAX_INTERVAL_DAYS = 36500

    /** 见 [relearnDelayMs]：按「忘了」之后在同一轮里回来的那一步。 */
    private const val RELEARN_MS = 60_000L

    /**
     * 兜底的抖动来源，只在**不按天承诺**的读数上用得到（[minimumIntervalDays] / [maximumIntervalDays]
     * 走的是 `fuzz = false`，实际上一次都没抽过）。真正会落盘、会被印在按钮上的那条路取自
     * [fuzzSeed]——用这里的话，标签与 `due` 就会各抽一个数。
     */
    private val rng = Random(System.nanoTime())

    @Volatile
    private var params: List<Double> = DEFAULT_PARAMS

    @Volatile
    private var requestRetention: Double = DEFAULT_REQUEST_RETENTION

    private val decay: Double get() = -params[20]
    private val factor: Double get() = 0.9.pow(1.0 / decay) - 1

    /**
     * Swap in a custom weight vector (e.g. from a future FSRS optimizer run) and target
     * retention. Rejects malformed input rather than silently degrading the schedule.
     */
    fun configure(w: List<Double>, retention: Double) {
        if (w.size == 21 && w.all { it.isFinite() } && w[20] > 0) {
            params = w
            setRequestRetention(retention)
        }
    }

    /**
     * 只改目标保持率，保留当前权重向量。
     *
     * 这件事 [configure] 做不了：它要求同时传入 21 维权重，而我们能拿出的只有 [DEFAULT_PARAMS]。
     * 用户在设置页拖动保持率滑块并不是在重新调模型，只是想要不同的到期密度——走 configure
     * 会把未来任何优化器产出的权重向量悄悄抹掉。
     *
     * 越界输入是夹紧而不是拒绝：调用方是一个绑定 DataStore 的滑块，旧版本已经落盘的取值
     * 不该让排期从此不再响应。
     */
    fun setRequestRetention(retention: Double) {
        if (!retention.isFinite()) return
        requestRetention = retention.coerceIn(MIN_REQUEST_RETENTION, MAX_REQUEST_RETENTION)
    }

    /** 当前排期使用的目标保持率，供设置页回显。 */
    fun currentRequestRetention(): Double = requestRetention

    /** Probability the card is still recalled right now, 0..1. */
    fun retention(state: State, now: Long = System.currentTimeMillis()): Double {
        if (state.stability <= 0.0) return 0.0
        return forgettingCurve((now - state.lastReview).toDouble() / MS_PER_DAY, state.stability)
    }

    /** Negative = overdue, 0 = due today, positive = days remaining. */
    fun daysUntilDue(state: State, now: Long = System.currentTimeMillis()): Int {
        if (state.reviewCount == 0) return Int.MAX_VALUE
        return kotlin.math.ceil((state.due - now).toDouble() / MS_PER_DAY).toInt()
    }

    /**
     * 「现在有没有得做」——到期，或者落在一分钟的重学步长之内。
     *
     * 把这一句收在调度器里，是因为它有四个读数者：复习队列、桌面上的小组件、每日提醒那条通知、
     * 以及首页的统计。四处各写一遍 `now >= due` 的话，最先分叉的是那个**在桌面上的数**：
     * 按了「忘了」的卡此刻仍算在这一轮里（只是排到队尾），而 `now >= due` 把它读成「没得做」，
     * 于是小组件会先掉到 0 再在几十秒后跳回去——一个会跳的读数比一个晚几十毫秒的读数更不像真的。
     */
    fun isDue(state: State, now: Long = System.currentTimeMillis()): Boolean = isDueAt(state.due, now)

    /** 同 [isDue]，但直接问到期的毫秒数：仓库里存的是 `FsrsState`，为问这一句转成 [State] 不值。 */
    fun isDueAt(due: Long, now: Long = System.currentTimeMillis()): Boolean = due <= now + RELEARN_MS

    /**
     * The interval each grade *would* produce, without the ±5% fuzz.
     *
     * The review screen renders these directly on the four buttons ("2d", "6d", "3w"), which
     * is the single most useful thing a scheduler can tell a learner before they commit.
     */
    /**
     * The interval each grade **will** schedule — the same number the four buttons print.
     *
     * 这里必须走与 [review] 完全相同的一条路（含抖动与单调钳制），否则按钮说的是另一件事。
     * 分头写过一次：`previewIntervals` 当时绕过了 `hard <= good < easy` 那三步钳制，于是一张
     * S=1、R=1 的卡上按钮写着「1 天」，而真正落盘的 due 是 2 天后。抖动也曾经各抽一次：
     * 标签 12 天、存进去 11 天，同一张卡换一次界面还会自己跳。种子取自状态本身，
     * 见 [fuzzSeed]。
     */
    fun previewIntervals(
        state: State,
        now: Long = System.currentTimeMillis(),
    ): Map<Rating, Int> = intervals(state, now, fuzz = true, random = Random(fuzzSeed(state, now)))

    /** Apply a review and return the next state. The caller persists it. */
    fun review(state: State, rating: Rating, now: Long = System.currentTimeMillis()): State =
        review(state, rating, now, Random(fuzzSeed(state, now)))

    /**
     * [review] with the jitter source injected. Not a test-only convenience: without a seam here,
     * the monotonicity clamp below cannot be demonstrated to work, because a passing run and a
     * failing one differ only by which random fuzz offsets came up.
     */
    internal fun review(state: State, rating: Rating, now: Long, random: Random): State {
        val days = scheduledIntervals(state, now, random).getValue(rating)
        val (d, s) = evolve(state, rating, now)
        return State(
            difficulty = d,
            stability = s,
            // 「忘了」不按天排。0 天在这里读作「[RELEARN_MS] 之后在同一轮里回来」，
            // 和按钮上那句「现在」取自同一处——分头写迟早会有一边先改，界面就开始撒谎。
            due = now + if (rating == Rating.AGAIN) RELEARN_MS else days * MS_PER_DAY,
            lastReview = now,
            reviewCount = state.reviewCount + 1,
            lapses = state.lapses + if (rating == Rating.AGAIN) 1 else 0,
        )
    }

    /**
     * The interval each grade will actually be scheduled with, fuzz included and **made monotonic**.
     *
     * 参考实现（fsrs4anki_scheduler.js）在算完四个 interval 之后紧接着做：
     * `hard = min(hard, good)`、`good = max(good, hard + 1)`、`easy = max(easy, good + 1)`。
     * 这一步是必需的，因为 ±5% 抖动是各自独立抽的：不加钳制，按「简单」可能被抖得比按「良好」还短。
     * 那正是这个界面唯一的承诺——四个按钮从左到右越来越长。缺了它，用户点最右边那颗
     * 却拿到比中间那颗更近的到期日，而这既不会崩也不会进日志。
     */
    internal fun scheduledIntervals(state: State, now: Long, random: Random): Map<Rating, Int> =
        intervals(state, now, fuzz = true, random = random)

    /**
     * 「忘了」的重学步长。
     *
     * FSRS 只管长期调度；参考实现在这里写得很明白（fsrs4anki_scheduler.js:17）：
     * "(re)learning steps in deck options work as usual. I recommend setting steps shorter than 1 day."
     * 我们没有 deck options 这一层，而 `intervalForStability` 的下限是 1 天，于是「忘了」这张卡在
     * 原版里会少掉它最要紧的一半行为：**这一轮**再答一次。界面那头其实早就按这件事设计好了——
     * `IntervalFormat` 有 `days <= 0 -> Now` 一档、四语都备了文案、`IntervalFormatTest` 也断言过，
     * 只是调度器永远发不出那个 0，所以那条分支一直走不到。
     */
    fun relearnDelayMs(): Long = RELEARN_MS

    internal fun intervals(state: State, now: Long, fuzz: Boolean, random: Random): Map<Rating, Int> {
        val raw = Rating.entries
            .filter { it != Rating.AGAIN }
            .associateWith { rating ->
                intervalForStability(evolve(state, rating, now).second, fuzz = fuzz, random = random)
            }
        var hard = raw.getValue(Rating.HARD)
        var good = raw.getValue(Rating.GOOD)
        var easy = raw.getValue(Rating.EASY)
        hard = min(hard, good)
        good = max(good, hard + 1)
        easy = max(easy, good + 1)
        // AGAIN 恒为 0：它走重学步长而不是长期间隔，所以既不参与抖动，也不参与单调钳制
        // （`intervalForStability` 的下限是 1 天，钳出来的最小值总归大于 0）。
        return mapOf(Rating.AGAIN to 0, Rating.HARD to hard, Rating.GOOD to good, Rating.EASY to easy)
    }

    /**
     * 同一张卡在同一个自然日里的抖动种子。
     *
     * 按钮上的间隔与真正写进 `due` 的间隔是**两次**调用（渲染时一次、评级时一次），所以抖动
     * 不能取自一个共享的 `Random(System.nanoTime())`：那样两次各抽一个数，标签说 12 天而存进去
     * 是 11 天，而且同一张卡换一次界面读数还会自己跳。Anki 的 Rust 实现按 (card id, 复习次数)
     * 定种子；`Fsrs` 是纯函数层、拿不到 id，所以用状态本身加当天序号——同一张卡在同一个自然日里
     * 抽到的永远是同一个抖动值，标签因此就是承诺。
     */
    private fun fuzzSeed(state: State, now: Long): Long {
        var seed = state.stability.toRawBits()
        seed = seed * 31 + state.difficulty.toRawBits()
        seed = seed * 31 + state.reviewCount
        seed = seed * 31 + state.lapses
        return seed * 31 + now / MS_PER_DAY
    }

    /** Shortest interval this configuration would ever schedule, used to warn about absurd retention settings. */
    fun minimumIntervalDays(): Int = intervalForStability(MIN_STABILITY, fuzz = false)

    /** Longest interval this configuration would ever schedule. */
    fun maximumIntervalDays(): Int =
        intervalForStability(params[3] * 100, fuzz = false).coerceAtMost(MAX_INTERVAL_DAYS)

    // ── internals ────────────────────────────────────────────────────────────

    /** Returns the post-review (difficulty, stability) pair for a grade. */
    private fun evolve(state: State, rating: Rating, now: Long): Pair<Double, Double> {
        val isNew = state.reviewCount == 0 || state.stability <= 0.0
        if (isNew) return initDifficulty(rating) to initStability(rating)

        val elapsedDays = (now - state.lastReview).toDouble() / MS_PER_DAY

        // Re-showing the same card on the same calendar day must not push the interval out;
        // FSRS-6 has a dedicated convergence term for this.
        val sameDay = now >= state.lastReview &&
            java.time.Instant.ofEpochMilli(now).atZone(java.time.ZoneId.systemDefault()).toLocalDate() ==
                java.time.Instant.ofEpochMilli(state.lastReview)
                    .atZone(java.time.ZoneId.systemDefault()).toLocalDate()
        if (sameDay && now < state.due) {
            val converged = nextShortTermStability(state.stability, rating)
            return nextDifficulty(state.difficulty, rating) to converged
        }

        val retrievability = forgettingCurve(elapsedDays, state.stability)
        val nextStability = when (rating) {
            Rating.AGAIN -> nextForgetStability(state.difficulty, state.stability, retrievability)
            else -> nextRecallStability(state.difficulty, state.stability, retrievability, rating)
        }
        return nextDifficulty(state.difficulty, rating) to nextStability
    }

    private fun forgettingCurve(elapsedDays: Double, stability: Double): Double {
        // Guard the power: elapsed<=0 or S<=0 would otherwise produce NaN.
        if (elapsedDays <= 0.0) return 1.0
        if (stability <= 0.0) return 0.0
        return (1 + factor * elapsedDays / stability).pow(-params[20])
    }

    private fun initDifficulty(rating: Rating): Double =
        constrainDifficulty(params[4] - exp(params[5] * (rating.value - 1)) + 1)

    private fun initStability(rating: Rating): Double =
        round2(params[rating.value - 1]).coerceAtLeast(MIN_STABILITY)

    /**
     * Same-day ("short term") stability.
     *
     * The `sinc` clamp below is not cosmetic and is easy to lose in a port: without it, tapping
     * *Good* on a same-day re-show of an already-strong card *shrinks* stability — the `s^-w19`
     * damping term overtakes the `e^(w17*(G-3+w18))` growth term once S is large — which quietly
     * penalises the exact behaviour a learner is being told to reward.
     *
     * **阈值是 G >= 3（GOOD 及以上），不是 wiki 那句话写的 "G >= 2"。** 照参考实现核过：
     * `fsrs4anki_scheduler.js` 里是 `if (rating >= 3) sinc = Math.max(sinc, 1)`，
     * 也就是 HARD（G=2）**不**钳制——同一天按「困难」本来就该让稳定性下降。
     * 先前这里的注释引了 wiki 的 "G >= 2" 又自我矛盾地说"参考实现按 >= GOOD 执行"，
     * 那种注释比没注释更危险：下一个人会照着它把对的代码"修"成 bug。
     */
    private fun nextShortTermStability(s: Double, rating: Rating): Double {
        var sinc = exp(params[17] * (rating.value - 3 + params[18])) * s.pow(-params[19])
        if (rating.value >= Rating.GOOD.value) sinc = max(sinc, 1.0)
        return round2(s * sinc).coerceAtLeast(MIN_STABILITY)
    }

    private fun intervalForStability(stability: Double, fuzz: Boolean, random: Random = rng): Int {
        val raw = stability / factor * (requestRetention.pow(1.0 / decay) - 1)
        val value = if (fuzz) applyFuzz(raw, random) else raw
        return value.roundToInt().coerceIn(1, MAX_INTERVAL_DAYS)
    }

    /**
     * ±5% jitter, applied only once the interval is long enough that identical intervals
     * would pile every card up on the same future day.
     */
    private fun applyFuzz(interval: Double, random: Random = rng): Double {
        if (interval < 2.5) return interval
        val ivl = interval.roundToInt()
        val minIvl = max(2.0, (ivl * 0.95 - 1).roundToInt().toDouble())
        val maxIvl = (ivl * 1.05 + 1).roundToInt().toDouble()
        return floor(random.nextDouble() * (maxIvl - minIvl + 1) + minIvl)
    }

    private fun nextDifficulty(currentD: Double, rating: Rating): Double {
        val deltaD = -params[6] * (rating.value - 3)
        val damped = deltaD * (10 - currentD) / 9
        val nextD = currentD + damped
        val reverted = params[7] * initDifficulty(Rating.EASY) + (1 - params[7]) * nextD
        return constrainDifficulty(reverted)
    }

    private fun nextRecallStability(d: Double, s: Double, r: Double, rating: Rating): Double {
        val hardPenalty = if (rating == Rating.HARD) params[15] else 1.0
        val easyBonus = if (rating == Rating.EASY) params[16] else 1.0
        val growth = exp(params[8]) * (11 - d) * s.pow(-params[9]) *
            (exp((1 - r) * params[10]) - 1) * hardPenalty * easyBonus
        return round2(s * (1 + growth)).coerceAtLeast(MIN_STABILITY)
    }

    private fun nextForgetStability(d: Double, s: Double, r: Double): Double {
        val ceiling = s / exp(params[17] * params[18])
        val result = params[11] * d.pow(-params[12]) * ((s + 1).pow(params[13]) - 1) *
            exp((1 - r) * params[14])
        return round2(min(result, ceiling)).coerceAtLeast(MIN_STABILITY)
    }

    /**
     * The reference implementation rounds D and S to two decimals after every transition
     * (`(+x).toFixed(2)`). Keeping that rounding means our intervals agree with Anki's
     * FSRS4Anki card-for-card instead of drifting by a day on long schedules.
     */
    private fun round2(v: Double): Double = kotlin.math.round(v * 100.0) / 100.0

    private fun constrainDifficulty(d: Double): Double = round2(d).coerceIn(1.0, 10.0)
}
