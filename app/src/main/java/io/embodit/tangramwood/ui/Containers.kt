package io.embodit.tangramwood.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/** Ids of the two window containers. The tangram volume is the default container (manifest). */
object Containers {
    const val BOARD = "TangramVolume"
    const val CONTROL = "TangramControl"
    const val CONTROL_TAG = "control"
}

/** The four pages of the control window (design panels.control: 拼 / 题库 / 规矩 / 设置). */
enum class Page(val stateId: String) { PLAY("S02"), LIBRARY("S12"), RULES("S14"), SETTINGS("S15") }

/** The settings page's two confirmations (S16 exit / clear records), kept in the window. */
enum class SettingsConfirm { EXIT, CLEAR }

/** Window-page state shared process-wide so debug commands and the game flow can switch pages. */
object Ui {
    var page by mutableStateOf(Page.PLAY)
    var settingsConfirm by mutableStateOf<SettingsConfirm?>(null)
    /** The library's group segment (热身 / 小动物 / 几何). */
    var libraryGroup by mutableStateOf(0)
}
