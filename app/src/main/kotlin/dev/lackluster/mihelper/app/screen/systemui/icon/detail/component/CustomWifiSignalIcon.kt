package dev.lackluster.mihelper.app.screen.systemui.icon.detail.component

import android.graphics.Paint
import android.graphics.Picture
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import dev.lackluster.mihelper.R
import dev.lackluster.mihelper.app.repository.CustomWifiSignalState
import kotlin.math.ceil

@Composable
fun CustomWifiSignalIcon(
    picture: Picture?,
    state: CustomWifiSignalState,
) {
    if (picture == null) {
        Box(Modifier.size(24.dp))
        return
    }

    val density = LocalDensity.current
    val layoutDirection = LocalLayoutDirection.current
    val tint = colorResource(R.color.foreground_dual_tone_full).toArgb()
    val picturePaint = remember(tint) {
        Paint().apply { colorFilter = PorterDuffColorFilter(tint, PorterDuff.Mode.SRC_IN) }
    }

    BoxWithConstraints(Modifier.height(24.dp).padding(vertical = 2.dp)) {
        val targetHeight = constraints.maxHeight.toFloat()
        val pictureScale = targetHeight / picture.height.toFloat()
        val iconScale = state.scale.coerceIn(0.5f, 1.5f)
        val start = state.paddingStart.coerceAtLeast(0f) * density.density
        val end = state.paddingEnd.coerceAtLeast(0f) * density.density
        val paddingLeft = if (layoutDirection == LayoutDirection.Rtl) end else start
        val paddingRight = if (layoutDirection == LayoutDirection.Rtl) start else end
        val width = ceil(picture.width * pictureScale * iconScale) + paddingLeft + paddingRight
        val offsetY = (targetHeight - targetHeight * iconScale) / 2f

        Canvas(Modifier.width(with(density) { width.toDp() }).fillMaxHeight()) {
            drawIntoCanvas { canvas ->
                canvas.save()
                canvas.translate(paddingLeft, offsetY)
                canvas.scale(iconScale, iconScale)
                canvas.save()
                canvas.scale(pictureScale, pictureScale)
                canvas.nativeCanvas.saveLayer(null, picturePaint)
                canvas.nativeCanvas.drawPicture(picture)
                canvas.nativeCanvas.restore()
                canvas.restore()
                canvas.restore()
            }
        }
    }
}
