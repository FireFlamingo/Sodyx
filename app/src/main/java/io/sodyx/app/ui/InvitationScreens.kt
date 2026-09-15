package io.sodyx.app.ui

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.core.graphics.createBitmap
import androidx.core.graphics.set
import com.google.zxing.BarcodeFormat
import com.google.zxing.MultiFormatWriter
import io.sodyx.app.data.InvitationRow
import io.sodyx.app.data.RedemptionResult
import io.sodyx.app.ui.design.Action
import io.sodyx.app.ui.design.Copy
import io.sodyx.app.ui.design.IconAction
import io.sodyx.app.ui.design.PageTitle
import io.sodyx.app.ui.design.SodyxColor
import io.sodyx.app.ui.design.SodyxSpace
import io.sodyx.app.ui.design.SodyxType
import io.sodyx.app.ui.design.Symbol
import io.sodyx.domain.DisplayAlias
import io.sodyx.domain.InvitationPayload
import java.lang.System.currentTimeMillis
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
internal fun InvitationCreateScreen(onBack: () -> Unit, onCreate: () -> Unit, busy: Boolean) {
    LazyColumn(Modifier.fillMaxSize().padding(SodyxSpace.Large)) {
        item {
            IconAction("Back", Symbol.Back, onBack, !busy)
            PageTitle("A private invitation.", "Local prototype · nothing is sent.")
            Spacer(Modifier.height(SodyxSpace.Large))
            Copy(
                "This creates a one-time invitation that expires in ten minutes. It does not " +
                    "prove identity, authenticity, or encryption.",
                color = SodyxColor.Secondary
            )
            Spacer(Modifier.height(SodyxSpace.Large))
            Action("Create invitation", onCreate, primary = true, enabled = !busy)
        }
    }
}

@Composable
internal fun InvitationDisplayScreen(
    invitation: InvitationRow?,
    onBack: () -> Unit,
    onScan: (String) -> Unit
) {
    val payload = invitation?.payload?.encode().orEmpty()
    LazyColumn(Modifier.fillMaxSize().padding(SodyxSpace.Large)) {
        item {
            IconAction("Back", Symbol.Back, onBack)
            PageTitle("Show invitation.", "Share this representation in person.")
            Spacer(Modifier.height(SodyxSpace.Large))
            if (payload.isNotEmpty()) {
                val expires = invitation?.payload?.expiresEpochMillis ?: 0L
                val bitmap = remember(payload) { qrBitmap(payload) }
                Image(
                    bitmap.asImageBitmap(),
                    "Invitation QR representation",
                    Modifier.padding(SodyxSpace.Normal).size(240.dp)
                )
                val pseudonymLabel = invitation?.payload?.pseudonym?.value?.toString()
                    ?.replace("-", "")?.take(8)?.uppercase().orEmpty()
                Copy(
                    "Generated pseudonym: PX-$pseudonymLabel",
                    style = SodyxType.Caption
                )
                Copy(
                    "Expires ${SimpleDateFormat("HH:mm", Locale.US).format(Date(expires))}",
                    style = SodyxType.Caption,
                    color = SodyxColor.Secondary
                )
                Copy(
                    payload,
                    Modifier.semantics {
                        contentDescription = "Invitation payload"
                    },
                    SodyxType.Caption,
                    SodyxColor.Secondary
                )
            }
            Spacer(Modifier.height(SodyxSpace.Large))
            Copy(
                "Camera scanning is not connected. Use the local scan flow to test redemption.",
                color = SodyxColor.Secondary
            )
            Action("Use in local scan", { onScan(payload) }, primary = true)
        }
    }
}

@Composable
internal fun InvitationScanScreen(
    onBack: () -> Unit,
    onUse: (String) -> Unit,
    latestPayload: String?,
    busy: Boolean
) {
    var payload by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    LazyColumn(Modifier.fillMaxSize().padding(SodyxSpace.Large)) {
        item {
            IconAction("Back", Symbol.Back, onBack, !busy)
            PageTitle("Use an invitation.", "Camera scanning is not connected.")
            Spacer(Modifier.height(SodyxSpace.Large))
            latestPayload?.let {
                Action("Use latest local invitation", { payload = it }, enabled = !busy)
            }
            BasicTextField(
                payload,
                {
                    if (it.length <=
                        512
                    ) {
                        payload = it
                        error = null
                    }
                },
                Modifier.semantics {
                    contentDescription = "Invitation payload"
                },
                enabled = !busy,
                singleLine = false
            )
            error?.let { Copy(it, color = SodyxColor.Danger) }
            Action(
                "Continue",
                {
                    runCatching { InvitationPayload.parse(payload) }
                        .onSuccess { parsed ->
                            val now = currentTimeMillis()
                            if (now < parsed.createdEpochMillis) {
                                error = "Invitation is invalid or not recognized."
                            } else if (now >= parsed.expiresEpochMillis) {
                                error = "Invitation expired. Create a new one."
                            } else {
                                onUse(parsed.encode())
                            }
                        }
                        .onFailure { error = "Invitation is invalid or not recognized." }
                },
                primary = true,
                enabled =
                !busy && payload.isNotBlank()
            )
        }
    }
}

@Composable
internal fun InvitationConfirmScreen(
    payload: String,
    result: RedemptionResult?,
    onBack: () -> Unit,
    onCreate: (DisplayAlias) -> Unit,
    busy: Boolean
) {
    var alias by remember { mutableStateOf("") }
    LazyColumn(Modifier.fillMaxSize().padding(SodyxSpace.Large)) {
        item {
            IconAction("Back", Symbol.Back, onBack, !busy)
            PageTitle("Name this connection.", "The alias stays on this device.")
            Spacer(Modifier.height(SodyxSpace.Large))
            Copy("Generated pseudonym", style = SodyxType.Caption, color = SodyxColor.Secondary)
            val parsed = runCatching { InvitationPayload.parse(payload) }.getOrNull()
            if (parsed != null) {
                Copy(
                    "PX-${parsed.pseudonym.value.toString().replace("-", "").take(8).uppercase()}",
                    style = SodyxType.Name
                )
            } else {
                Copy("Invitation could not be read.", color = SodyxColor.Danger)
            }
            result?.let { Copy(redemptionText(it), color = SodyxColor.Danger) }
            BasicTextField(
                alias,
                {
                    if (it.length <=
                        64
                    ) {
                        alias = it
                    }
                },
                Modifier.fillMaxWidth().semantics {
                    contentDescription = "Connection alias"
                }.padding(vertical = SodyxSpace.Normal),
                enabled = !busy,
                singleLine = true,
                decorationBox = { field ->
                    if (alias.isEmpty()) {
                        Copy("Connection alias", color = SodyxColor.Secondary)
                    }
                    field()
                }
            )
            val validAlias = alias.trim().let { value ->
                value.isNotEmpty() && value.length <= 64 && value.none { it.isISOControl() }
            }
            Action(
                "Create relationship",
                { if (validAlias && parsed != null) onCreate(DisplayAlias(alias.trim())) },
                primary = true,
                enabled = !busy && validAlias && parsed != null
            )
        }
    }
}

private fun redemptionText(result: RedemptionResult): String = when (result) {
    is RedemptionResult.Redeemed -> "Relationship created locally."
    RedemptionResult.AlreadyRedeemed -> "Invitation already used."
    RedemptionResult.Expired -> "Invitation expired. Create a new one."
    RedemptionResult.Invalid -> "Invitation is invalid or not recognized."
}

private fun qrBitmap(payload: String): Bitmap {
    val matrix = MultiFormatWriter().encode(payload, BarcodeFormat.QR_CODE, 480, 480)
    return createBitmap(480, 480, Bitmap.Config.ARGB_8888).also { bitmap ->
        for (x in 0 until 480) {
            for (y in 0 until 480) {
                bitmap[x, y] =
                    if (matrix[x, y]) android.graphics.Color.BLACK else android.graphics.Color.WHITE
            }
        }
    }
}
