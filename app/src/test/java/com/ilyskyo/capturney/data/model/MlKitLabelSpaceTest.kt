// Copyright (c) 2026 ilyskyo
// SPDX-License-Identifier: MIT

package com.ilyskyo.capturney.data.model

import kotlinx.serialization.json.Json
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 默认识别器（ML Kit 图像标注，446 个类）送进词典的每一个标签，都只许在**整词档位**上长成词片。
 *
 * ## 为什么检测器那份守卫不够
 *
 * `DetectorConceptCoverageTest` 钉的是 EfficientDet 的 COCO 类别。取景页还有另一条同样能长出
 * 词片的路：`VisionRepository.analyze` 把引擎给的标签原样喂进匹配，结果直接是界面上的
 * `suggestions`。而默认引擎是图像标注器，它的标签空间和 COCO **不是同一张表**。
 *
 * 2026-10-09 把这 446 个标签逐个跑了一遍，17 个会掉到标签内部的某个词。调用方取的是第一名
 * 而且不看分数，所以这些会直接变成教错东西的卡：
 *
 * - `Fast food` → `en.fast`「快的」
 * - `Mobile phone` → `en.mobile`
 * - `Cookware and bakeware` → `en.and`「和」
 * - `Stuffed toy` → `en.toy`、`Pop music` → `en.pop`、`Santa claus` → `en.santa`
 *
 * 修法不是给这 17 个补概念（那要撰写 34 个此刻无法核证的日语韩语值，而且下个模型版本会再来一遍），
 * 而是在**引擎标签进词典**那一步设档位门槛：低于整词档就一个都不给。
 * 这不是减少功能——`PhotoEntryPipeline` 与 `CaptureViewModel` 在命中为空时**本来**就回落到
 * 原始标签文本、不建条目。所以它是把「说错」换回「不说」。
 *
 * ## 门槛为什么量在 `tier` 而不是 `confidence`
 *
 * `confidence = 档位 × 模型分`，而图像标注的模型分常年在 0.4～0.9。
 * 拿 0.95 去卡 `confidence` 会几乎杀掉整个词片层，并且卡错了东西——
 * 该问的是「这个条目是不是那个东西」，不是「模型当时有多确信」。`aLowModelScore...` 那条就是钉这个的。
 *
 * ## `src/test/resources/mlkit-labels.txt` 是从哪来的
 *
 * 不是手抄：标签列在模型自带的元数据里（release 包的
 * `assets/mlkit_label_default_model/mobile_ica_8bit_with_metadata_tflite` 内嵌 `0-labels-en.txt`），
 * 由脚本从**已构建的 APK** 抽出来写成这份表，抽取方式记在 `docs/BUILD.md`。
 * 它是测试夹具而不是查表路径——生产代码依然不认识任何具体标签（见 `Lexicon.kt` 那条设计说明）。
 * 换模型时这张表会先红，提醒重新抽取。
 */
class MlKitLabelSpaceTest {

    private val dir = File("src/main/assets/lexicon")
    private val labelsFile = File("src/test/resources/mlkit-labels.txt")

    /** 与 `LexiconRepository.reload()` 同样的合并顺序：内置 → 概念补丁 → 注音层。 */
    private val index: LexiconIndex by lazy {
        val json = Json { ignoreUnknownKeys = true }
        val entries = LinkedHashMap<String, LexiconEntry>()
        json.decodeFromString(LexiconFile.serializer(), File(dir, "en.json").readText(Charsets.UTF_8))
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

    private val labels: List<String> by lazy {
        assertTrue("标签表不在 ${labelsFile.absolutePath}——它是从 release APK 抽的，见 docs/BUILD.md", labelsFile.isFile)
        labelsFile.readText(Charsets.UTF_8).lineSequence()
            .map { it.trim() }.filter { it.isNotEmpty() }.toList()
            .also { assertTrue("只读到 ${it.size} 条标签，量具肯定不对（模型带的是 446 个类）", it.size >= 400) }
    }

    @Test
    fun noEngineLabelCanSuggestABelowTierEntry() {
        val offenders = labels.mapNotNull { label ->
            val below = index.matchWholeLabel(listOf(label to 1.0f)).filter { it.tier < WHOLE_LABEL_TIER }
            if (below.isEmpty()) null else "'$label' 给出了低于整词档的 ${below.map { it.entry.id }}"
        }
        assertTrue("这些标签还会铸成组成词：\n${offenders.joinToString("\n")}", offenders.isEmpty())
    }

    @Test
    fun theLabelsThatUsedToTeachComponentWordsNowSayNothing() {
        // 这 17 个是 2026-10-09 实测掉档的那批。逐个点名而不是只看总数：
        // 只看总数会在门槛被误调紧（把所有标签都过滤掉）时依然通过。
        val silent = FORMER_COMPONENT_FALLBACKS.mapNotNull { label ->
            val hit = index.matchWholeLabel(listOf(label to 1.0f)).firstOrNull()
            if (hit == null) null else "'$label' 现在仍然命中 ${hit.entry.id}/${hit.entry.words["en"]}（档 ${hit.tier}）"
        }
        assertTrue("这些本该闭嘴的标签还在给条目：\n${silent.joinToString("\n")}", silent.isEmpty())
    }

    @Test
    fun aLowModelScoreDoesNotSinkAWholeLabelMatch() {
        // 图像标注的分数常在 0.5 上下。整词命中必须照样出得来——
        // 门槛量的是档位，不是 confidence（0.95 档 × 0.5 分 = 0.475，用 confidence 卡就全没了）。
        val hit = index.matchWholeLabel(listOf("traffic light" to 0.5f)).firstOrNull()
        assertTrue("模型分 0.5 时连整词命中都被滤掉了——门槛八成卡在 confidence 上", hit != null)
        assertTrue("traffic light 第一名不对：${hit?.entry?.id}", hit?.entry?.id == "en.traffic_light")
    }

    @Test
    fun theGateStillLetsMostLabelsGrowWords() {
        // 2026-10-09 实测：446 个标签里 363 个在门槛之后仍然长出词片。
        // 门槛只改变了 17 个——就是 `theLabelsThatUsedToTeach...` 点名的那 17 个，
        // 它们从「给一个错的条目」变成「不给条目」；另有 66 个本来就查不到，两边都一样静着。
        // 下限取 350 而不是 363：留出十来个余量给以后新增的概念，
        // 但一旦有人把档位算错（或把过滤改成按 confidence 卡）到几乎什么都不剩，这条会红。
        // 需要这条是因为：**把所有标签都过滤掉也能让前面几条测试通过**，
        // 而那不是修好了错义项，那是把功能关掉。
        val suggested = labels.count { index.matchWholeLabel(listOf(it to 1.0f)).isNotEmpty() }
        assertTrue(
            "446 个标签里只剩 $suggested 个还能长出词片（实测门槛只该改变那 17 个）——" +
                "这不是在修错义项，是把功能关掉了。门槛调错的方向和没有门槛一样糟",
            suggested >= 350,
        )
    }

    private companion object {
        /** 2026-10-09 实测会掉到组成词的 17 个标签（模型分按 1.0 喂，纯粹看档位）。 */
        val FORMER_COMPONENT_FALLBACKS = listOf(
            "Aerospace engineering", "Basset hound", "Bunk bed", "Cairn terrier",
            "Cookware and bakeware", "Fast food", "Ferris wheel", "Mobile phone",
            "Musical instrument", "Pixie-bob", "Pop music", "Santa claus", "Scuba diving",
            "Stairs", "Stuffed toy", "Watercolor paint", "Web page",
        )
    }
}
