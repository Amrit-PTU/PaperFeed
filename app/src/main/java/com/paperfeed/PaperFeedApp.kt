package com.paperfeed

import android.app.Application
import com.paperfeed.backup.BackupManager
import com.paperfeed.data.AppDatabase
import com.paperfeed.data.Repository
import com.paperfeed.data.SettingsStore
import com.paperfeed.search.SmartSearch
import com.paperfeed.work.Notifier
import com.paperfeed.work.RefreshScheduler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

class PaperFeedApp : Application() {
    lateinit var repo: Repository
        private set
    lateinit var backup: BackupManager
        private set
    /** Offline meaning search (same engine as Gurbani Ask). */
    lateinit var smart: SmartSearch
        private set
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val refreshing = MutableStateFlow(false)
    val messages = MutableSharedFlow<String>(extraBufferCapacity = 4)
    @Volatile var didInitialRefresh = false

    /** Foreground refresh triggered from the UI (pull-to-refresh, toolbar button). */
    fun refreshNow(onlySource: Long? = null) {
        if (!refreshing.compareAndSet(false, true)) return
        appScope.launch {
            try {
                val r = repo.refresh(onlySource)
                val msg = buildString {
                    append(if (r.totalNew == 0) "No new articles" else "${r.totalNew} new article${if (r.totalNew == 1) "" else "s"}")
                    if (r.errors.isNotEmpty()) append(" · ${r.errors.size} source${if (r.errors.size == 1) "" else "s"} failed")
                }
                messages.tryEmit(msg)
            } finally {
                refreshing.value = false
            }
            repo.enrichPending(limit = 40)
            runCatching { smart.index() }
        }
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        val db = AppDatabase.build(this)
        repo = Repository(db, SettingsStore(this))
        backup = BackupManager(this, repo)
        smart = SmartSearch(this, db)
        Notifier.createChannels(this)
        appScope.launch {
            repo.settings.migrateToMaterialYou()
            RefreshScheduler.schedule(this@PaperFeedApp, repo.settings.current().refreshMinutes, replace = false)
            runCatching { smart.index() }
        }
    }

    companion object {
        lateinit var instance: PaperFeedApp
            private set
    }
}
