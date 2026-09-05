package com.rain.sdk.sample.screens

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.rain.sdk.RainSdk
import com.rain.sdk.interfaces.RainClient
import com.rain.sdk.sample.R
import com.rain.sdk.sample.WalletChain
import com.rain.sdk.sample.ui.RainBackHeader
import com.rain.sdk.sample.ui.RainBadge
import com.rain.sdk.sample.ui.RainBadgeTone
import com.rain.sdk.sample.ui.RainButton
import com.rain.sdk.sample.ui.RainButtonStyle
import com.rain.sdk.sample.ui.RainCard
import com.rain.sdk.sample.ui.RainErrorPanel
import com.rain.sdk.sample.ui.RainMuted
import com.rain.sdk.sample.ui.RainPanel
import com.rain.sdk.sample.ui.RainRow
import com.rain.sdk.sample.ui.RainScreen
import com.rain.sdk.sample.ui.RainStrong
import com.rain.sdk.sample.ui.RainTitleBlock

@Composable
fun WalletInfoScreen(
    innerPadding: PaddingValues,
    rainSdk: RainSdk,
    rainClient: RainClient,
    selectedChain: WalletChain,
    onBack: () -> Unit,
    viewModel: WalletInfoViewModel = viewModel(factory = WalletInfoViewModelFactory(rainSdk, rainClient))
) {
    val state by viewModel.state.collectAsState()
    val context = LocalContext.current

    // Re-fetch whenever the active chain changes so the screen shows that wallet's address.
    LaunchedEffect(selectedChain) {
        viewModel.fetchWalletInfo(selectedChain)
    }

    RainScreen(innerPadding) {
        RainBackHeader(onBack = onBack)
        RainTitleBlock(title = "Wallet & QR", subtitle = selectedChain.displayName)

        if (state.isLoading) {
            RainPanel { RainMuted("Loading wallet info…") }
        }

        state.errorText?.let { error ->
            RainErrorPanel(error)
            RainButton(
                text = "Retry",
                onClick = { viewModel.fetchWalletInfo(selectedChain) },
                modifier = Modifier.fillMaxWidth(),
                style = RainButtonStyle.Secondary,
            )
        }

        // User wallet address (provider-agnostic: Portal, Turnkey or Privy).
        if (state.portalAddress.isNotEmpty()) {
            AddressCard(
                title = "Wallet address",
                subtitle = null,
                address = state.portalAddress,
                isValid = selectedChain.isValidAddress(state.portalAddress),
                qrBitmap = state.portalQrBitmap,
                onCopy = { copyToClipboard(context, "Wallet address", state.portalAddress, "Address copied") },
            )
        }

        // Where deposits go: the collateral contract (or its dedicated deposit address).
        if (state.collateralAddress.isNotEmpty()) {
            AddressCard(
                title = "Deposit address",
                subtitle = "Collateral contract",
                address = state.collateralAddress,
                isValid = selectedChain.isValidAddress(state.collateralAddress),
                qrBitmap = state.collateralQrBitmap,
                onCopy = { copyToClipboard(context, "Deposit address", state.collateralAddress, "Address copied") },
            )
        }
    }
}

@Composable
private fun AddressCard(
    title: String,
    subtitle: String?,
    address: String,
    isValid: Boolean,
    qrBitmap: Bitmap?,
    onCopy: () -> Unit,
) {
    RainCard {
        RainRow {
            Column(modifier = Modifier.weight(1f)) {
                RainStrong(title)
                if (subtitle != null) RainMuted(subtitle)
            }
            RainBadge(
                text = if (isValid) "Valid" else "Invalid",
                tone = if (isValid) RainBadgeTone.Success else RainBadgeTone.Danger,
            )
        }

        qrBitmap?.let { bitmap ->
            Box(
                modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                contentAlignment = Alignment.Center,
            ) {
                Image(
                    bitmap = bitmap.asImageBitmap(),
                    contentDescription = "$title QR code",
                    modifier = Modifier.size(200.dp),
                )
            }
        }

        RainMuted(
            text = address,
            modifier = Modifier.fillMaxWidth(),
            textAlign = if (qrBitmap != null) TextAlign.Center else TextAlign.Start,
        )

        RainButton(
            text = "Copy address",
            onClick = onCopy,
            modifier = Modifier.fillMaxWidth(),
            style = RainButtonStyle.Secondary,
            icon = R.drawable.ic_copy,
        )
    }
}
