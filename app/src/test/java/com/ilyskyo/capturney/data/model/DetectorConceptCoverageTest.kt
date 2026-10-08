// Copyright (c) 2026 ilyskyo
// SPDX-License-Identifier: MIT

package com.ilyskyo.capturney.data.model

import kotlinx.serialization.json.Json
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 检测器点得出来的每一样东西，都必须有一个**说得出它名字**的概念。
 *
 * ## 为什么这条比「词典大不大」重要
 *
 * 铸条目那一步是 `index.match(label).firstOrNull()`——**取第一名，而且不看分数**。
 * 所以只要某个标签没有整词概念，第一名就会落到它的**组成词**上：
 * 修之前实测 "hot dog" 的第一名是 `en.hot`，"traffic light" 是 `en.traffic`，
 * "tennis racket" 是 `en.tennis`。屏幕上那就是一张贴错名字的贴纸：
 * 用户指着热狗，App 教他「hot」。
 *
 * 这类错不会崩、不会进日志、编译与 lint 全过，也不会在单测里露头——它只在真机上、
 * 恰好拍到那个东西时才看得见。所以把检测器的标签空间整个摊出来逐条断言。
 *
 * ## 阈值为什么是 0.95
 *
 * 分数阶梯是：整词命中 1.0 / 别名整词 0.97 / 只差空格 0.95 / 多词 n-gram 0.78 / 单词 0.58。
 * 0.95 以下就意味着「第一名是某个组成词」，而那正是上面那种错的签名。
 * 换句话说这条断言挑的是**档位**，不是随便一个数字。
 *
 * ## 这份标签表
 *
 * 是 efficientdet_lite0 那套 COCO 类别名（含带空格的那些）。模型自己带标签表、
 * 源码里没有硬编码可抄，所以这张表是手抄的——它的作用因此是「钉住我们认识这个标签空间」：
 * 哪天换模型或扩类别，这里会先红，提醒补概念而不是让某个物体继续被贴上错名字。
 */
class DetectorConceptCoverageTest {

    private val dir = File("src/main/assets/lexicon")
    private val json = Json { ignoreUnknownKeys = true }

    /**
     * 与 `LexiconRepository.reload()` 同样的合并顺序：内置 → 概念补丁 → 注音层。
     *
     * 第三份必须有。ja/ko 的释义不在 `concepts.json` 里，而在 `gloss-ja.json` / `gloss-ko.json`
     * 那一层；测试不合并它，就会拿「注音层没读到」当成「概念缺母语释义」来报——
     * 我第一次跑就是这么红的，报的是 `en.cellphone 缺 glosses[ja]`，
     * 而设备上那条其实有释义。**断言错的方向比断言失败更贵**：它会引导人去补已经对的东西。
     */
    private val index: LexiconIndex by lazy {
        val entries = LinkedHashMap<String, LexiconEntry>()
        val builtinFile = File(dir, "en.json")
        json.decodeFromString(LexiconFile.serializer(), builtinFile.readText(Charsets.UTF_8))
            .entries.forEach { entries[it.id] = it }
        json.decodeFromString(LexiconFile.serializer(), File(dir, "concepts.json").readText(Charsets.UTF_8))
            .entries.forEach { entries[it.id] = it }
        for (tag in listOf("ja", "ko")) {
            val overlay = File(dir, "gloss-$tag.json")
            if (!overlay.isFile) continue
            val parsed = json.decodeFromString(GlossOverlayFile.serializer(), overlay.readText(Charsets.UTF_8))
            for (item in parsed.entries) {
                val existing = entries[item.id] ?: continue
                entries[item.id] = existing.withGloss(parsed.language, item.word)
            }
        }
        LexiconIndex(entries.values.toList())
    }

    @Test
    fun everyDetectorClassHasAWholeLabelConcept() {
        val missing = DETECTOR_LABELS.mapNotNull { label ->
            val top = index.match(listOf(label to 1.0f)).firstOrNull()
            if (top != null && top.confidence >= WHOLE_LABEL_FLOOR) {
                null
            } else {
                // 报出第一名是什么：落到 "hot" 还是压根没命中，修法不一样。
                "'$label' 的第一名 = ${top?.let { "${it.entry.id}/${it.entry.words["en"]} ${it.confidence}" } ?: "无"}"
            }
        }
        assertTrue(
            "这些检测类别没有整词概念，会被贴上组成词的名字：\n${missing.joinToString("\n")}",
            missing.isEmpty(),
        )
    }

    @Test
    fun compoundLabelsResolveToThemselvesNotTheirComponents() {
        // 这一条盯的是「第一名是不是那个复合概念本身」，而不只是「有没有过阈值」：
        // 阈值挡得住 0.58 的组成词，挡不住一个恰好也叫整词的错概念。
        // 下面 PINNED_SINGLE_LABELS 是那句话的活标本：`teddy` 是**整词**命中 `en.teddy`、分数 1.0，
        // 而 ECDICT 那个词头的第一义项是「连衫衬裤」——拍到泰迪熊的人会被教成一件内衣。
        // 2026-10-09 用概念层把 `en.teddy` 覆盖成「泰迪熊」，这条盯着它别退回去。
        val wanted: List<Pair<String, String>> =
            COMPOUND_LABELS.map { (label, id) -> label to id } +
                PINNED_SINGLE_LABELS.map { (label, pinned) -> label to pinned.id }
        val wrong = wanted.mapNotNull { (label, id) ->
            val top = index.match(listOf(label to 1.0f)).firstOrNull()
            if (top?.entry?.id == id) null else "'$label' 命中 ${top?.entry?.id}，应为 $id"
        }
        assertTrue("这些类别被解析成了别的东西：\n${wrong.joinToString("\n")}", wrong.isEmpty())

        // 身份对了还要问「是哪个意思」：`teddy` 命中 `en.teddy` 拿满分，可 ECDICT 那个词头的
        // 第一义项是「连衫衬裤」——光比 id 的这条断言在它身上是**绿的**，
        // 所以必须单独问一次中文里有没有那个能拍到的东西。
        val wrongSense = PINNED_SINGLE_LABELS.mapNotNull { (label, want) ->
            val entry = index.match(listOf(label to 1.0f)).firstOrNull()?.entry
                ?: return@mapNotNull "'$label' 一个都没命中"
            val zh = entry.words["zh"].orEmpty()
            if (want.zhMustContain in zh) null
            else "「$label」→ ${entry.id} 的中文是「$zh」，不含「${want.zhMustContain}」"
        }
        assertTrue("这些相机词的正解被 ECDICT 的首义项顶掉了：\n${wrongSense.joinToString("\n")}", wrongSense.isEmpty())
    }

    /**
     * 补概念不能只补到「检测器认得」这一半——人得**查得到**它。
     *
     * 取景页长出来的词片与搜索页走的是两条路：搜索是 `index.search(query, language)`，
     * 它按「任一语言的词头或任一条释义」匹配。所以四种语言都得能把它搜出来：
     * 日语母语的人搜「信号機」，搜不到就等于这个概念对他不存在——
     * 而这正是 #49 那一整轮的问题形状（界面有四种语言，内容只做了两种）。
     */
    @Test
    fun everyNewConceptIsFindableInAllFourLanguages() {
        val unfindable = buildList {
            for ((label, id) in COMPOUND_LABELS) {
                val entry = index.byId(id)
                // 以前这里是 `?: continue`：概念一旦被删掉，这两条就**静默少测一个**，
                // 而它们的的名字仍承诺「每个复合概念四语可搜、都有背面」。查不到必须算红，
                // 不能算跳过——守卫跳过的地方和没有守卫一样（本仓库已经为此返工过一次）。
                if (entry == null) {
                    add("$id 在 en.json/concepts.json 里不存在——这条概念根本没进词典，不是可跳过的样本")
                    continue
                }
                for (tag in listOf("en", "zh", "ja", "ko")) {
                    val word = entry.words[tag] ?: continue
                    val foundIds = index.search(word).map { it.id }.toSet()
                    if (id !in foundIds) add("$id 用 $tag 的「$word」搜不出来")
                }
                // 英文标签本身也要能搜到（用户可能在搜索页打他刚看到的那个词）。
                if (id !in index.search(label).map { it.id }.toSet()) add("$id 搜「$label」找不到")
            }
        }
        assertTrue("这些概念查不到：\n${unfindable.joinToString("\n")}", unfindable.isEmpty())
    }

    /**
     * 四种语言各自当母语时，这张卡都**有背面**。
     *
     * 铸卡那一步只带真的存在的那个语言的释义（借来的语言会被滤掉，见 `LexiconEntry.toCard`），
     * 所以「words 里写了四语」不等于「四语母语的人拿得到背面」。这一条把两者对上。
     */
    @Test
    fun everyNewConceptHasAMotherTongueGlossForAllThreeNativeLanguages() {
        val missing = buildList {
            for ((_, id) in COMPOUND_LABELS) {
                val entry = index.byId(id)
                // 以前这里是 `?: continue`：概念一旦被删掉，这两条就**静默少测一个**，
                // 而它们的的名字仍承诺「每个复合概念四语可搜、都有背面」。查不到必须算红，
                // 不能算跳过——守卫跳过的地方和没有守卫一样（本仓库已经为此返工过一次）。
                if (entry == null) {
                    add("$id 在 en.json/concepts.json 里不存在——这条概念根本没进词典，不是可跳过的样本")
                    continue
                }
                for (tag in listOf("zh", "ja", "ko")) {
                    if (entry.glosses[tag].isNullOrBlank()) add("$id 缺 glosses[$tag]")
                }
            }
        }
        assertTrue("这些概念没有母语释义，卡背面会是空的：\n${missing.joinToString("\n")}", missing.isEmpty())
    }

    /**
     * 相机能长出来的那些词，背面**不许只有一种母语拿得到**。
     *
     * 断的是「孤儿」而不是「缺三种」：整表 13055 条里大部分本来就只有中文释义，那是产品当前
     * 的内容边界（`docs/BUILD.md` 的取数那一节写着），要求它们补齐四语会把一条假承诺写进测试。
     * 但检测器的标签空间不一样——它是**举起来就会长出来的那批词**，
     * 而铸卡时只带真正存在的那种语言的释义（借来的语言会被 `LexiconEntry.toCard` 滤掉）。
     * 所以只要某个相机词有 zh 却没有 ja/ko，就会出现一件很难发现的事：
     * 中文用户拍到杯子看到背面，日语用户拍到同一个杯子拿到一张空卡。
     * 两侧都不崩、不报错，只有换系统语言再拍一次才看得见——而那一步没人会做。
     *
     * 2026-10-09 实测这条是成立的（抽查 54 个相机词：三语全齐 54、只有中文 0、三语全缺 0），
     * 所以它现在钉的是「别把它弄坏」，而不是「去补还没做的」。
     */
    @Test
    fun noCameraVisibleWordHasAnEmptyCardBackForSomeNativeLanguage() {
        val orphans = buildList {
            for (label in DETECTOR_LABELS) {
                val top = index.match(listOf(label to 1.0f)).firstOrNull() ?: continue
                val entry = top.entry
                val present = listOf("zh", "ja", "ko").filter { tag ->
                    val word = entry.words[tag]
                    val gloss = entry.glosses[tag]
                    !word.isNullOrBlank() || !gloss.isNullOrBlank()
                }
                if (present.isNotEmpty() && present.size < 3) {
                    add("「$label」→ ${entry.id} 只有 $present，缺 " +
                        listOf("zh", "ja", "ko").filterNot { it in present })
                }
            }
        }
        assertTrue(
            "这些相机词会被某一种母语的用户拍成空背面：\n${orphans.joinToString("\n")}",
            orphans.isEmpty(),
        )
    }

    private companion object {
        const val WHOLE_LABEL_FLOOR = 0.95f

        /**
         * 单词形标签里那些**整词命中却是错概念**的。
         *
         * 它们过不了 0.95 那道阈值检查——阈值只看档位，而这一类错拿的是满分；
         * 光比 id 也不行，因为错的词头本身就是那个 id。所以要同时钉住
         * 「必须是哪个条目」与「中文里必须有那个能拍到的东西」。
         * `en.teddy` 的 ECDICT 首义项是「连衫衬裤」，2026-10-09 用概念层覆盖成「泰迪熊」。
         */
        data class Pinned(val id: String, val zhMustContain: String)

        val PINNED_SINGLE_LABELS = mapOf(
            "teddy" to Pinned("en.teddy", "熊"),
        )

        /** 带空格的类别：修之前它们全会掉到组成词上。 */
        val COMPOUND_LABELS = mapOf(
            "traffic light" to "en.traffic_light",
            "fire hydrant" to "en.fire_hydrant",
            "stop sign" to "en.stop_sign",
            "parking meter" to "en.parking_meter",
            "cell phone" to "en.cellphone",
            "potted plant" to "en.potted_plant",
            "dining table" to "en.dining_table",
            "sports ball" to "en.sports_ball",
            "baseball bat" to "en.baseball_bat",
            "baseball glove" to "en.baseball_glove",
            "tennis racket" to "en.tennis_racket",
            "wine glass" to "en.wine_glass",
            "hot dog" to "en.hot_dog",
            "teddy bear" to "en.teddy_bear",
            "hair drier" to "en.hair_drier",
        )

        val DETECTOR_LABELS = COMPOUND_LABELS.keys + listOf(
            "person", "bicycle", "car", "motorcycle", "airplane", "bus", "train", "truck", "boat",
            "bench", "bird", "cat", "dog", "horse", "sheep", "cow", "elephant", "bear", "zebra",
            "giraffe", "backpack", "umbrella", "handbag", "tie", "suitcase", "frisbee", "skis",
            "snowboard", "skateboard", "surfboard", "bottle", "cup", "fork", "knife", "spoon",
            "bowl", "banana", "apple", "sandwich", "orange", "broccoli", "carrot", "pizza",
            "donut", "cake", "chair", "couch", "bed", "toilet", "tv", "laptop", "mouse", "remote",
            "keyboard", "oven", "toaster", "sink", "refrigerator", "book", "clock", "vase",
            "scissors", "teddy", "toothbrush",
        )
    }
}
