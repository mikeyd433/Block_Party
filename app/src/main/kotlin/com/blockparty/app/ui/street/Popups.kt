package com.blockparty.app.ui.street

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.blockparty.core.geometry.IsoProjection
import com.blockparty.core.geometry.Vec2

/** A little floating label in the world: coins earned, or a review's stars. */
class Popup(val text: String, val color: Color, val at: Vec2, val startSec: Float, val extraRise: Float = 0f) {
    companion object {
        const val DURATION_SEC = 1.6f
    }
}

/** Draws popups rising and fading above where they happened (world coordinates). */
fun DrawScope.drawPopups(popups: List<Popup>, timeSec: Float, iso: IsoProjection, textMeasurer: TextMeasurer) {
    for (p in popups) {
        val t = (timeSec - p.startSec) / Popup.DURATION_SEC
        if (t !in 0f..1f) continue
        val alpha = if (t < 0.75f) 1f else 1f - (t - 0.75f) / 0.25f
        val base = iso.gridToScreen(p.at.x, p.at.y)
        val rise = iso.tileHeight * (2.3f + p.extraRise + 1.2f * t)
        val layout = textMeasurer.measure(
            p.text,
            TextStyle(fontSize = 22.sp, fontWeight = FontWeight.Bold, color = p.color.copy(alpha = alpha)),
        )
        val topLeft = Offset(base.x - layout.size.width / 2f, base.y - rise - layout.size.height)
        val pad = 8f
        drawRoundRect(
            Color.White.copy(alpha = 0.85f * alpha),
            topLeft = topLeft - Offset(pad, pad / 2f),
            size = Size(layout.size.width + pad * 2, layout.size.height + pad),
            cornerRadius = CornerRadius(14f),
        )
        drawText(layout, topLeft = topLeft)
    }
}
