package com.rain.sdk.sample.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import com.rain.sdk.sample.R
import com.rain.sdk.sample.ui.RainBadge
import com.rain.sdk.sample.ui.RainBadgeTone
import com.rain.sdk.sample.ui.RainButton
import com.rain.sdk.sample.ui.RainButtonStyle
import com.rain.sdk.sample.ui.RainCard
import com.rain.sdk.sample.ui.RainIconButton
import com.rain.sdk.sample.ui.RainLabel
import com.rain.sdk.sample.ui.RainLink
import com.rain.sdk.sample.ui.RainMuted
import com.rain.sdk.sample.ui.RainRow
import com.rain.sdk.sample.ui.RainStrong
import com.rain.sdk.sample.ui.theme.RainType
import java.math.BigDecimal
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.util.Locale

/*
 * Formatting and small building blocks shared by the feature screens.
 */

private const val ADDRESS_HEAD = 6
private const val ADDRESS_TAIL = 4
private const val HASH_HEAD = 8
private const val HASH_TAIL = 6

/** `0x3cA8…C0Ff` — six leading and four trailing characters, as the design shows addresses. */
fun shortAddress(address: String): String = elide(address, ADDRESS_HEAD, ADDRESS_TAIL)

/** `0x7d2f9b…a41c3e` — eight leading and six trailing characters. */
fun shortHash(hash: String): String = elide(hash, HASH_HEAD, HASH_TAIL)

/** Keeps [head] and [tail] characters around an ellipsis; strings that would not shrink stay whole. */
private fun elide(value: String, head: Int, tail: Int): String =
    if (value.length <= head + tail + 2) value else "${value.take(head)}…${value.takeLast(tail)}"

private val moneyFormat = DecimalFormat("#,##0.00", DecimalFormatSymbols(Locale.US))

/** Two-decimal, grouped money figure for stablecoin balances: `1,250.00`. */
fun formatMoney(value: BigDecimal): String = moneyFormat.format(value)

/**
 * Grouped balance that keeps the token's full precision but never fewer than two decimals:
 * `1,250.00`, `250.00`, `0.4821`. Trailing zeros beyond the second decimal are dropped.
 */
fun formatBalance(value: BigDecimal): String {
    val stripped = value.stripTrailingZeros()
    val format = DecimalFormat("#,##0.00", DecimalFormatSymbols(Locale.US))
    format.minimumFractionDigits = 2
    format.maximumFractionDigits = maxOf(2, stripped.scale())
    return format.format(stripped)
}

/** A plain decimal with trailing zeros stripped and no scientific notation. */
fun formatPlain(value: BigDecimal): String =
    if (value.signum() == 0) "0" else value.stripTrailingZeros().toPlainString()

fun openUrl(context: Context, url: String) {
    context.startActivity(Intent(Intent.ACTION_VIEW, url.toUri()))
}

fun copyToClipboard(context: Context, label: String, text: String, toast: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    clipboard.setPrimaryClip(ClipData.newPlainText(label, text))
    Toast.makeText(context, toast, Toast.LENGTH_SHORT).show()
}

/**
 * Outcome card for a broadcast transaction: status, the hash as an explorer link (or plain text
 * where the chain has no resolvable explorer entry), a copy affordance, and an explorer button.
 */
@Suppress("LongParameterList") // Slot-style Compose API: every extra parameter is an optional knob.
@Composable
fun TransactionResultCard(
    title: String,
    hash: String,
    explorerUrl: String?,
    explorerName: String,
    badge: String = "Submitted",
    badgeTone: RainBadgeTone = RainBadgeTone.Success,
    note: String? = null,
) {
    val context = LocalContext.current
    RainCard {
        RainRow {
            RainStrong(title, Modifier.weight(1f))
            RainBadge(badge, tone = badgeTone)
        }
        if (note != null) RainMuted(note)
        Column {
            RainLabel("Transaction hash")
            RainRow {
                if (explorerUrl != null) {
                    RainLink(
                        shortHash(hash),
                        onClick = { openUrl(context, explorerUrl) },
                        modifier = Modifier.weight(1f),
                        maxLines = 1
                    )
                } else {
                    Text(shortHash(hash), style = RainType.Body, modifier = Modifier.weight(1f), maxLines = 1)
                }
                RainIconButton(
                    icon = R.drawable.ic_copy,
                    contentDescription = "Copy transaction hash",
                    onClick = { copyToClipboard(context, "Transaction hash", hash, "Transaction hash copied") },
                    iconSize = 20.dp,
                )
            }
        }
        if (explorerUrl != null) {
            RainButton(
                text = "View on $explorerName",
                onClick = { openUrl(context, explorerUrl) },
                modifier = Modifier.fillMaxWidth(),
                style = RainButtonStyle.Secondary,
                icon = R.drawable.ic_arrow_up_right,
            )
        }
    }
}
