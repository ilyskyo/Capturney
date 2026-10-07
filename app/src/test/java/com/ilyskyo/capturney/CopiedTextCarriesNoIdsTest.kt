// Copyright (c) 2026 ilyskyo
// SPDX-License-Identifier: MIT

package com.ilyskyo.capturney

import com.ilyskyo.capturney.data.model.Entry
import com.ilyskyo.capturney.data.model.OverlayLayer
import com.ilyskyo.capturney.data.model.EntryObject
import com.ilyskyo.capturney.ui.lookback.EntryDetailState
import com.ilyskyo.capturney.ui.lookback.ObjectPlace
import com.ilyskyo.capturney.ui.lookback.detailPlainText
import com.ilyskyo.capturney.vision.camera.CameraFocusMath.NormBox
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 详情页那颗「复制文本」的约定是：**只给人能读出来的那部分，不给内部 id**。
 *
 * 为什么这值得一条测试：剪贴板是这本日记里唯一一个**内容会离开 App** 的地方——粘进备忘录、
 * 聊天、笔记软件都是常态。所以 id、文件名这类内部结构一旦混进去，就是顺着用户的手漏出去的，
 * 而它在屏幕上完全看不出来（用户看到的只是「多了一串没用的字符」，甚至看不出那是 id）。
 *
 * 断言写成「**任何** id 形状的东西都不该出现」而不是「不该出现 `entry.id`」：
 * 条目 id、物体 id、照片文件名、贴纸文件名一起查。将来给 `detailPlainText` 加字段的人
 * 不必知道哪些字段是内部标识——只要它是这条记录里那些标识之一，就会被抓。
 */
class CopiedTextCarriesNoIdsTest {

    @Test
    fun the_copied_text_carries_the_readable_part_and_no_identifiers() {
        val state = stateFor(
            entryId = "9f3c1a7e",
            objectId = "4b7d2e",
            photoPath = "IMG_20261007_1a2b3c.jpg",
            stickerPath = "sticker_9z8y7x.png",
        )
        val copied = detailPlainText(state)

        // 先确认它确实把该给的给了——否则「没有 id」这条可以靠返回空串轻松通过。
        assertTrue("复制出来的文本没有标题：$copied", copied.contains("清晨的厨房"))
        assertTrue("复制出来的文本没有摘要：$copied", copied.contains("杯子是空的"))
        assertTrue("复制出来的文本没有画面上的词：$copied", copied.contains("cup"))
        assertTrue("复制出来的文本没有氛围词：$copied", copied.contains("warm"))

        for (identifier in listOf("9f3c1a7e", "4b7d2e", "IMG_20261007_1a2b3c", "sticker_9z8y7x", ".jpg", ".png")) {
            assertFalse(
                "复制出来的文本里出现了内部标识 `$identifier`：$copied。剪贴板是这本日记里唯一一个" +
                    "内容会离开 App 的地方，内部结构不该顺着用户的手粘出去",
                copied.contains(identifier),
            )
        }
    }

    private fun stateFor(entryId: String, objectId: String, photoPath: String, stickerPath: String) =
        EntryDetailState(
            entry = Entry(
                id = entryId,
                photoPath = photoPath,
                takenAt = 1_760_000_000_000L,
                title = "清晨的厨房",
                summary = "杯子是空的",
                ambience = listOf("warm"),
                objects = listOf(
                    EntryObject(
                        id = objectId,
                        word = "cup",
                        left = 0.2f,
                        top = 0.3f,
                        right = 0.5f,
                        bottom = 0.8f,
                        stickerPath = stickerPath,
                        layer = OverlayLayer.ITEM,
                    ),
                ),
            ),
            // 详情页的正文与照片无关（照片为 null 时页面照常工作），这里也不需要真的位图。
            photo = null,
            objects = listOf(
                ObjectPlace(
                    id = objectId,
                    word = "cup",
                    gloss = "杯子",
                    box = NormBox(0.2f, 0.3f, 0.5f, 0.8f),
                    hasSticker = true,
                ),
            ),
            ambience = listOf("warm"),
        )
}
