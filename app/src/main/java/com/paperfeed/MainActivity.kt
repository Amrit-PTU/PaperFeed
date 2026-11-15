package com.paperfeed

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.List
import androidx.compose.material.icons.automirrored.outlined.PlaylistAdd
import androidx.compose.material.icons.outlined.Bookmark
import androidx.compose.material.icons.outlined.EditNote
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.RssFeed
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Badge
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.paperfeed.data.ListWithCount
import com.paperfeed.ui.ArticleScreen
import com.paperfeed.ui.AuthorScreen
import com.paperfeed.ui.HomeScreen
import com.paperfeed.ui.ListDetailScreen
import com.paperfeed.ui.ListsScreen
import com.paperfeed.ui.LocalSettings
import com.paperfeed.ui.NoteEditorScreen
import com.paperfeed.ui.NotesScreen
import com.paperfeed.ui.PublisherAbstractScreen
import com.paperfeed.ui.SearchScreen
import com.paperfeed.ui.SupportScreen
import com.paperfeed.ui.NameDialog
import com.paperfeed.ui.PaperFeedTheme
import com.paperfeed.ui.SettingsScreen
import com.paperfeed.ui.SourcesScreen
import com.paperfeed.ui.ThemeScreen
import com.paperfeed.ui.isDarkScheme
import com.paperfeed.ui.isReadLater
import com.paperfeed.ui.repo
import com.paperfeed.work.Notifier
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    /** Deep link from a notification or launcher shortcut, consumed by the nav host. */
    private val pendingRoute = MutableStateFlow<String?>(null)

    private val notifPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) {}

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        handleIntent(intent)
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        setContent {
            val settings by repo.settings.flow.collectAsState(initial = null)
            val s = settings ?: return@setContent
            PaperFeedTheme(s.themeId, s.customTheme, s.textScale) {
                val dark = isDarkScheme(MaterialTheme.colorScheme)
                LaunchedEffect(dark) {
                    val style = if (dark) SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
                    else SystemBarStyle.light(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT)
                    enableEdgeToEdge(statusBarStyle = style, navigationBarStyle = style)
                }
                CompositionLocalProvider(LocalSettings provides s) {
                    AppRoot(pendingRoute)
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        intent ?: return
        intent.getStringExtra(Notifier.EXTRA_ARTICLE_KEY)?.let { pendingRoute.value = Routes.article(it) }
        val listId = intent.getLongExtra(Notifier.EXTRA_LIST_ID, -1L)
        if (listId > 0) pendingRoute.value = Routes.list(listId)
    }
}

object Routes {
    const val HOME = "home"
    const val LISTS = "lists"
    const val SOURCES = "sources"
    const val SETTINGS = "settings"
    const val THEMES = "themes"
    const val NOTES = "notes"
    const val SUPPORT = "support"
    const val SEARCH = "search"
    fun note(key: String, noteId: Long = 0) = "note/${Uri.encode(key)}/$noteId"
    fun publisher(key: String) = "publisher/${Uri.encode(key)}"
    fun article(key: String) = "article/${Uri.encode(key)}"
    fun author(name: String) = "author/${Uri.encode(name)}"
    fun list(id: Long) = "list/$id"
}

private data class Tab(val route: String, val label: String, val icon: androidx.compose.ui.graphics.vector.ImageVector)

private val tabs = listOf(
    Tab(Routes.HOME, "Home", Icons.Outlined.Home),
    Tab(Routes.LISTS, "Lists", Icons.AutoMirrored.Outlined.List),
    Tab(Routes.SOURCES, "Sources", Icons.Outlined.RssFeed),
    Tab(Routes.SETTINGS, "Settings", Icons.Outlined.Settings),
)

private fun NavHostController.goTab(route: String) = navigate(route) {
    popUpTo(Routes.HOME) { saveState = true }
    launchSingleTop = true
    restoreState = true
}

@Composable
private fun AppRoot(pendingRoute: MutableStateFlow<String?>) {
    val nav = rememberNavController()
    val drawer = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val lists by repo.listsWithCounts().collectAsState(initial = emptyList())
    val notesCount by repo.notesCount().collectAsState(initial = 0)
    val backStack by nav.currentBackStackEntryAsState()
    val route = backStack?.destination?.route
    var newList by remember { mutableStateOf(false) }
    val pending by pendingRoute.collectAsState()
    val context = androidx.compose.ui.platform.LocalContext.current

    LaunchedEffect(pending) {
        pending?.let { nav.navigate(it); pendingRoute.value = null }
    }
    LaunchedEffect(lists) { publishShortcuts(context, lists) }

    val openDrawer: () -> Unit = { scope.launch { drawer.open() } }

    ModalNavigationDrawer(
        drawerState = drawer,
        gesturesEnabled = route in tabs.map { it.route } || route == Routes.NOTES,
        drawerContent = {
            ModalDrawerSheet {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    Text("PaperFeed", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, modifier = Modifier.padding(24.dp))
                    tabs.take(1).forEach { t ->
                        NavigationDrawerItem(
                            label = { Text(t.label) }, icon = { Icon(t.icon, null) }, selected = route == t.route,
                            onClick = { scope.launch { drawer.close() }; nav.goTab(t.route) },
                            modifier = Modifier.padding(horizontal = 12.dp),
                        )
                    }
                    HorizontalDivider(Modifier.padding(vertical = 8.dp))
                    Text("Lists", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(start = 28.dp, bottom = 4.dp))
                    lists.forEach { (l, count) ->
                        NavigationDrawerItem(
                            label = { Text(l.name) },
                            icon = { Icon(if (isReadLater(l)) Icons.Outlined.Bookmark else Icons.AutoMirrored.Outlined.List, null) },
                            badge = { if (count > 0) Badge { Text("$count") } },
                            selected = route == "list/{id}" && backStack?.arguments?.getLong("id") == l.id,
                            onClick = { scope.launch { drawer.close() }; nav.navigate(Routes.list(l.id)) },
                            modifier = Modifier.padding(horizontal = 12.dp),
                        )
                    }
                    NavigationDrawerItem(
                        label = { Text("New list") }, icon = { Icon(Icons.AutoMirrored.Outlined.PlaylistAdd, null) }, selected = false,
                        onClick = { newList = true }, modifier = Modifier.padding(horizontal = 12.dp),
                    )
                    NavigationDrawerItem(
                        label = { Text("Notes") }, icon = { Icon(Icons.Outlined.EditNote, null) },
                        badge = { if (notesCount > 0) Badge { Text("$notesCount") } },
                        selected = route == Routes.NOTES,
                        onClick = { scope.launch { drawer.close() }; nav.navigate(Routes.NOTES) { launchSingleTop = true } },
                        modifier = Modifier.padding(horizontal = 12.dp),
                    )
                    HorizontalDivider(Modifier.padding(vertical = 8.dp))
                    tabs.drop(2).forEach { t ->
                        NavigationDrawerItem(
                            label = { Text(t.label) }, icon = { Icon(t.icon, null) }, selected = route == t.route,
                            onClick = { scope.launch { drawer.close() }; nav.goTab(t.route) },
                            modifier = Modifier.padding(horizontal = 12.dp),
                        )
                    }
                    Spacer(Modifier.height(16.dp))
                }
            }
        },
    ) {
        Scaffold(
            bottomBar = {
                if (route in tabs.map { it.route }) {
                    NavigationBar {
                        tabs.forEach { t ->
                            NavigationBarItem(
                                selected = route == t.route,
                                onClick = { nav.goTab(t.route) },
                                icon = { Icon(t.icon, null) },
                                label = { Text(t.label) },
                            )
                        }
                    }
                }
            },
        ) { padding ->
            NavHost(nav, startDestination = Routes.HOME, modifier = Modifier.padding(bottom = padding.calculateBottomPadding()).consumeWindowInsets(PaddingValues(bottom = padding.calculateBottomPadding()))) {
                composable(Routes.HOME) {
                    HomeScreen(
                        openDrawer, onArticle = { nav.navigate(Routes.article(it)) }, onAuthor = { nav.navigate(Routes.author(it)) },
                        onSupport = { nav.navigate(Routes.SUPPORT) },
                        onSearch = { nav.navigate(Routes.SEARCH) },
                    )
                }
                composable(Routes.LISTS) {
                    ListsScreen(openDrawer, onList = { nav.navigate(Routes.list(it)) }, onNotes = { nav.navigate(Routes.NOTES) })
                }
                composable(Routes.SOURCES) { SourcesScreen(openDrawer) }
                composable(Routes.SETTINGS) {
                    SettingsScreen(openDrawer, onThemes = { nav.navigate(Routes.THEMES) }, onSupport = { nav.navigate(Routes.SUPPORT) })
                }
                composable(Routes.THEMES) { ThemeScreen(onBack = { nav.popBackStack() }) }
                composable("article/{key}", arguments = listOf(navArgument("key") { type = NavType.StringType })) { e ->
                    val key = e.arguments?.getString("key").orEmpty()
                    ArticleScreen(
                        key, onBack = { nav.popBackStack() }, onAuthor = { nav.navigate(Routes.author(it)) },
                        onNote = { nav.navigate(Routes.note(key, it)) },
                        onPublisherAbstract = { nav.navigate(Routes.publisher(key)) },
                    )
                }
                composable("author/{name}", arguments = listOf(navArgument("name") { type = NavType.StringType })) { e ->
                    val name = e.arguments?.getString("name").orEmpty()
                    AuthorScreen(
                        name, onBack = { nav.popBackStack() },
                        onArticle = { nav.navigate(Routes.article(it)) },
                        onAuthor = { nav.navigate(Routes.author(it)) },
                    )
                }
                composable(
                    "note/{key}/{id}",
                    arguments = listOf(navArgument("key") { type = NavType.StringType }, navArgument("id") { type = NavType.LongType }),
                ) { e ->
                    NoteEditorScreen(e.arguments?.getString("key").orEmpty(), e.arguments?.getLong("id") ?: 0L, onBack = { nav.popBackStack() })
                }
                composable("publisher/{key}", arguments = listOf(navArgument("key") { type = NavType.StringType })) { e ->
                    PublisherAbstractScreen(e.arguments?.getString("key").orEmpty(), onBack = { nav.popBackStack() })
                }
                composable(Routes.NOTES) {
                    NotesScreen(
                        openDrawer,
                        onEdit = { k, id -> nav.navigate(Routes.note(k, id)) },
                        onArticle = { nav.navigate(Routes.article(it)) },
                    )
                }
                composable(Routes.SUPPORT) { SupportScreen(onBack = { nav.popBackStack() }) }
                composable(Routes.SEARCH) {
                    SearchScreen(
                        onBack = { nav.popBackStack() },
                        onArticle = { nav.navigate(Routes.article(it)) },
                        onAuthor = { nav.navigate(Routes.author(it)) },
                    )
                }
                composable("list/{id}", arguments = listOf(navArgument("id") { type = NavType.LongType })) { e ->
                    ListDetailScreen(
                        e.arguments?.getLong("id") ?: 1L,
                        onBack = { nav.popBackStack() },
                        onArticle = { nav.navigate(Routes.article(it)) },
                        onAuthor = { nav.navigate(Routes.author(it)) },
                    )
                }
            }
        }
    }
    if (newList) NameDialog("New list", confirm = "Create", onDismiss = { newList = false }) {
        scope.launch { repo.createList(it) }; newList = false
    }
}

/** Long-press launcher shortcuts that open the first few lists directly. */
private fun publishShortcuts(context: android.content.Context, lists: List<ListWithCount>) {
    val max = ShortcutManagerCompat.getMaxShortcutCountPerActivity(context).coerceAtMost(4)
    val shortcuts = lists.take(max).mapIndexed { i, (l, _) ->
        ShortcutInfoCompat.Builder(context, "list_${l.id}")
            .setShortLabel(l.name.take(24))
            .setIcon(IconCompat.createWithResource(context, if (isReadLater(l)) R.drawable.ic_shortcut_bookmark else R.drawable.ic_shortcut_list))
            .setIntent(Intent(context, MainActivity::class.java).setAction(Intent.ACTION_VIEW).putExtra(Notifier.EXTRA_LIST_ID, l.id))
            .setRank(i)
            .build()
    }
    runCatching { ShortcutManagerCompat.setDynamicShortcuts(context, shortcuts) }
}
