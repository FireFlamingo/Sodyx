package io.sodyx.app.ui.design

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.sodyx.app.R

internal object SodyxColor {
    val Background = Color(0xFF0A0B0D)
    val Surface = Color(0xFF0E1116)
    val Raised = Color(0xFF171C22)
    val Ink = Color(0xFFE9ECEF)
    val Secondary = Color(0xFF9DA3AB)
    val Accent = Color(0xFF9DD5E2)
    val Danger = Color(0xFFE5AEA7)
    val Hairline = Ink.copy(alpha = 0.09f)
    val SubtleLine = Ink.copy(alpha = 0.045f)

    // Stronger than decorative rules so interactive outlines remain discernible.
    val ControlBorder = Color(0xFF646E79)
}

internal object SodyxSpace {
    val Tiny = 4.dp
    val Small = 8.dp
    val Medium = 12.dp
    val Normal = 16.dp
    val Large = 24.dp
    val Section = 32.dp
    val Wide = 48.dp
    val Touch = 48.dp
    val ContentWidth = 560.dp
}

internal object SodyxShape {
    val Control = RoundedCornerShape(4.dp)
    val Message = RoundedCornerShape(8.dp)
}

internal object SodyxMotion {
    const val SCREEN_MILLIS = 160
}

@OptIn(ExperimentalTextApi::class)
internal object SodyxType {
    private val display = FontFamily(
        Font(
            R.font.archivo,
            FontWeight.Normal,
            variationSettings = FontVariation.Settings(
                FontVariation.weight(500),
                FontVariation.width(100f)
            )
        )
    )
    private val body = FontFamily(
        Font(
            R.font.manrope,
            FontWeight.Normal,
            variationSettings = FontVariation.Settings(FontVariation.weight(400))
        ),
        Font(
            R.font.manrope,
            FontWeight.SemiBold,
            variationSettings = FontVariation.Settings(FontVariation.weight(600))
        )
    )
    val Display =
        TextStyle(
            fontFamily = display,
            fontSize = 40.sp,
            lineHeight = 44.sp,
            letterSpacing = (-1.6).sp
        )
    val Title =
        TextStyle(
            fontFamily = display,
            fontSize = 32.sp,
            lineHeight = 38.sp,
            letterSpacing = (-1).sp
        )
    val Name =
        TextStyle(
            fontFamily = display,
            fontSize = 21.sp,
            lineHeight = 28.sp,
            letterSpacing = (-0.4).sp
        )
    val Body = TextStyle(fontFamily = body, fontSize = 15.sp, lineHeight = 23.sp)
    val Button =
        TextStyle(
            fontFamily = body,
            fontSize = 14.sp,
            lineHeight = 21.sp,
            fontWeight = FontWeight.SemiBold
        )
    val Caption = TextStyle(fontFamily = body, fontSize = 12.sp, lineHeight = 18.sp)
    val Label =
        TextStyle(fontFamily = body, fontSize = 11.sp, lineHeight = 17.sp, letterSpacing = 1.4.sp)
}
