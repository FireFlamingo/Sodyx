package io.sodyx.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import io.sodyx.app.ui.design.Action
import io.sodyx.app.ui.design.Copy
import io.sodyx.app.ui.design.Eyebrow
import io.sodyx.app.ui.design.IconAction
import io.sodyx.app.ui.design.PageTitle
import io.sodyx.app.ui.design.Rule
import io.sodyx.app.ui.design.SodyxColor
import io.sodyx.app.ui.design.SodyxSpace
import io.sodyx.app.ui.design.SodyxType
import io.sodyx.app.ui.design.Symbol
import io.sodyx.app.ui.design.TextAction
import io.sodyx.domain.DisplayAlias

@Composable
internal fun AddContactScreen(
    onBack: () -> Unit,
    onCreate: (DisplayAlias) -> Unit,
    onInvite: () -> Unit,
    onScan: () -> Unit,
    busy: Boolean
) {
    var alias by remember { mutableStateOf("") }
    LazyColumn(Modifier.fillMaxSize().padding(horizontal = SodyxSpace.Large)) {
        item {
            IconAction("Back", Symbol.Back, enabled = !busy, onClick = onBack)
            Rule()
            Spacer(Modifier.height(SodyxSpace.Wide))
            Copy("Create a local\nconnection.", style = SodyxType.Display)
            Spacer(Modifier.height(SodyxSpace.Large))
            Copy(
                "Create a local one-time invitation or use the test connection seam. " +
                    "Invitations are local prototypes; nothing is sent and camera scanning " +
                    "is not connected.",
                color = SodyxColor.Secondary
            )
            Spacer(Modifier.height(SodyxSpace.Wide))
            Eyebrow("LOCAL PROTOTYPE")
            Action("Create one-time invitation", onInvite, enabled = !busy)
            Action("Scan invitation", onScan, enabled = !busy)
            Spacer(Modifier.height(SodyxSpace.Normal))
            BasicTextField(
                alias,
                { if (!busy && it.length <= 64) alias = it },
                Modifier.fillMaxWidth()
                    .semantics { contentDescription = "Connection name" }
                    .padding(vertical = SodyxSpace.Normal),
                textStyle = SodyxType.Body.copy(color = SodyxColor.Ink),
                singleLine = true,
                enabled = !busy,
                decorationBox = { field ->
                    if (alias.isEmpty()) Copy("Connection name", color = SodyxColor.Secondary)
                    field()
                }
            )
            Action(
                "Create test connection",
                {
                    val value = alias.trim()
                    if (!busy && value.isNotEmpty() && value.length <= 64 &&
                        value.all { !it.isISOControl() }
                    ) {
                        onCreate(DisplayAlias(value))
                    }
                },
                primary = true,
                enabled = !busy
            )
            Spacer(Modifier.height(SodyxSpace.Large))
            TextAction("Cancel", onBack, enabled = !busy)
        }
    }
}

@Composable
internal fun EndSessionScreen(
    alias: String,
    onCancel: () -> Unit,
    onConfirm: () -> Unit,
    busy: Boolean
) {
    LazyColumn(Modifier.fillMaxSize().padding(SodyxSpace.Large)) {
        item {
            IconAction("Back", Symbol.Back, enabled = !busy, onClick = onCancel)
            Rule()
            Spacer(Modifier.height(SodyxSpace.Wide))
            Copy("End this\nsession?", style = SodyxType.Display)
            Spacer(Modifier.height(SodyxSpace.Large))
            Copy("With $alias", style = SodyxType.Name, color = SodyxColor.Secondary)
            Spacer(Modifier.height(SodyxSpace.Large))
            Copy(
                "The app logically deletes this session and its locally stored messages. " +
                    "The relationship remains available and a new local session can be started.",
                color = SodyxColor.Secondary
            )
            Spacer(Modifier.height(SodyxSpace.Large))
            Action(
                "End local session",
                onConfirm,
                primary = true,
                destructive = true,
                enabled = !busy
            )
            TextAction("Keep this session", onCancel, enabled = !busy)
        }
    }
}

@Composable
internal fun SettingsScreen(reducedMotion: Boolean, onMotion: (Boolean) -> Unit) {
    LazyColumn(Modifier.fillMaxSize().padding(SodyxSpace.Large)) {
        item {
            Spacer(Modifier.height(SodyxSpace.Large))
            Eyebrow("SODYX / PREFERENCES")
            Spacer(Modifier.height(SodyxSpace.Section))
            PageTitle("Less, by design.", "A quiet local prototype.")
            Spacer(Modifier.height(SodyxSpace.Section))
            SettingToggle(
                "Reduce motion",
                "Use immediate screen transitions.",
                reducedMotion,
                onMotion
            )
            Spacer(Modifier.height(SodyxSpace.Section))
            Copy(
                "Local prototype data is stored unencrypted on this device. " +
                    "Invitations and QR representations are local prototypes. No network " +
                    "transport or camera scanning is connected.",
                color = SodyxColor.Secondary
            )
        }
    }
}

@Composable
private fun SettingToggle(
    title: String,
    description: String,
    checked: Boolean,
    onChange: (Boolean) -> Unit
) {
    Row(
        Modifier
            .fillMaxWidth()
            .toggleable(value = checked, role = Role.Switch, onValueChange = onChange)
            .padding(vertical = SodyxSpace.Large)
    ) {
        Column(Modifier.weight(1f)) {
            Copy(title, style = SodyxType.Button)
            Copy(description, style = SodyxType.Caption, color = SodyxColor.Secondary)
        }
        Copy(
            if (checked) "ON" else "OFF",
            style = SodyxType.Caption,
            color = SodyxColor.Accent
        )
    }
}
