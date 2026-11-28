package com.nasmusic.player.ui

import android.Manifest
import android.content.res.Configuration
import android.net.Uri
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
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
fun AppRoot(
    vm: MainViewModel,
    openPlayer: Boolean,
    onConsumeOpenPlayer: () -> Unit = {}
) {
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
    val backStackEntry by nav.currentBackStackEntryAsState()
    val route = backStackEntry?.destination?.route.orEmpty()
    val metrics = LocalCarMetrics.current
    val configuration = LocalConfiguration.current
    val isLandscape = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
    // 是否使用双栏发现页，必须以「当前朝向」为准：
    //  - 真车机固定横屏（metrics.enabled=true），天然一直是双栏；
    //  - 手机 / 平板 / 折叠屏随旋转切换：横屏 discover 双栏，竖屏 player 单页。
    // 注意：不能把 smallestScreenWidthDp >= 600（设备级属性，旋转不变）算进来，
    // 否则平板 / 折叠屏在竖屏时 useDiscover 恒为 true，会一直卡在横屏双栏。
    val useDiscover = metrics.enabled || isLandscape
    val showSearchBar = route in CONTENT_ROUTES

    // startDestination 只在首次组合计算一次并冻结。Activity 声明了 configChanges，旋转不重建，
    // 若 startDestination 随方向变化，NavHost 会重建 graph 并清空整个返回栈（二级页面全丢）。
    // 之后的方向切换统一交给下面的 effect 用普通导航完成，不再动 startDestination。
    val startDestination = remember { if (openPlayer || !useDiscover) "player" else "discover" }

    // 通知/外部「打开播放器」：冷启动由 startDestination 保证；热启动 onNewIntent 时在此跳转。
    LaunchedEffect(openPlayer) {
        if (openPlayer) {
            if (nav.currentBackStackEntry?.destination?.route != "player") {
                nav.navigate("player") { launchSingleTop = true }
            }
            // 消费后复位：否则下次从通知打开同一个 intent 时 openPlayer 仍为 true，
            // 会把用户从当前页面强行踢回播放页
            onConsumeOpenPlayer()
            // 横屏从通知冷启动：startDestination 因 openPlayer 冻结在 player，
            // 而方向 effect 首帧因 openPlayer 保护跳过、之后 useDiscover 不再变化，
            // 会永远卡在单页。这里消费后主动对齐一次：横屏切到 discover 双栏（左侧仍是播放卡片），
            // 竖屏保持 player。只处理主路由，不打断二级页面。
            val current = nav.currentBackStackEntry?.destination?.route
            val target = if (useDiscover) "discover" else "player"
            if ((current == null || current == "player" || current == "discover") && current != target) {
                nav.navigate(target) {
                    launchSingleTop = true
                    if (current != null) popUpTo(current) { inclusive = true }
                }
            }
        }
    }

    // 方向变化后把主路由对齐到当前朝向：横屏用 discover 双栏，竖屏用 player 单栏。
    //
    // 修复「切换时一直卡在横屏」：
    //  1) useDiscover 不再掺入设备级的 isTablet，平板 / 折叠屏竖屏也能切回 player。
    //  2) 首帧也做一次对齐（原实现无条件跳过），避免「横屏启动 → 直接竖屏」时
    //     startDestination 冻结在 discover 又没有后续旋转事件，导致一直卡在横屏双栏。
    //     仅从通知冷启动进 player（openPlayer=true）时尊重该决策、不做对齐。
    //  3) 切换时用 popUpTo 清掉来回旋转堆积的 player/discover，返回键直接退出而不是
    //     回到另一朝向的布局。
    val firstOrientationPass = remember { mutableStateOf(true) }
    LaunchedEffect(useDiscover) {
        if (firstOrientationPass.value) {
            firstOrientationPass.value = false
            // 横屏从通知冷启动：startDestination 已是 player，不要首帧就踢回 discover
            if (openPlayer) return@LaunchedEffect
        }
        // 只在 player/discover 主路由之间切换，绝不打断登录、浏览、歌单等二级页面。
        val current = nav.currentBackStackEntry?.destination?.route
        val target = if (useDiscover) "discover" else "player"
        if ((current == null || current == "player" || current == "discover") && current != target) {
            nav.navigate(target) {
                launchSingleTop = true
                // 清理切换中堆积的主路由，返回键直接退出而不是回到另一朝向的布局
                if (current != null) popUpTo(current) { inclusive = true }
            }
        }
    }
    val showMiniPlayer = playState.currentSong != null &&
        route != "player" && route != "discover" &&
        !route.startsWith("fnosLogin") && !route.startsWith("onlineSourceLogin")
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
                        startDestination = startDestination,
                        modifier = Modifier
                            .fillMaxHeight()
                            // 必须撑满可用宽度：否则 NavHost 退化为 wrap-content，
                            // PlayerScreen 的 fillMaxSize 会把顶栏 Row 撑到 4000dp，
                            // 手机可视区正好落在中间的 Spacer 空白段，导致整排顶栏按钮消失。
                            .fillMaxWidth()
                            .widthIn(max = if (route == "player" || route == "discover") 4000.dp else metrics.listMaxWidth)
                    ) {
                        composable("library") { LibraryScreen(vm, playerVm, nav) }
                        composable("discover") { DiscoverScreen(vm, playerVm, nav) }
                        composable("settings") { SettingsScreen(vm, playerVm, nav) }
                        composable("soundEffect") {
                            if (useDiscover) {
                                // 横屏/车机/大屏：音效内嵌在双栏发现页右栏，左侧播放卡片常驻不消失
                                DiscoverScreen(
                                    vm, playerVm, nav,
                                    startInSound = true,
                                    soundExit = { nav.popBackStack() }
                                )
                            } else {
                                SoundEffectScreen(vm, onBack = { nav.popBackStack() })
                            }
                        }
                        // 独立的网络音乐源页：竖屏首页顶栏云朵按钮直达；
                        // 横屏仍在发现页顶部 Tab 内嵌展示，不走此路由。
                        composable("nasSources") {
                            Column(Modifier.fillMaxSize().statusBarsPadding()) {
                                SimpleTopBar(title = "网络音乐源", onBack = { nav.popBackStack() })
                                Column(
                                    Modifier
                                        .weight(1f)
                                        .fillMaxWidth()
                                        .verticalScroll(rememberScrollState())
                                        .padding(horizontal = 16.dp, vertical = 8.dp)
                                ) {
                                    NasSourcesSection(vm, nav)
                                }
                            }
                        }
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
                                onOpenSources = { nav.navigate("nasSources") { launchSingleTop = true } },
                                onOpenSound = { nav.navigate("soundEffect") { launchSingleTop = true } },
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
                                // Navigation 的 StringType 参数取出时已经解码一次，
                                // 这里再 decode 会破坏名字里含 % 的路径（#17）
                                path = args?.getString("path").orEmpty(),
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
                        composable(
                            route = "onlineSourceLogin?sourceId={sourceId}",
                            arguments = listOf(
                                navArgument("sourceId") {
                                    type = NavType.StringType
                                    defaultValue = ""
                                }
                            )
                        ) { entry ->
                            OnlineSourceLoginScreen(
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
