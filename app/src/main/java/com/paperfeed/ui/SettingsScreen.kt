package com.paperfeed.ui

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ChromeReaderMode
import androidx.compose.material.icons.outlined.Backup
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material.icons.outlined.Paid
import androidx.compose.material.icons.outlined.ViewAgenda
import androidx.compose.material.icons.outlined.CloudDownload
import androidx.compose.material.icons.outlined.CloudUpload
import androidx.compose.material.icons.outlined.Email
import androidx.compose.material.icons.outlined.FileDownload
import androidx.compose.material.icons.outlined.FileUpload
import androidx.compose.material.icons.outlined.FormatSize
import androidx.compose.material.icons.outlined.Menu
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.google.android.gms.auth.api.identity.Identity
import com.paperfeed.BuildConfig
import com.paperfeed.PaperFeedApp
import com.paperfeed.backup.DriveBackup
import com.paperfeed.data.AppSettings
import com.paperfeed.work.RefreshScheduler
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.time.LocalDate
import java.util.Date
import kotlin.math.roundToInt

private val intervals = listOf(15 to "Every 15 minutes", 30 to "Every 30 minutes", 60 to "Every hour", 180 to "Every 3 hours",
    360 to "Every 6 hours", 720 to "Every 12 hours", 1440 to "Once a day", 0 to "Manually only")

private enum class DriveOp { BACKUP, RESTORE, ENABLE_AUTO }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(onOpenDrawer: () -> Unit, onThemes: () -> Unit, onSupport: () -> Unit) {
    val context = LocalContext.current
    val app = PaperFeedApp.instance
    val scope = rememberCoroutineScope()
    val s by repo.settings.flow.collectAsState(initial = AppSettings())
    var intervalDialog by remember { mutableStateOf(false) }
    var emailDialog by remember { mutableStateOf(false) }
    var elsevierDialog by remember { mutableStateOf(false) }
    var sizeSheet by remember { mutableStateOf<Boolean?>(null) }
    var confirmRestore by remember { mutableStateOf<(() -> Unit)?>(null) }
    var busy by remember { mutableStateOf<String?>(null) }
    var pendingOp by remember { mutableStateOf<DriveOp?>(null) }

    fun toast(msg: String) = Toast.makeText(context, msg, Toast.LENGTH_LONG).show()

    fun runDrive(op: DriveOp, token: String) {
        scope.launch {
            busy = "Talking to Google Drive…"
            runCatching {
                when (op) {
                    DriveOp.BACKUP -> { app.backup.backupToDrive(token); "Backed up to Google Drive (${DriveBackup.FILE_NAME})" }
                    DriveOp.RESTORE -> if (app.backup.restoreFromDrive(token)) "Restored from Google Drive" else "No backup found on Google Drive yet"
                    DriveOp.ENABLE_AUTO -> {
                        repo.settings.update { it.copy(driveAutoBackup = true) }
                        app.backup.backupToDrive(token)
                        "Daily Google Drive backup enabled"
                    }
                }
            }.onSuccess { toast(it) }.onFailure { toast("Drive: ${it.message}") }
            busy = null
        }
    }

    val authLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { res ->
        val op = pendingOp ?: return@rememberLauncherForActivityResult
        pendingOp = null
        if (res.resultCode != Activity.RESULT_OK) { toast("Google Drive access was not granted"); return@rememberLauncherForActivityResult }
        val token = runCatching {
            Identity.getAuthorizationClient(context).getAuthorizationResultFromIntent(res.data).accessToken
        }.getOrNull()
        if (token == null) toast("Google Drive authorization failed") else runDrive(op, token)
    }

    fun withDrive(op: DriveOp) {
        scope.launch {
            runCatching { DriveBackup.authorize(context) }
                .onSuccess { result ->
                    val pi = result.pendingIntent
                    if (result.hasResolution() && pi != null) {
                        pendingOp = op
                        authLauncher.launch(IntentSenderRequest.Builder(pi.intentSender).build())
                    } else {
                        result.accessToken?.let { runDrive(op, it) } ?: toast("Google Drive authorization failed")
                    }
                }
                .onFailure {
                    toast("Google sign-in unavailable: ${it.message}. See README → Google Drive setup.")
                }
        }
    }

    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) scope.launch {
            runCatching { app.backup.writeToUri(uri) }.onSuccess { toast("Backup saved") }.onFailure { toast("Export failed: ${it.message}") }
        }
    }
    val autoFileLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) scope.launch {
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
                )
                repo.settings.update { it.copy(safBackupUri = uri.toString()) }
                app.backup.writeToUri(uri)
            }.onSuccess { toast("Daily backup to this file enabled") }.onFailure { toast("Couldn't use that file: ${it.message}") }
        }
    }
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) confirmRestore = {
            scope.launch {
                runCatching { app.backup.readFromUri(uri) }.onSuccess { toast("Backup restored") }.onFailure { toast("Import failed: ${it.message}") }
            }
        }
    }
    val notifPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        scope.launch { repo.settings.update { it.copy(notificationsEnabled = granted) } }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = { IconButton(onClick = onOpenDrawer) { Icon(Icons.Outlined.Menu, "Menu") } },
            )
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState())) {
            SectionHeader("Appearance")
            val themeName = when (s.themeId) {
                Themes.SYSTEM -> "Follow system"
                Themes.DYNAMIC -> "Material You"
                Themes.CUSTOM -> "Custom"
                else -> Themes.presets.firstOrNull { it.id == s.themeId }?.name ?: "Paper"
            }
            Pref(Icons.Outlined.Palette, "Theme", themeName, onClick = onThemes)
            var scale by remember(s.textScale) { mutableFloatStateOf(s.textScale) }
            ListItem(
                leadingContent = { Icon(Icons.Outlined.FormatSize, null) },
                headlineContent = { Text("Text size  ·  ${(scale * 100).toInt()}%") },
                supportingContent = {
                    Slider(
                        value = scale, onValueChange = { scale = it }, valueRange = 0.85f..1.3f, steps = 8,
                        onValueChangeFinished = { scope.launch { repo.settings.update { it.copy(textScale = scale) } } },
                    )
                },
            )

            Pref(
                Icons.Outlined.ViewAgenda, "Article list: size & layout",
                "${s.feedStyle.label} · ${(s.feedScale * 100).roundToInt()}% · preview ${if (s.previewLines == 0) "off" else "${s.previewLines} lines"}",
            ) { sizeSheet = true }
            Pref(Icons.AutoMirrored.Outlined.ChromeReaderMode, "Reading text size", "${(s.readerScale * 100).roundToInt()}% in articles and notes") { sizeSheet = false }

            SectionHeader("Notifications")
            PrefSwitch(Icons.Outlined.Notifications, "New article notifications", "Title, and the abstract when expanded", s.notificationsEnabled) { on ->
                if (on && Build.VERSION.SDK_INT >= 33) notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                else scope.launch { repo.settings.update { it.copy(notificationsEnabled = on) } }
            }
            Pref(Icons.Outlined.Schedule, "Check for new articles", intervals.firstOrNull { it.first == s.refreshMinutes }?.second ?: "Every hour") {
                intervalDialog = true
            }

            SectionHeader("Google Drive backup")
            Text(
                "Backs up your lists, ratings, saved articles, sources and theme to “${DriveBackup.FILE_NAME}” in your Drive." +
                    if (s.lastDriveBackup > 0) "\nLast backup: " + DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(s.lastDriveBackup)) else "",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
            Pref(Icons.Outlined.CloudUpload, "Back up now", null) { withDrive(DriveOp.BACKUP) }
            Pref(Icons.Outlined.CloudDownload, "Restore from Drive", "Replaces your lists with the Drive copy") {
                confirmRestore = { withDrive(DriveOp.RESTORE) }
            }
            PrefSwitch(Icons.Outlined.Backup, "Automatic daily backup", "Runs in the background with the article check", s.driveAutoBackup) { on ->
                if (on) withDrive(DriveOp.ENABLE_AUTO) else scope.launch { repo.settings.update { it.copy(driveAutoBackup = false) } }
            }

            SectionHeader("File backup")
            Text(
                "Works without a Google account setup: pick “Drive” in the file picker to store the file in Google Drive.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
            Pref(Icons.Outlined.FileUpload, "Export backup file…", null) {
                exportLauncher.launch("PaperFeed-backup-${LocalDate.now()}.json")
            }
            Pref(Icons.Outlined.FileDownload, "Import backup file…", null) {
                importLauncher.launch(arrayOf("application/json", "application/octet-stream", "text/plain"))
            }
            PrefSwitch(
                Icons.Outlined.Backup, "Daily backup to a file",
                if (s.safBackupUri.isNotBlank()) "Last written: " + (if (s.lastSafBackup > 0) DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(s.lastSafBackup)) else "never")
                else "Choose a file once; it is overwritten daily",
                s.safBackupUri.isNotBlank(),
            ) { on ->
                if (on) autoFileLauncher.launch("PaperFeed-backup.json")
                else scope.launch { repo.settings.update { it.copy(safBackupUri = "") } }
            }

            SectionHeader("Network")
            Pref(
                Icons.Outlined.Email, "Contact email for APIs",
                s.contactEmail.ifBlank { "Optional. Sent to Crossref/OpenAlex for faster, more reliable access" },
            ) { emailDialog = true }

            Pref(
                Icons.Outlined.Key, "Elsevier API key",
                if (s.elsevierApiKey.isBlank()) "Optional. Lets the app fetch abstracts for Nucl. Phys. A, Phys. Lett. B, PPNP, Ann. Nucl. Energy automatically"
                else "Set · used for Elsevier abstracts",
            ) { elsevierDialog = true }

            SectionHeader("Support")
            Pref(Icons.Outlined.Paid, "Support development", "Leave a tip, rate the app, or tell a colleague", onClick = onSupport)

            SectionHeader("About")
            Text(
                "PaperFeed ${BuildConfig.VERSION_NAME}\nMetadata from Crossref, OpenAlex, arXiv, Semantic Scholar and publisher RSS feeds.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(16.dp),
            )
        }
    }

    sizeSheet?.let { feed -> TextSizeSheet(forFeed = feed, onDismiss = { sizeSheet = null }) }
    if (elsevierDialog) {
        var key by remember { mutableStateOf(s.elsevierApiKey) }
        AlertDialog(
            onDismissRequest = { elsevierDialog = false },
            title = { Text("Elsevier API key") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "Elsevier journals don't publish abstracts in their feeds. A free key from dev.elsevier.com " +
                            "(“I want an API key”, any app name) lets PaperFeed fetch them in the background. " +
                            "Without it, use “Fetch abstract” on an article to read it from the publisher page.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    TextButton(onClick = { openUrl(context, "https://dev.elsevier.com/apikey/manage") }) { Text("Get a free key") }
                    OutlinedTextField(key, { key = it }, singleLine = true, label = { Text("API key") })
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    elsevierDialog = false
                    scope.launch {
                        repo.settings.update { it.copy(elsevierApiKey = key.trim()) }
                        if (key.isNotBlank()) app.appScope.launch { repo.enrichPending(40) }
                    }
                }) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = { elsevierDialog = false }) { Text("Cancel") } },
        )
    }
    busy?.let {
        AlertDialog(onDismissRequest = {}, confirmButton = {}, title = { Text("Please wait") }, text = { Text(it) })
    }
    confirmRestore?.let { action ->
        AlertDialog(
            onDismissRequest = { confirmRestore = null },
            title = { Text("Restore backup?") },
            text = { Text("Your current lists will be replaced with the ones in the backup. Ratings and saved articles from the backup are merged in.") },
            confirmButton = { TextButton(onClick = { confirmRestore = null; action() }) { Text("Restore") } },
            dismissButton = { TextButton(onClick = { confirmRestore = null }) { Text("Cancel") } },
        )
    }
    if (intervalDialog) {
        AlertDialog(
            onDismissRequest = { intervalDialog = false },
            title = { Text("Check for new articles") },
            text = {
                Column {
                    intervals.forEach { (m, label) ->
                        Row(
                            Modifier.fillMaxWidth().clickable {
                                intervalDialog = false
                                scope.launch { repo.settings.update { it.copy(refreshMinutes = m) } }
                                RefreshScheduler.schedule(context, m)
                            },
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(selected = s.refreshMinutes == m, onClick = null, modifier = Modifier.padding(12.dp))
                            Text(label)
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { intervalDialog = false }) { Text("Close") } },
        )
    }
    if (emailDialog) {
        var email by remember { mutableStateOf(s.contactEmail) }
        AlertDialog(
            onDismissRequest = { emailDialog = false },
            title = { Text("Contact email") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Crossref and OpenAlex give faster, more reliable service to requests that include a contact address. Leave empty to not send one.",
                        style = MaterialTheme.typography.bodySmall)
                    OutlinedTextField(email, { email = it }, singleLine = true, label = { Text("Email") })
                }
            },
            confirmButton = {
                TextButton(onClick = { emailDialog = false; scope.launch { repo.settings.update { it.copy(contactEmail = email.trim()) } } }) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = { emailDialog = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun Pref(icon: ImageVector, title: String, summary: String?, onClick: () -> Unit) {
    ListItem(
        modifier = Modifier.clickable(onClick = onClick),
        leadingContent = { Icon(icon, null) },
        headlineContent = { Text(title) },
        supportingContent = summary?.let { { Text(it) } },
    )
}

@Composable
private fun PrefSwitch(icon: ImageVector, title: String, summary: String?, checked: Boolean, onChange: (Boolean) -> Unit) {
    ListItem(
        modifier = Modifier.clickable { onChange(!checked) },
        leadingContent = { Icon(icon, null) },
        headlineContent = { Text(title) },
        supportingContent = summary?.let { { Text(it) } },
        trailingContent = { Switch(checked = checked, onCheckedChange = onChange) },
    )
}
