package com.kejing.tangramwood

import androidx.compose.foundation.layout.Box
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.pico.spatial.ui.foundation.dsl.DefaultWindowContainer
import com.pico.spatial.ui.foundation.dsl.Form
import com.pico.spatial.ui.foundation.dsl.Placement
import com.pico.spatial.ui.foundation.dsl.SpatialAppScope
import com.pico.spatial.ui.foundation.dsl.WindowContainer
import com.pico.spatial.ui.foundation.dsl.WindowContainerSize
import com.kejing.tangramwood.ui.Containers
import com.kejing.tangramwood.ui.board.BoardScreen
import com.kejing.tangramwood.ui.control.ControlWindow
import com.kejing.tangramwood.ui.theme.TangramTheme

/**
 * Two surfaces in Shared Space (design-package windowModel volume_plus_control_window): the tangram volume (default
 * container, 0.68 x 0.49 x 1.05 m from the manifest, D17-enlarged) and the control window (Planar 620 x 580 dp) to its
 * right. Every open (first open, the S17 tap, [重开控制窗]) uses Placement.right(volume) (profile D44 correction: Shared
 * Space never promises "in front of you"; the user moves the panel with the system handle).
 */
fun mainApp(scope: SpatialAppScope) =
    with(scope) {
        DefaultWindowContainer {
            TangramTheme { BoardScreen() }
        }
        WindowContainer(
            Containers.CONTROL,
            form = Form.Planar,
            defaultSize = WindowContainerSize(width = 640.dp, height = 620.dp),
            placement = { containers.firstOrNull { it.id == Containers.BOARD }?.let { Placement.right(it) } ?: Placement.none() },
        ) {
            TangramTheme {
                Box(Modifier.windowConstraints(minWidth = 600.dp, minHeight = 580.dp, maxWidth = 900.dp, maxHeight = 700.dp)) { ControlWindow() }
            }
        }
    }
