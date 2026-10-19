package com.kenny.browserforwearos.presentation

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.wear.compose.material3.AlertDialog
import androidx.wear.compose.material3.AppScaffold
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.OutlinedButton
import androidx.wear.compose.material3.Text
import com.kenny.browserforwearos.BuildConfig
import com.kenny.browserforwearos.R
import com.kenny.browserforwearos.engine.EnginePrompt
import com.kenny.browserforwearos.engine.TabSessionController
import com.kenny.browserforwearos.presentation.theme.BrowserForWearOSTheme
import kotlinx.coroutines.delay
import org.mozilla.geckoview.GeckoView

/**
 * B1 真机引擎门禁界面：最小可用的 地址输入 → 真实网页 渲染路径。
 * 圆屏完整 UI（首页/工具菜单/四角按钮等）在 B2 展开；此处地址判别为临时规则。
 */
class MainActivity : ComponentActivity() {

    private val viewModel: BrowserViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        setContent {
            BrowserForWearOSTheme {
                BrowserNavHost(viewModel)
            }
        }
    }

    override fun onPause() {
        super.onPause()
        // 熄屏/后台暂停非必要网页活动；setActive(false) 的真实行为由 B1 真机验证记录
        viewModel.controller.setActive(false)
    }

    override fun onResume() {
        super.onResume()
        viewModel.controller.setActive(true)
    }
}

private enum class Screen { Home, Address, Web }

@Composable
fun BrowserNavHost(viewModel: BrowserViewModel) {
    var screen by remember { mutableStateOf(Screen.Home) }
    val controller = viewModel.controller

    when (screen) {
        Screen.Home -> HomeScreen(
            onOpenWeb = { screen = Screen.Address },
        )
        Screen.Address -> AddressScreen(
            onSubmit = { input ->
                viewModel.submitAddress(input)
                screen = Screen.Web
            },
            onCancel = { screen = Screen.Home },
        )
        Screen.Web -> WebScreen(
            viewModel = viewModel,
            onHome = { screen = Screen.Home },
        )
    }

    // 引擎反馈（弹窗拦截/未支持链接等）：轻量浮层，4 秒自动消失
    val feedback by controller.feedback.collectAsState()
    feedback?.let { message ->
        LaunchedEffect(message) {
            delay(4000)
            controller.consumeFeedback()
        }
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(top = 20.dp),
            contentAlignment = Alignment.TopCenter,
        ) {
            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .background(MaterialTheme.colorScheme.surfaceContainer, MaterialTheme.shapes.small)
                    .padding(horizontal = 12.dp, vertical = 8.dp),
            )
        }
    }

    EnginePromptDialogs(controller)
}

@Composable
fun HomeScreen(onOpenWeb: () -> Unit) {
    AppScaffold {
        Box(
            modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
            contentAlignment = Alignment.Center,
        ) {
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = stringResource(R.string.app_name),
                    textAlign = TextAlign.Center,
                    style = MaterialTheme.typography.titleLarge,
                )
                Text(
                    text = BuildConfig.VERSION_NAME,
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    text = stringResource(R.string.abi_label, BuildConfig.FLAVOR),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Button(
                    onClick = onOpenWeb,
                    modifier = Modifier.padding(top = 8.dp),
                ) {
                    Text(stringResource(R.string.b1_open_web))
                }
            }
        }
    }
}

@Composable
fun AddressScreen(
    onSubmit: (String) -> Unit,
    onCancel: () -> Unit,
) {
    var text by remember { mutableStateOf("") }
    AppScaffold {
        Column(
            modifier = Modifier.fillMaxSize().padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = stringResource(R.string.b1_address_title),
                style = MaterialTheme.typography.titleLarge,
            )
            BasicTextField(
                value = text,
                onValueChange = { text = it },
                textStyle = TextStyle(color = MaterialTheme.colorScheme.onSurface),
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surfaceContainer, MaterialTheme.shapes.small)
                    .padding(12.dp),
                decorationBox = { inner ->
                    Box {
                        if (text.isEmpty()) {
                            Text(
                                text = stringResource(R.string.b1_address_hint),
                                style = TextStyle(color = MaterialTheme.colorScheme.onSurfaceVariant),
                            )
                        }
                        inner()
                    }
                },
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = { onSubmit(text) },
                    enabled = text.isNotBlank(),
                ) {
                    Text(stringResource(R.string.b1_go))
                }
                OutlinedButton(onClick = onCancel) {
                    Text(stringResource(R.string.b1_cancel))
                }
            }
        }
    }
}

@Composable
fun WebScreen(
    viewModel: BrowserViewModel,
    onHome: () -> Unit,
) {
    val controller = viewModel.controller
    val url by controller.url.collectAsState()
    val title by controller.title.collectAsState()
    val loading by controller.isLoading.collectAsState()
    val progress by controller.progress.collectAsState()
    val canGoBack by controller.canGoBack.collectAsState()

    // B1 返回顺序简化：网页历史后退 → 回首页；键盘/弹层/全屏层级在 B2 补全
    BackHandler(enabled = true) {
        if (canGoBack) {
            controller.goBack()
        } else {
            onHome()
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        // B1 临时状态条：真实引擎状态（最终网址/标题/加载进度）
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color.Black)
                .padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title.ifEmpty { url },
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color.White,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (loading) {
                    Text(
                        text = stringResource(R.string.b1_loading, progress),
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color(0xFF9BE29B),
                    )
                }
            }
            Button(onClick = onHome) {
                Text(stringResource(R.string.b1_home))
            }
        }
        // 网页主体：GeckoView。factory 每次挂载只执行一次，setSession 不触发重新加载；
        // 离开组合时 releaseSession 解绑视图但保留会话（规范 3.2 规则 2）
        AndroidView(
            factory = { context ->
                GeckoView(context).apply {
                    setSession(controller.session)
                }
            },
            onRelease = { view ->
                if (view.session === controller.session) {
                    view.releaseSession()
                }
            },
            modifier = Modifier.fillMaxSize(),
        )
    }
}

/** 引擎原生提示：alert/confirm/prompt/HTTP 认证/选择框/离开确认。 */
@Composable
fun EnginePromptDialogs(controller: TabSessionController) {
    val prompt by controller.pendingPrompt.collectAsState()
    val current = prompt ?: return

    when (current) {
        is EnginePrompt.Alert -> {
            // GeckoView 中 alert 的"确认"即 dismiss 语义
            AlertDialog(
                visible = true,
                onDismissRequest = { controller.dismissPrompt() },
                confirmButton = {
                    Button(onClick = { controller.dismissPrompt() }) {
                        Text(stringResource(R.string.b1_ok))
                    }
                },
                title = { Text(current.prompt.title.orEmpty()) },
                text = { Text(current.prompt.message.orEmpty()) },
            )
        }
        is EnginePrompt.Confirm -> {
            AlertDialog(
                visible = true,
                onDismissRequest = { controller.confirmButton(false) },
                confirmButton = {
                    Button(onClick = { controller.confirmButton(true) }) {
                        Text(stringResource(R.string.b1_ok))
                    }
                },
                title = { Text(current.prompt.title.orEmpty()) },
                text = { Text(current.prompt.message.orEmpty()) },
                dismissButton = {
                    OutlinedButton(onClick = { controller.confirmButton(false) }) {
                        Text(stringResource(R.string.b1_cancel))
                    }
                },
            )
        }
        is EnginePrompt.Text -> {
            var value by remember(current) { mutableStateOf(current.prompt.defaultValue.orEmpty()) }
            AlertDialog(
                visible = true,
                onDismissRequest = { controller.dismissPrompt() },
                confirmButton = {
                    Button(onClick = { controller.confirmText(value) }) {
                        Text(stringResource(R.string.b1_ok))
                    }
                },
                title = { Text(current.prompt.title.orEmpty()) },
                text = { Text(current.prompt.message.orEmpty()) },
                dismissButton = {
                    OutlinedButton(onClick = { controller.dismissPrompt() }) {
                        Text(stringResource(R.string.b1_cancel))
                    }
                },
                content = {
                    item {
                        BasicTextField(
                            value = value,
                            onValueChange = { value = it },
                            textStyle = TextStyle(color = MaterialTheme.colorScheme.onSurface),
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(MaterialTheme.colorScheme.surfaceContainer, MaterialTheme.shapes.small)
                                .padding(8.dp),
                        )
                    }
                },
            )
        }
        is EnginePrompt.Auth -> {
            var user by remember(current) { mutableStateOf("") }
            var pass by remember(current) { mutableStateOf("") }
            AlertDialog(
                visible = true,
                onDismissRequest = { controller.dismissPrompt() },
                confirmButton = {
                    Button(onClick = { controller.confirmAuth(user, pass) }) {
                        Text(stringResource(R.string.b1_ok))
                    }
                },
                title = { Text(current.prompt.title.orEmpty()) },
                text = { Text(current.prompt.message.orEmpty()) },
                dismissButton = {
                    OutlinedButton(onClick = { controller.dismissPrompt() }) {
                        Text(stringResource(R.string.b1_cancel))
                    }
                },
                content = {
                    item {
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            BasicTextField(
                                value = user,
                                onValueChange = { user = it },
                                textStyle = TextStyle(color = MaterialTheme.colorScheme.onSurface),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .background(MaterialTheme.colorScheme.surfaceContainer, MaterialTheme.shapes.small)
                                    .padding(8.dp),
                                decorationBox = { inner ->
                                    Box {
                                        if (user.isEmpty()) {
                                            Text(
                                                text = stringResource(R.string.b1_auth_user),
                                                style = TextStyle(color = MaterialTheme.colorScheme.onSurfaceVariant),
                                            )
                                        }
                                        inner()
                                    }
                                },
                            )
                            BasicTextField(
                                value = pass,
                                onValueChange = { pass = it },
                                textStyle = TextStyle(color = MaterialTheme.colorScheme.onSurface),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .background(MaterialTheme.colorScheme.surfaceContainer, MaterialTheme.shapes.small)
                                    .padding(8.dp),
                                decorationBox = { inner ->
                                    Box {
                                        if (pass.isEmpty()) {
                                            Text(
                                                text = stringResource(R.string.b1_auth_password),
                                                style = TextStyle(color = MaterialTheme.colorScheme.onSurfaceVariant),
                                            )
                                        }
                                        inner()
                                    }
                                },
                            )
                        }
                    }
                },
            )
        }
        is EnginePrompt.Choice -> {
            AlertDialog(
                visible = true,
                onDismissRequest = { controller.dismissPrompt() },
                confirmButton = {
                    OutlinedButton(onClick = { controller.dismissPrompt() }) {
                        Text(stringResource(R.string.b1_cancel))
                    }
                },
                title = { Text(current.prompt.title.orEmpty()) },
                text = { Text(current.prompt.message.orEmpty()) },
                content = {
                    current.prompt.choices.forEach { choice ->
                        item(key = choice.id) {
                            Text(
                                text = choice.label,
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .then(
                                        if (choice.disabled) {
                                            Modifier
                                        } else {
                                            Modifier.clickable { controller.confirmChoice(choice) }
                                        },
                                    )
                                    .padding(vertical = 8.dp),
                            )
                        }
                    }
                },
            )
        }
        is EnginePrompt.BeforeUnload -> {
            AlertDialog(
                visible = true,
                onDismissRequest = { controller.dismissPrompt() },
                confirmButton = {
                    Button(onClick = { controller.confirmLeave() }) {
                        Text(stringResource(R.string.b1_leave_ok))
                    }
                },
                title = { Text(stringResource(R.string.b1_leave_title)) },
                text = { Text(stringResource(R.string.b1_leave_message)) },
                dismissButton = {
                    OutlinedButton(onClick = { controller.dismissPrompt() }) {
                        Text(stringResource(R.string.b1_stay))
                    }
                },
            )
        }
    }
}
