package com.rain.sdk.sample.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.rain.sdk.interfaces.RainClient

@Composable
fun WalletInfoScreen(
    innerPadding: PaddingValues,
    accessToken: String,
    rainClient: RainClient,
    onBack: () -> Unit,
    viewModel: WalletInfoViewModel = viewModel(factory = WalletInfoViewModelFactory(rainClient))
) {
    val state by viewModel.state.collectAsState()
    val context = LocalContext.current

    LaunchedEffect(Unit) {
        if (state.portalAddress.isEmpty()) {
            viewModel.fetchWalletInfo(accessToken)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(innerPadding)
            .verticalScroll(rememberScrollState())
            .padding(16.dp)
    ) {
        // Header
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            TextButton(onClick = onBack) {
                Text("← Back")
            }
            Text(
                text = "Wallet & QR",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f),
                textAlign = TextAlign.Center
            )
            Spacer(modifier = Modifier.size(48.dp))
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Loading
        if (state.isLoading) {
            Text(
                text = "Loading wallet info...",
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.fillMaxWidth(),
                textAlign = TextAlign.Center
            )
            Spacer(modifier = Modifier.height(16.dp))
        }

        // Error
        state.errorText?.let { error ->
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.errorContainer
                )
            ) {
                Text(
                    text = "Error: $error",
                    color = MaterialTheme.colorScheme.onErrorContainer,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(16.dp)
                )
            }
            Spacer(modifier = Modifier.height(16.dp))

            Button(
                onClick = { viewModel.fetchWalletInfo(accessToken) },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Retry")
            }
            Spacer(modifier = Modifier.height(16.dp))
        }

        // Portal Address (User Wallet)
        if (state.portalAddress.isNotEmpty()) {
            AddressCard(
                title = "Portal Address (User Wallet)",
                address = state.portalAddress,
                isValid = state.isAddressValid(state.portalAddress),
                qrBitmap = state.portalQrBitmap,
                onCopy = { copyToClipboard(context, state.portalAddress) }
            )
            Spacer(modifier = Modifier.height(16.dp))
        }

        // Collateral Address (Deposit)
        if (state.collateralAddress.isNotEmpty()) {
            AddressCard(
                title = "Deposit Address (Collateral)",
                address = state.collateralAddress,
                isValid = state.isAddressValid(state.collateralAddress),
                qrBitmap = state.collateralQrBitmap,
                onCopy = { copyToClipboard(context, state.collateralAddress) }
            )
        }
    }
}

@Composable
private fun AddressCard(
    title: String,
    address: String,
    isValid: Boolean,
    qrBitmap: android.graphics.Bitmap?,
    onCopy: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
        )
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Title + validation badge
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = if (isValid) "✅ Valid" else "❌ Invalid",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (isValid)
                        MaterialTheme.colorScheme.primary
                    else
                        MaterialTheme.colorScheme.error
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            // QR Code
            qrBitmap?.let { bitmap ->
                Image(
                    bitmap = bitmap.asImageBitmap(),
                    contentDescription = "$title QR Code",
                    modifier = Modifier.size(200.dp)
                )
                Spacer(modifier = Modifier.height(8.dp))
            }

            // Address text
            Text(
                text = address,
                style = MaterialTheme.typography.bodySmall,
                textAlign = TextAlign.Center,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(modifier = Modifier.height(8.dp))

            // Copy button
            OutlinedButton(
                onClick = onCopy,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("📋 Copy Address")
            }
        }
    }
}

private fun copyToClipboard(context: Context, text: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    clipboard.setPrimaryClip(ClipData.newPlainText("address", text))
    Toast.makeText(context, "Address copied!", Toast.LENGTH_SHORT).show()
}
