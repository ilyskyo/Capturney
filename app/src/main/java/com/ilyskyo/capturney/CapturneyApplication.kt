// Copyright (c) 2026 ilyskyo
// SPDX-License-Identifier: MIT

package com.ilyskyo.capturney

import android.app.Application
import android.util.Log
import com.ilyskyo.capturney.core.AppContainer
import com.ilyskyo.capturney.ui.theme.Haptics
import kotlinx.coroutines.launch

/**
 * 进程级入口：持有 [AppContainer]，并在冷启动就预热所有 JSON 文档。
 *
 * 命名为 CapturneyApplication 而不是 CapturneyApp，是为了避开同包里那个同名的
 * @Composable（ui.nav.CapturneyApp）——两者会在 MainActivity 里同文件出现。
 */
class CapturneyApplication : Application() {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        container.warmUp()
        // 分割模型要在第一次按下快门之前就在后台躺好：主线程加载它是最典型的 ANR 形状。
        // 这里不 await——取景页只 collect 结果，早到晚到都不影响它能拿到什么。
        container.applicationScope.launch { container.vision.prepareSegmentation() }
        // 振动器在第一次 vibrate 之前要过一次 IPC，那一下正好落在用户第一次按下按钮时——
        // 触觉慢半拍比没有触觉更明显。所以在这里提前解析，代价是一次系统服务查询。
        Haptics.init(this)
        Log.i(TAG, "Capturney started, filesDir=$filesDir")
    }

    private companion object {
        const val TAG = "CapturneyApp"
    }
}
