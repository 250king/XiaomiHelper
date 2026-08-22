/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 *
 * This file is part of XiaomiHelper project
 * Copyright (C) 2026 HowieHChen, howie.dev@outlook.com

 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * any later version.

 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.

 * You should have received a copy of the GNU General Public License
 * along with this program. If not, see <https://www.gnu.org/licenses/>.
 */

package dev.lackluster.mihelper.hook.rules.music

import com.highcapable.kavaref.KavaRef.Companion.resolve
import dev.lackluster.mihelper.data.preference.Preferences
import dev.lackluster.mihelper.hook.base.StaticHooker
import dev.lackluster.mihelper.hook.utils.RemotePreferences.lazyGet
import dev.lackluster.mihelper.hook.utils.toTyped

object HideBottomTab : StaticHooker() {
    private const val TOP_TAB_HOME_ID = 1
    private const val TOP_TAB_KEGE_ID = 2
    private const val TOP_TAB_LONG_AUDIO_ID = 3
    private const val TOP_TAB_QUICK_PLAY_ID = 4

    private val hideLongAudio by Preferences.Music.HIDE_TAB_LONG_AUDIO.lazyGet()
    private val hideQuickPlay by Preferences.Music.HIDE_TAB_QUICK_PLAY.lazyGet()
    private val hideFreeMode by Preferences.Music.HIDE_TAB_FREE_MODE.lazyGet()

    private val clzTopTab by "com.tencent.qqmusiclite.data.dto.shelfcard2.TopTab".lazyClassOrNull()
    private val fldTabId by lazy {
        clzTopTab?.resolve()?.firstFieldOrNull {
            name = "id"
        }?.toTyped<Int>()
    }

    override fun onInit() {
        updateSelfState(hideLongAudio || hideQuickPlay || hideFreeMode)
    }

    override fun onHook() {
        "com.tencent.qqmusiclite.util.ConciseModeManager".toClassOrNull()?.apply {
            if (hideLongAudio) {
                resolve().firstMethodOrNull {
                    name = "shouldShowBottomLongAudioTab"
                }?.hook {
                    result(false)
                }
            }
            if (hideQuickPlay) {
                resolve().firstMethodOrNull {
                    name = "shouldShowBottomQuickPlayTab"
                }?.hook {
                    result(false)
                }
            }
            if (hideFreeMode) {
                resolve().firstMethodOrNull {
                    name = "shouldShowBottomFreeModeTab"
                }?.hook {
                    result(false)
                }
            }
        }
        "com.tencent.qqmusiclite.fragment.home.BaseHomeFragment".toClassOrNull()?.apply {
            val metGetTabs = resolve().firstMethodOrNull {
                name = "getTabs"
            }?.toTyped<MutableList<Any?>>()
            resolve().firstMethodOrNull {
                name = "updateTabs"
            }?.hook {
                val ori = proceed()
                val kegeEnabled = getArg(0) == true
                metGetTabs?.invoke(thisObject)?.let { list ->
                    val filtered = list.filter {
                        val id = fldTabId?.get(it) ?: return@filter true
                        when (id) {
                            TOP_TAB_HOME_ID -> true
                            TOP_TAB_KEGE_ID -> kegeEnabled
                            TOP_TAB_LONG_AUDIO_ID -> !hideLongAudio
                            TOP_TAB_QUICK_PLAY_ID -> !hideQuickPlay
                            else -> true
                        }
                    }
                    list.clear()
                    list.addAll(filtered)
                }
                result(ori)
            }
        }
    }
}
