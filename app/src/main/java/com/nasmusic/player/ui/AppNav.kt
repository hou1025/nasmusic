package com.nasmusic.player.ui

import android.Manifest
import android.net.Uri
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument

/** 搜索框与返回栏只在列表/设置页出现；播放页保持无底部导航的沉浸式界面。 */
private val CONTENT_ROUTES = setOf("library", "settings")

fun nasBrowserRoute(sourceId: String, path: String): String =
    "nasBrowser/$sourceId?path=${Uri.encode(path)}"

@Composable
fun AppRoot(vm: MainViewModel, openPlayer: Boolean) {
    val nav = rememberNavController()
    val playerVm: PlayerViewModel = viewModel()
    val playState by playerVm.state.collectAsStateWithLifecycle()
    val granted by vm.permissionGranted.collectAsStateWithLifecycle()
    val query by vm.query.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(Unit) { vm.message.collect { snackbarHostState.showSnackbar(it) } }
    LaunchedEffect(Unit) { playerVm.message.collect { snackbarHostState.showSnackbar(it) } }

    val permissions = remember {
        buildList {
            add(
                if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_AUDIO
                else Manifest.permission.READ_EXTERNAL_STORAGE
            )
            if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
        }.toTypedArray()
    }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { vm.refreshPermissions() }

    LaunchedEffect(Unit) {
        vm.refreshPermissions()
        permissionLauncher.launch(permissions)
    }
    LaunchedEffect(openPlayer) {
        if (openPlayer && nav.currentBackStackEntry?.destination?.route != "player") {
            nav.navigate("player") { launchSingleTop = true }
        }
    }

    val backStackEntry by nav.currentBackStackEntryAsState()
    val route = backStackEntry?.destination?.route.orEmpty()
    val metrics = LocalCarMetrics.current
    val showSearchBar = route in CONTENT_ROUTES
    val showMiniPlayer = playState.currentSong != null &&
        route != "player" && !route.startsWith("fnosLogin")
    // 列表/设置页的搜索结果浮层：返回键先清空搜索，不直接退出页面
    BackHandler(enabled = query.isNotBlank() && showSearchBar) { vm.setQuery("") }

    Box(Modifier.fillMaxSize().background(LocalAppBackdrop.current)) {
        Scaffold(
            containerColor = Color.Transparent,
            // 各 Slot 自行处理系统栏：主页面搜索框加状态栏内边距，二级页顶栏由 TopAppBar 处理
            contentWindowInsets = WindowInsets(0.dp),
            snackbarHost = { SnackbarHost(snackbarHostState) },
            topBar = {
                // 列表/设置页：返回播放页 + 顶部搜索框，统一处理状态栏安全区
                if (showSearchBar) {
                    // 车机超宽屏：顶栏与页面内容同宽并居中，避免搜索框横跨整块中控屏
                    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .widthIn(max = metrics.listMaxWidth)
                                .statusBarsPadding()
                                .padding(horizontal = 8.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            IconButton(
                                onClick = {
                                    if (!nav.popBackStack()) {
                                        nav.navigate("player") { launchSingleTop = true }
                                    }
                                },
                                modifier = Modifier.size(metrics.touchTarget)
                            ) {
                                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回播放页")
                            }
                            AppSearchBar(
                                query = query,
                                onQueryChange = { vm.setQuery(it) },
                                modifier = Modifier.weight(1f).padding(start = 4.dp)
                            )
                        }
                    }
                }
            },
            bottomBar = {
                // 底部导航已取消：只保留迷你播放器 + 系统手势条安全区，
                // 让播放页控制行不再贴到屏幕最底部
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.BottomCenter) {
                    Column(Modifier.widthIn(max = metrics.listMaxWidth)) {
                        if (showMiniPlayer) {
                            MiniPlayer(
                                state = playState,
                                onOpen = { nav.navigate("player") { launchSingleTop = true } },
                                onPlayPause = { playerVm.playPause() },
                                onNext = { playerVm.next() }
                            )
                        }
                        Spacer(Modifier.navigationBarsPadding())
                    }
                }
            }
        ) { padding ->
            Column(Modifier.fillMaxSize().padding(padding)) {
                if (!granted) {
                    PermissionBanner(onRequest = { permissionLauncher.launch(permissions) })
                }
                // 非播放页在超宽屏上限制内容宽度并居中，避免一行文字横跨整块中控屏
                Box(Modifier.weight(1f), contentAlignment = Alignment.TopCenter) {
                    NavHost(
                        navController = nav,
                        startDestination = "player",
                        modifier = Modifier
                            .fillMaxHeight()
                            .widthIn(max = if (route == "player") 4000.dp else metrics.listMaxWidth)
                    ) {
                        composable("library") { LibraryScreen(vm, playerVm, nav) }
                        composable("settings") { SettingsScreen(vm, playerVm, nav) }
                        composable("player") {
                            PlayerScreen(
                                vm, playerVm,
                                onBack = {
                                    // 启动页就是播放页：此时「收起」等于打开音乐列表
                                    if (!nav.popBackStack()) {
                                        nav.navigate("library") { launchSingleTop = true }
                                    }
                                },
                                onOpenLibrary = { nav.navigate("library") { launchSingleTop = true } },
                                onOpenSettings = { nav.navigate("settings") { launchSingleTop = true } }
                            )
                        }
                        composable(
                            route = "playlist/{playlistId}",
                            arguments = listOf(navArgument("playlistId") { type = NavType.StringType })
                        ) { entry ->
                            PlaylistDetailScreen(
                                vm, playerVm,
                                entry.arguments?.getString("playlistId").orEmpty(),
                                onBack = { nav.popBackStack() }
                            )
                        }
                        composable(
                            route = "nasBrowser/{sourceId}?path={path}",
                            arguments = listOf(
                                navArgument("sourceId") { type = NavType.StringType },
                                navArgument("path") {
                                    type = NavType.StringType
                                    defaultValue = ""
                                }
                            )
                        ) { entry ->
                            val args = entry.arguments
                            NasBrowserScreen(
                                vm, playerVm,
                                sourceId = args?.getString("sourceId").orEmpty(),
                                path = Uri.decode(args?.getString("path").orEmpty()),
                                onBack = { nav.popBackStack() },
                                onOpenFolder = { newPath ->
                                    nav.navigate(
                                        nasBrowserRoute(args?.getString("sourceId").orEmpty(), newPath)
                                    )
                                }
                            )
                        }
                        composable(
                            route = "fnosLogin?sourceId={sourceId}",
                            arguments = listOf(
                                navArgument("sourceId") {
                                    type = NavType.StringType
                                    defaultValue = ""
                                }
                            )
                        ) { entry ->
                            FnosLoginScreen(
                                vm = vm,
                                sourceId = entry.arguments?.getString("sourceId").orEmpty(),
                                onBack = { nav.popBackStack() },
                                onDone = { nav.popBackStack() }
                            )
                        }
                    }

                    // 搜索结果以浮层覆盖当前页面，返回后页面状态保持不变
                    if (query.isNotBlank() && showSearchBar) {
                        Surface(
                            modifier = Modifier.matchParentSize(),
                            color = MaterialTheme.colorScheme.surface.copy(alpha = 0.98f)
                        ) {
                            SearchResultsPanel(vm, playerVm)
                        }
                    }
                }
            }
        }
    }
}
