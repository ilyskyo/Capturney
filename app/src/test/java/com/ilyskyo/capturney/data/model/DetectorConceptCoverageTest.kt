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
        val wrong = COMPOUND_LABELS.mapNotNull { (label, id) ->
            val top = index.match(listOf(label to 1.0f)).firstOrNull()
            if (top?.entry?.id == id) null else "'$label' 命中 ${top?.entry?.id}，应为 $id"
        }
        assertTrue("复合类别被解析成了别的东西：\n${wrong.joinToString("\n")}", wrong.isEmpty())
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
                val entry = index.byId(id) ?: continue
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
                val entry = index.byId(id) ?: continue
                for (tag in listOf("zh", "ja", "ko")) {
                    if (entry.glosses[tag].isNullOrBlank()) add("$id 缺 glosses[$tag]")
                }
            }
        }
        assertTrue("这些概念没有母语释义，卡背面会是空的：\n${missing.joinToString("\n")}", missing.isEmpty())
    }

    private companion object {
        const val WHOLE_LABEL_FLOOR = 0.95f

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
