package com.paperfeed.data

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore("settings")

data class AppSettings(
    val themeId: String = "light",
    /** Custom theme colors as ARGB ints, encoded "isDark;primary;background;surface;text". */
    val customTheme: String = "0;-12627531;-1;-722948;-14540254",
    val refreshMinutes: Int = 60,
    val notificationsEnabled: Boolean = true,
    val contactEmail: String = "",
    val driveAutoBackup: Boolean = false,
    val driveAccount: String = "",
    val lastDriveBackup: Long = 0,
    val safBackupUri: String = "",
    val lastSafBackup: Long = 0,
    val textScale: Float = 1f,
    /** Text size of the home/list rows and of the article reader, adjustable from their "Aa" buttons. */
    val feedScale: Float = 1f,
    val readerScale: Float = 1f,
    val feedStyle: FeedStyle = FeedStyle.LIST,
    /** Abstract lines shown under each title in the feed (0 = none). */
    val previewLines: Int = 0,
    val elsevierApiKey: String = "",
)

enum class FeedStyle(val label: String) { LIST("List"), COMPACT("Compact"), CARDS("Cards") }

class SettingsStore(private val context: Context) {
    private object K {
        val theme = stringPreferencesKey("theme")
        val custom = stringPreferencesKey("custom_theme")
        val refresh = intPreferencesKey("refresh_minutes")
        val notif = booleanPreferencesKey("notifications")
        val email = stringPreferencesKey("contact_email")
        val driveAuto = booleanPreferencesKey("drive_auto")
        val driveAccount = stringPreferencesKey("drive_account")
        val lastDrive = longPreferencesKey("last_drive")
        val safUri = stringPreferencesKey("saf_uri")
        val lastSaf = longPreferencesKey("last_saf")
        val textScale = intPreferencesKey("text_scale_pct")
        val feedScale = intPreferencesKey("feed_scale_pct")
        val readerScale = intPreferencesKey("reader_scale_pct")
        val feedStyle = stringPreferencesKey("feed_style")
        val previewLines = intPreferencesKey("preview_lines")
        val elsevierKey = stringPreferencesKey("elsevier_key")
    }

    val flow: Flow<AppSettings> = context.dataStore.data.map { p -> p.toSettings() }

    suspend fun current(): AppSettings = flow.first()

    private fun Preferences.toSettings() = AppSettings(
        themeId = this[K.theme] ?: "light",
        customTheme = this[K.custom] ?: AppSettings().customTheme,
        refreshMinutes = this[K.refresh] ?: 60,
        notificationsEnabled = this[K.notif] ?: true,
        contactEmail = this[K.email] ?: "",
        driveAutoBackup = this[K.driveAuto] ?: false,
        driveAccount = this[K.driveAccount] ?: "",
        lastDriveBackup = this[K.lastDrive] ?: 0,
        safBackupUri = this[K.safUri] ?: "",
        lastSafBackup = this[K.lastSaf] ?: 0,
        textScale = (this[K.textScale] ?: 100) / 100f,
        feedScale = (this[K.feedScale] ?: 100) / 100f,
        readerScale = (this[K.readerScale] ?: 100) / 100f,
        feedStyle = runCatching { FeedStyle.valueOf(this[K.feedStyle] ?: "LIST") }.getOrDefault(FeedStyle.LIST),
        previewLines = this[K.previewLines] ?: 0,
        elsevierApiKey = this[K.elsevierKey] ?: "",
    )

    suspend fun update(transform: (AppSettings) -> AppSettings) {
        context.dataStore.edit { p ->
            val s = transform(p.toSettings())
            p[K.theme] = s.themeId
            p[K.custom] = s.customTheme
            p[K.refresh] = s.refreshMinutes
            p[K.notif] = s.notificationsEnabled
            p[K.email] = s.contactEmail
            p[K.driveAuto] = s.driveAutoBackup
            p[K.driveAccount] = s.driveAccount
            p[K.lastDrive] = s.lastDriveBackup
            p[K.safUri] = s.safBackupUri
            p[K.lastSaf] = s.lastSafBackup
            p[K.textScale] = Math.round(s.textScale * 100)
            p[K.feedScale] = Math.round(s.feedScale * 100)
            p[K.readerScale] = Math.round(s.readerScale * 100)
            p[K.feedStyle] = s.feedStyle.name
            p[K.previewLines] = s.previewLines
            p[K.elsevierKey] = s.elsevierApiKey
        }
    }
}
