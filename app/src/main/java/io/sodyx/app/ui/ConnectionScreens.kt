package io.sodyx.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.selection.toggleable
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import io.sodyx.app.ui.design.Action
import io.sodyx.app.ui.design.Copy
import io.sodyx.app.ui.design.Eyebrow
import io.sodyx.app.ui.design.IconAction
import io.sodyx.app.ui.design.LineIcon
import io.sodyx.app.ui.design.PageTitle
import io.sodyx.app.ui.design.Rule
import io.sodyx.app.ui.design.SodyxColor
import io.sodyx.app.ui.design.SodyxShape
import io.sodyx.app.ui.design.SodyxSpace
import io.sodyx.app.ui.design.SodyxType
import io.sodyx.app.ui.design.Symbol
import io.sodyx.app.ui.design.TextAction

@Composable
private fun BackBar(label: String, onBack: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = SodyxSpace.Small),
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconAction("Back", Symbol.Back, onBack)
        Eyebrow(label, Modifier.padding(start = SodyxSpace.Small))
    }
}

@Composable
internal fun AddContactScreen(onBack: () -> Unit) {
    var selected by remember { mutableStateOf<String?>(null) }
    LazyColumn(Modifier.fillMaxSize().padding(horizontal = SodyxSpace.Large)) {
        item {
            BackBar("NEW CONNECTION", onBack)
            Rule()
            Spacer(Modifier.height(SodyxSpace.Wide))
            Copy(
                "An invitation.\nNot a username.",
                Modifier.semantics {
                    heading()
                },
                SodyxType.Display
            )
            Spacer(Modifier.height(SodyxSpace.Large))
            Copy(
                "Start with someone you choose. Share an invitation directly, or meet in person.",
                color = SodyxColor.Secondary
            )
            Spacer(Modifier.height(SodyxSpace.Wide))
            Eyebrow("01 / SHARE AN INVITATION")
            Spacer(Modifier.height(SodyxSpace.Normal))
            Action("Create invitation", {
                selected =
                    "Invitations will be single-use and short-lived. This preview does not create an invitation."
            }, primary = true)
            Spacer(Modifier.height(SodyxSpace.Section))
            Eyebrow("02 / CONNECT IN PERSON")
            Spacer(Modifier.height(SodyxSpace.Normal))
            Action("Scan an invitation", {
                selected =
                    "Scanning will let you connect in person. This preview does not open the camera."
            })
            Spacer(Modifier.height(SodyxSpace.Section))
            Rule()
            Copy(
                "No public directory.\nNo phone number to exchange.",
                Modifier.padding(vertical = SodyxSpace.Large),
                color = SodyxColor.Secondary
            )
        }
        selected?.let { explanation ->
            item {
                Copy(
                    explanation,
                    Modifier.fillMaxWidth().background(
                        SodyxColor.Surface
                    ).padding(SodyxSpace.Normal).semantics {
                        liveRegion =
                            LiveRegionMode.Polite
                    },
                    SodyxType.Caption,
                    SodyxColor.Accent
                )
                TextAction("Dismiss", { selected = null })
            }
        }
    }
}

@Composable
internal fun EndSessionScreen(alias: String, onCancel: () -> Unit, onConfirm: () -> Unit) {
    LazyColumn(Modifier.fillMaxSize().padding(horizontal = SodyxSpace.Large)) {
        item {
            BackBar("SESSION PREVIEW", onCancel)
            Rule()
            Spacer(Modifier.height(SodyxSpace.Wide))
            Copy("End this\nsession?", Modifier.semantics { heading() }, SodyxType.Display)
            Spacer(Modifier.height(SodyxSpace.Large))
            Copy("With $alias", style = SodyxType.Name, color = SodyxColor.Secondary)
            Spacer(Modifier.height(SodyxSpace.Wide))
            EndDetail(
                "01",
                "The conversation closes",
                "The sample messages will disappear from this view."
            )
            EndDetail("02", "Your connection stays", "Ending a session does not remove the person.")
            EndDetail(
                "03",
                "Copies can still exist",
                "Another person can keep screenshots or copies outside Sodyx."
            )
            Spacer(Modifier.height(SodyxSpace.Large))
            Copy(
                "This is a visual demonstration. It does not destroy keys, erase storage, or contact another device.",
                style = SodyxType.Caption,
                color = SodyxColor.Secondary
            )
            Spacer(Modifier.height(SodyxSpace.Large))
            Action("End sample session", onConfirm, primary = true, destructive = true)
            TextAction("Keep this session", onCancel, Modifier.fillMaxWidth())
        }
    }
}

@Composable
private fun EndDetail(number: String, title: String, detail: String) {
    Rule()
    Row(
        Modifier.padding(vertical = SodyxSpace.Normal),
        horizontalArrangement = Arrangement.spacedBy(SodyxSpace.Normal)
    ) {
        Copy(number, style = SodyxType.Caption, color = SodyxColor.Danger)
        Column(verticalArrangement = Arrangement.spacedBy(SodyxSpace.Small)) {
            Copy(title, style = SodyxType.Button)
            Copy(detail, style = SodyxType.Caption, color = SodyxColor.Secondary)
        }
    }
}

@Composable
internal fun SettingsScreen(
    samples: Boolean,
    reducedMotion: Boolean,
    onSamples: (Boolean) -> Unit,
    onMotion: (Boolean) -> Unit,
    onReset: () -> Unit
) {
    var resetNotice by remember { mutableStateOf(false) }
    LazyColumn(Modifier.fillMaxSize().padding(horizontal = SodyxSpace.Large)) {
        item {
            Spacer(Modifier.height(SodyxSpace.Large))
            Eyebrow("SODYX / PREFERENCES")
            Spacer(Modifier.height(SodyxSpace.Section))
            PageTitle("Less, by design.", "A quiet space. A smaller footprint.")
            Spacer(Modifier.height(SodyxSpace.Section))
            Eyebrow("APPEARANCE")
            Spacer(Modifier.height(SodyxSpace.Small))
            SettingDetail("Theme", "Always dark")
            SettingToggle(
                "Reduce motion",
                "Use immediate screen transitions.",
                reducedMotion,
                onMotion
            )
            Spacer(Modifier.height(SodyxSpace.Section))
            Eyebrow("PRIVACY")
            Spacer(Modifier.height(SodyxSpace.Normal))
            Copy(
                "A different identity for each connection. Conversations that can end without ending the relationship.",
                color = SodyxColor.Secondary
            )
            Spacer(Modifier.height(SodyxSpace.Normal))
            Copy(
                "These are design intentions. Encryption, identity verification, and session erasure are not available in this preview.",
                style = SodyxType.Caption,
                color = SodyxColor.Secondary
            )
            Spacer(Modifier.height(SodyxSpace.Section))
            Eyebrow("EXPLORE THE PREVIEW")
            SettingToggle(
                "Sample conversations",
                "Turn off to see an empty conversation list.",
                samples,
                onSamples
            )
            TextAction("Reset sample sessions", {
                onReset()
                resetNotice = true
            })
            if (resetNotice) {
                Copy(
                    "Sample sessions restored.",
                    Modifier.semantics {
                        liveRegion =
                            LiveRegionMode.Polite
                    },
                    SodyxType.Caption,
                    SodyxColor.Accent
                )
            }
            Spacer(Modifier.height(SodyxSpace.Large))
            Rule(subtle = true)
            Copy(
                "Sodyx · Design study 01\nArchivo & Manrope · SIL Open Font License",
                Modifier.padding(vertical = SodyxSpace.Normal),
                SodyxType.Caption,
                SodyxColor.Secondary
            )
        }
    }
}

@Composable
private fun SettingDetail(title: String, value: String) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = SodyxSpace.Large),
        horizontalArrangement = Arrangement.spacedBy(SodyxSpace.Normal)
    ) {
        Copy(title, Modifier.weight(1f), SodyxType.Button)
        Copy(value, Modifier.weight(1f), SodyxType.Body, SodyxColor.Secondary)
    }
    Rule()
}

@Composable
private fun SettingToggle(
    title: String,
    description: String,
    checked: Boolean,
    onChange: (Boolean) -> Unit
) {
    Row(
        Modifier.fillMaxWidth().toggleable(
            checked,
            role = Role.Switch,
            onValueChange = onChange
        ).padding(vertical = SodyxSpace.Large),
        horizontalArrangement = Arrangement.spacedBy(SodyxSpace.Large),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(SodyxSpace.Small)) {
            Copy(title, style = SodyxType.Button)
            Copy(description, style = SodyxType.Caption, color = SodyxColor.Secondary)
        }
        Box(
            Modifier.size(
                SodyxSpace.Large
            ).background(
                if (checked) SodyxColor.Accent else SodyxColor.Background,
                SodyxShape.Control
            ).border(SodyxSpace.Tiny / 4, SodyxColor.ControlBorder, SodyxShape.Control),
            contentAlignment = Alignment.Center
        ) {
            if (checked) LineIcon(Symbol.Check, SodyxColor.Background)
        }
    }
    Rule()
}
