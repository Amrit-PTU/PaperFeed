package com.paperfeed.ui

import android.app.Activity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Favorite
import androidx.compose.material.icons.outlined.Paid
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.StarRate
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.paperfeed.BuildConfig
import com.paperfeed.support.TipJar

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SupportScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val tipJar = remember { TipJar(context) }
    DisposableEffect(Unit) {
        tipJar.start()
        onDispose { tipJar.end() }
    }
    val state by tipJar.state.collectAsState()
    val thanks by tipJar.thanks.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Support PaperFeed") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Back") } },
            )
        },
    ) { padding ->
        Column(
            Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(Icons.Outlined.Favorite, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(56.dp))
            Text(
                if (thanks) "Thank you so much! 🎉" else "PaperFeed is free and has no ads.",
                style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center,
            )
            Text(
                "If it saves you time keeping up with the literature, a small tip helps keep it maintained.",
                style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            when (val s = state) {
                TipJar.State.Loading -> CircularProgressIndicator()
                is TipJar.State.Ready -> s.products.forEach { p ->
                    Button(
                        onClick = { (context as? Activity)?.let { tipJar.buy(it, p) } },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Icon(Icons.Outlined.Paid, null)
                        Text(
                            "  ${p.name.ifBlank { p.title }}  ·  ${p.oneTimePurchaseOfferDetails?.formattedPrice ?: ""}",
                        )
                    }
                }
                TipJar.State.Unavailable -> if (BuildConfig.DONATE_URL.isBlank()) {
                    Text(
                        "In-app tips become available once the app is installed from Google Play.",
                        style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            // External donation link: only for builds distributed outside Google Play (see PUBLISHING.md).
            if (BuildConfig.DONATE_URL.isNotBlank()) {
                Button(onClick = { openUrl(context, BuildConfig.DONATE_URL) }, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Outlined.Paid, null)
                    Text("  Donate")
                }
            }
            Spacer(Modifier.height(16.dp))
            Text("Free ways to help", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = {
                    openUrl(context, "https://play.google.com/store/apps/details?id=${context.packageName}")
                }) {
                    Icon(Icons.Outlined.StarRate, null)
                    Text(" Rate")
                }
                OutlinedButton(onClick = {
                    shareText(
                        context, "PaperFeed",
                        "PaperFeed – follow new papers from your journals and arXiv: https://play.google.com/store/apps/details?id=${context.packageName}",
                        "Tell a colleague",
                    )
                }) {
                    Icon(Icons.Outlined.Share, null)
                    Text(" Tell a colleague")
                }
            }
        }
    }
}
