package com.maktabah.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.graphics.toColorInt
import com.maktabah.R

@Composable
fun ColorPickerDialog(
    initialColor: String,
    onColorSelected: (String) -> Unit,
    onDismissRequest: () -> Unit
) {
    val initial = try {
        Color(initialColor.toColorInt())
    } catch (_: Exception) {
        Color.Yellow
    }

    var hsv by remember {
        val hsvArr = FloatArray(3)
        android.graphics.Color.colorToHSV(initial.toArgb(), hsvArr)
        mutableStateOf(hsvArr)
    }

    val currentColor = Color(android.graphics.Color.HSVToColor(hsv))
    val hexString = String.format("#%06X", (0xFFFFFF and currentColor.toArgb()))

    AlertDialog(
        onDismissRequest = onDismissRequest,
        title = { Text(stringResource(R.string.color_picker_title)) },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // Color Preview
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(60.dp)
                        .background(currentColor, shape = RoundedCornerShape(8.dp))
                        .padding(8.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = hexString,
                        color = if (hsv[2] < 0.5f) Color.White else Color.Black,
                        style = MaterialTheme.typography.titleMedium
                    )
                }

                // Hue Slider
                ColorSlider(
                    label = "Hue",
                    value = hsv[0],
                    range = 0f..360f,
                    onValueChange = { hsv = hsv.copyOf().apply { this[0] = it } }
                )

                // Saturation Slider
                ColorSlider(
                    label = "Saturation",
                    value = hsv[1],
                    range = 0f..1f,
                    onValueChange = { hsv = hsv.copyOf().apply { this[1] = it } }
                )

                // Value Slider
                ColorSlider(
                    label = "Value",
                    value = hsv[2],
                    range = 0f..1f,
                    onValueChange = { hsv = hsv.copyOf().apply { this[2] = it } }
                )
            }
        },
        confirmButton = {
            Button(onClick = { onColorSelected(hexString) }) {
                Text(stringResource(R.string.color_picker_save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismissRequest) {
                Text(stringResource(R.string.color_picker_cancel))
            }
        }
    )
}

@Composable
private fun ColorSlider(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    onValueChange: (Float) -> Unit
) {
    Column {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(text = label, style = MaterialTheme.typography.labelMedium)
            Text(
                text = if (range.endInclusive > 1f) value.toInt().toString() else String.format("%.2f", value),
                style = MaterialTheme.typography.labelSmall
            )
        }
        Slider(
            value = value,
            onValueChange = onValueChange,
            valueRange = range,
            modifier = Modifier.fillMaxWidth()
        )
    }
}
