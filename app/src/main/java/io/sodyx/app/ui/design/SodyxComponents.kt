package io.sodyx.app.ui.design

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign

@Composable
internal fun Copy(
    text: String,
    modifier: Modifier = Modifier,
    style: TextStyle = SodyxType.Body,
    color: Color = SodyxColor.Ink
) {
    BasicText(text, modifier, style = style.copy(color = color))
}

@Composable
internal fun Rule(subtle: Boolean = false) {
    Box(
        Modifier.fillMaxWidth().height(
            SodyxSpace.Tiny / 4
        ).background(if (subtle) SodyxColor.SubtleLine else SodyxColor.Hairline)
    )
}

@Composable
internal fun Eyebrow(text: String, modifier: Modifier = Modifier) {
    Copy(text, modifier, SodyxType.Label, SodyxColor.Secondary)
}

@Composable
internal fun PageTitle(title: String, subtitle: String) {
    Column(verticalArrangement = Arrangement.spacedBy(SodyxSpace.Medium)) {
        Copy(title, Modifier.semantics { heading() }, SodyxType.Title)
        Copy(subtitle, color = SodyxColor.Secondary)
    }
}

internal enum class Symbol { Back, Plus, Arrow, Close, Check, Settings, Conversations }

@Composable
internal fun LineIcon(symbol: Symbol, color: Color = SodyxColor.Ink) {
    Canvas(Modifier.size(SodyxSpace.Large)) {
        fun line(x1: Float, y1: Float, x2: Float, y2: Float) {
            drawLine(
                color,
                Offset(size.width * x1, size.height * y1),
                Offset(
                    size.width * x2,
                    size.height * y2
                ),
                strokeWidth = size.width / 16,
                cap = StrokeCap.Square
            )
        }
        when (symbol) {
            Symbol.Plus -> {
                line(.2f, .5f, .8f, .5f)
                line(.5f, .2f, .5f, .8f)
            }
            Symbol.Back -> {
                line(.2f, .5f, .8f, .5f)
                line(.2f, .5f, .45f, .25f)
                line(.2f, .5f, .45f, .75f)
            }
            Symbol.Arrow -> {
                line(.2f, .5f, .8f, .5f)
                line(.8f, .5f, .55f, .25f)
                line(.8f, .5f, .55f, .75f)
            }
            Symbol.Close -> {
                line(.25f, .25f, .75f, .75f)
                line(.25f, .75f, .75f, .25f)
            }
            Symbol.Check -> {
                line(.25f, .5f, .43f, .68f)
                line(.43f, .68f, .75f, .32f)
            }
            Symbol.Settings -> {
                line(.15f, .3f, .85f, .3f)
                line(.15f, .7f, .85f, .7f)
                line(.35f, .15f, .35f, .45f)
                line(.65f, .55f, .65f, .85f)
            }
            Symbol.Conversations -> {
                line(.15f, .2f, .85f, .2f)
                line(.85f, .2f, .85f, .7f)
                line(.85f, .7f, .4f, .7f)
                line(.4f, .7f, .15f, .85f)
                line(.15f, .85f, .15f, .2f)
            }
        }
    }
}

@Composable
internal fun IconAction(
    label: String,
    symbol: Symbol,
    onClick: () -> Unit,
    enabled: Boolean = true
) {
    Box(
        Modifier.size(SodyxSpace.Touch).clip(SodyxShape.Control)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center
    ) { LineIcon(symbol) }
}

@Composable
internal fun Action(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    primary: Boolean = false,
    destructive: Boolean = false,
    enabled: Boolean = true
) {
    val tint = if (destructive) SodyxColor.Danger else SodyxColor.Accent
    Row(
        modifier.fillMaxWidth().defaultMinSize(minHeight = SodyxSpace.Touch)
            .clip(SodyxShape.Control)
            .background(if (primary) tint else Color.Transparent)
            .border(
                SodyxSpace.Tiny / 4,
                if (primary) tint else SodyxColor.ControlBorder,
                SodyxShape.Control
            )
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = SodyxSpace.Normal, vertical = SodyxSpace.Medium),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Copy(
            label,
            Modifier.weight(1f),
            SodyxType.Button,
            if (primary) SodyxColor.Background else tint
        )
        LineIcon(Symbol.Arrow, if (primary) SodyxColor.Background else tint)
    }
}

@Composable
internal fun TextAction(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true
) {
    Box(
        modifier.defaultMinSize(
            minHeight = SodyxSpace.Touch
        ).clickable(
            enabled = enabled,
            role = Role.Button,
            onClick = onClick
        ).padding(vertical = SodyxSpace.Medium),
        contentAlignment = Alignment.CenterStart
    ) {
        Copy(label, style = SodyxType.Button, color = SodyxColor.Accent)
    }
}

@Composable
internal fun PreviewNote() {
    Copy(
        "Local prototype · Stored unencrypted · Nothing is sent",
        Modifier.fillMaxWidth().padding(vertical = SodyxSpace.Small),
        SodyxType.Caption.copy(textAlign = TextAlign.Center),
        SodyxColor.Secondary
    )
}

@Composable
internal fun QuietEmpty(title: String, description: String, action: String, onAction: () -> Unit) {
    Column(
        Modifier.fillMaxWidth().padding(vertical = SodyxSpace.Section),
        verticalArrangement = Arrangement.spacedBy(SodyxSpace.Normal)
    ) {
        Box(
            Modifier.size(SodyxSpace.Wide).border(SodyxSpace.Tiny / 4, SodyxColor.Hairline),
            contentAlignment = Alignment.Center
        ) {
            LineIcon(Symbol.Conversations, SodyxColor.Accent)
        }
        Spacer(Modifier.height(SodyxSpace.Small))
        Copy(title, Modifier.semantics { heading() }, SodyxType.Title)
        Copy(description, color = SodyxColor.Secondary)
        TextAction(action, onAction)
    }
}
