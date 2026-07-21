package com.maktabah.ui.common

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp

/**
 * Shape ala NSPopover: rounded rect + segitiga kecil (arrow) yang menunjuk
 * ke titik asal popup (misalnya item yang di-long-press).
 *
 * @param cornerRadius radius sudut body popover
 * @param arrowWidth lebar dasar segitiga arrow
 * @param arrowHeight tinggi/panjang segitiga arrow (menonjol keluar body)
 * @param arrowOffsetX posisi horizontal arrow relatif dari kiri body (0 = paling kiri)
 * @param arrowAtTop true = arrow nempel di sisi atas (nunjuk ke atas), false = di sisi bawah
 */
class PopoverArrowShape(
	private val cornerRadius: Dp = 20.dp,
	private val arrowWidth: Dp = 14.dp,
	private val arrowHeight: Dp = 7.dp,
	private val arrowOffsetX: Dp,
	private val arrowAtTop: Boolean = true,
) : Shape {

	override fun createOutline(
		size: Size,
		layoutDirection: LayoutDirection,
		density: Density,
	): Outline {
		val radiusPx = with(density) { cornerRadius.toPx() }
		val arrowWidthPx = with(density) { arrowWidth.toPx() }
		val arrowHeightPx = with(density) { arrowHeight.toPx() }
		var arrowCenterX = with(density) { arrowOffsetX.toPx() }

		// clamp supaya arrow tidak keluar dari lengkungan sudut body
		val minX = radiusPx + arrowWidthPx / 2f
		val maxX = size.width - radiusPx - arrowWidthPx / 2f
		if (maxX > minX) {
			arrowCenterX = arrowCenterX.coerceIn(minX, maxX)
		} else {
			arrowCenterX = size.width / 2f
		}

		val bodyTop = if (arrowAtTop) arrowHeightPx else 0f
		val bodyBottom = if (arrowAtTop) size.height else size.height - arrowHeightPx

		val path = Path().apply {
			// mulai dari kiri-atas body (setelah sudut)
			moveTo(radiusPx, bodyTop)

			if (arrowAtTop) {
				// sisi atas, sisipkan arrow menghadap ke atas
				lineTo(arrowCenterX - arrowWidthPx / 2f, bodyTop)
				lineTo(arrowCenterX, 0f)
				lineTo(arrowCenterX + arrowWidthPx / 2f, bodyTop)
			}
			lineTo(size.width - radiusPx, bodyTop)

			// sudut kanan-atas
			arcTo(
				rect = androidx.compose.ui.geometry.Rect(
					left = size.width - 2 * radiusPx,
					top = bodyTop,
					right = size.width,
					bottom = bodyTop + 2 * radiusPx,
				),
				startAngleDegrees = -90f,
				sweepAngleDegrees = 90f,
				forceMoveTo = false,
			)

			lineTo(size.width, bodyBottom - radiusPx)

			// sudut kanan-bawah
			arcTo(
				rect = androidx.compose.ui.geometry.Rect(
					left = size.width - 2 * radiusPx,
					top = bodyBottom - 2 * radiusPx,
					right = size.width,
					bottom = bodyBottom,
				),
				startAngleDegrees = 0f,
				sweepAngleDegrees = 90f,
				forceMoveTo = false,
			)

			if (!arrowAtTop) {
				lineTo(arrowCenterX + arrowWidthPx / 2f, bodyBottom)
				lineTo(arrowCenterX, size.height)
				lineTo(arrowCenterX - arrowWidthPx / 2f, bodyBottom)
			}
			lineTo(radiusPx, bodyBottom)

			// sudut kiri-bawah
			arcTo(
				rect = androidx.compose.ui.geometry.Rect(
					left = 0f,
					top = bodyBottom - 2 * radiusPx,
					right = 2 * radiusPx,
					bottom = bodyBottom,
				),
				startAngleDegrees = 90f,
				sweepAngleDegrees = 90f,
				forceMoveTo = false,
			)

			lineTo(0f, bodyTop + radiusPx)

			// sudut kiri-atas
			arcTo(
				rect = androidx.compose.ui.geometry.Rect(
					left = 0f,
					top = bodyTop,
					right = 2 * radiusPx,
					bottom = bodyTop + 2 * radiusPx,
				),
				startAngleDegrees = 180f,
				sweepAngleDegrees = 90f,
				forceMoveTo = false,
			)

			close()
		}

		return Outline.Generic(path)
	}
}