package com.paperfeed.backup

import android.content.Context
import android.net.Uri
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.AuthorizationResult
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.Scope
import com.paperfeed.data.Repository
import com.paperfeed.net.Http
import com.paperfeed.net.HttpException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

/**
 * Google Drive backup through the Drive REST API. Uses the narrow `drive.file` scope:
 * the app can only see the backup file it created, which shows up in "My Drive".
 */
object DriveBackup {
    private const val SCOPE = "https://www.googleapis.com/auth/drive.file"
    const val FILE_NAME = "PaperFeed-backup.json"
    private val json = "application/json; charset=UTF-8".toMediaType()

    val request: AuthorizationRequest =
        AuthorizationRequest.builder().setRequestedScopes(listOf(Scope(SCOPE))).build()

    /** May return a result that needs a UI resolution (consent screen). */
    suspend fun authorize(context: Context): AuthorizationResult =
        Identity.getAuthorizationClient(context).authorize(request).await()

    /** Access token without any UI, or null if the user must (re)grant access in the app. */
    suspend fun silentToken(context: Context): String? =
        runCatching { authorize(context) }.getOrNull()?.takeIf { !it.hasResolution() }?.accessToken

    private fun Request.Builder.auth(token: String) = header("Authorization", "Bearer $token")

    private suspend fun call(req: Request): String = withContext(Dispatchers.IO) {
        Http.client.newCall(req).execute().use { r ->
            val body = r.body.string()
            if (!r.isSuccessful) throw HttpException(r.code, "Drive error ${r.code}: ${body.take(200)}")
            body
        }
    }

    private suspend fun findFileId(token: String): String? {
        val q = Http.enc("name='$FILE_NAME' and trashed=false")
        val body = call(
            Request.Builder()
                .url("https://www.googleapis.com/drive/v3/files?q=$q&spaces=drive&orderBy=modifiedTime%20desc&fields=files(id,modifiedTime)")
                .auth(token).build()
        )
        return JSONObject(body).optJSONArray("files")?.optJSONObject(0)?.optString("id")?.ifBlank { null }
    }

    suspend fun upload(token: String, content: String) {
        val id = findFileId(token)
        if (id == null) {
            val meta = JSONObject().put("name", FILE_NAME).put("mimeType", "application/json").toString()
            val body = MultipartBody.Builder()
                .setType("multipart/related".toMediaType())
                .addPart(meta.toRequestBody(json))
                .addPart(content.toRequestBody(json))
                .build()
            call(
                Request.Builder().url("https://www.googleapis.com/upload/drive/v3/files?uploadType=multipart")
                    .auth(token).post(body).build()
            )
        } else {
            call(
                Request.Builder().url("https://www.googleapis.com/upload/drive/v3/files/$id?uploadType=media")
                    .auth(token).patch(content.toRequestBody(json)).build()
            )
        }
    }

    suspend fun download(token: String): String? {
        val id = findFileId(token) ?: return null
        return call(Request.Builder().url("https://www.googleapis.com/drive/v3/files/$id?alt=media").auth(token).build())
    }
}

/** Shared backup/restore entry points used by the settings screen and the auto-backup worker. */
class BackupManager(private val context: Context, private val repo: Repository) {

    suspend fun exportJson(): String = BackupJson.encode(repo.snapshot())

    suspend fun importJson(json: String) = repo.restore(BackupJson.decode(json))

    suspend fun backupToDrive(token: String) {
        DriveBackup.upload(token, exportJson())
        repo.settings.update { it.copy(lastDriveBackup = System.currentTimeMillis()) }
    }

    /** Returns false when no backup exists on Drive yet. */
    suspend fun restoreFromDrive(token: String): Boolean {
        val json = DriveBackup.download(token) ?: return false
        importJson(json)
        return true
    }

    suspend fun writeToUri(uri: Uri) = withContext(Dispatchers.IO) {
        val data = exportJson()
        context.contentResolver.openOutputStream(uri, "wt")?.use { it.write(data.toByteArray()) }
            ?: error("Cannot open the backup file for writing")
        repo.settings.update { it.copy(lastSafBackup = System.currentTimeMillis()) }
    }

    suspend fun readFromUri(uri: Uri) = withContext(Dispatchers.IO) {
        val json = context.contentResolver.openInputStream(uri)?.use { it.readBytes().decodeToString() }
            ?: error("Cannot open the backup file")
        importJson(json)
    }

    /** Runs whichever automatic backups are configured. */
    suspend fun autoBackup() {
        val s = repo.settings.current()
        if (s.driveAutoBackup) {
            DriveBackup.silentToken(context)?.let { backupToDrive(it) }
        }
        if (s.safBackupUri.isNotBlank()) {
            runCatching { writeToUri(Uri.parse(s.safBackupUri)) }
        }
    }
}
