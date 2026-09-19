package com.autoclickerplus

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.app.AlertDialog
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.foundation.rememberScrollState
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.autoclickerplus.model.AutomationAction
import com.autoclickerplus.model.AutomationCondition
import com.autoclickerplus.model.flowSummary
import com.autoclickerplus.model.flowTitle
import com.autoclickerplus.model.MAX_POSITION_JITTER_PX
import com.autoclickerplus.model.MAX_WAIT_JITTER_MS
import com.autoclickerplus.model.collectActionPaths
import com.autoclickerplus.model.jumpTargetLabel
import com.autoclickerplus.model.resolvedTargetPath
import com.autoclickerplus.model.summary
import com.autoclickerplus.model.AutomationConfig
import com.autoclickerplus.model.BranchSide
import com.autoclickerplus.model.ConditionOperator
import com.autoclickerplus.model.RepeatMode
import com.autoclickerplus.model.ScriptLibrary
import com.autoclickerplus.model.TextMatchMode
import com.autoclickerplus.service.AutoClickAccessibilityService
import com.autoclickerplus.data.ScriptTransfer
import kotlin.math.roundToInt

class MainActivity : ComponentActivity() {
    private val viewModel: AutomationViewModel by viewModels()
    private val scriptTransfer = ScriptTransfer()
    private var serviceConnected by mutableStateOf(false)
    private val exportLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/json"),
    ) { uri ->
        if (uri == null) return@registerForActivityResult
        runCatching {
            contentResolver.openOutputStream(uri)?.bufferedWriter()?.use {
                it.write(scriptTransfer.exportAll(viewModel.library.value))
            } ?: error("ファイルを開けません")
        }.onSuccess {
            toast("全スクリプトをExportしました")
        }.onFailure {
            toast("Exportに失敗しました: ${it.message}")
        }
    }
    private val importLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri == null) return@registerForActivityResult
        runCatching {
            contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
                ?: error("ファイルを開けません")
        }.onSuccess { source ->
            scriptTransfer.importAsNew(viewModel.library.value, source)
                .onSuccess { imported ->
                    val addedCount = imported.scripts.size - viewModel.library.value.scripts.size
                    val addedNames = imported.scripts
                        .takeLast(addedCount)
                        .joinToString("\n") { "・${it.name}" }
                    AlertDialog.Builder(this)
                        .setTitle("Importの確認")
                        .setMessage(
                            "$addedCount 件を新規スクリプトとして追加します。\n\n$addedNames",
                        )
                        .setNegativeButton("キャンセル", null)
                        .setPositiveButton("追加") { _, _ ->
                            viewModel.replaceLibrary(imported)
                            toast("$addedCount 件をImportしました")
                        }
                        .show()
                }
                .onFailure { toast("Importできません: ${it.message}") }
        }.onFailure {
            toast("Importに失敗しました: ${it.message}")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                val config by viewModel.config.collectAsState()
                val library by viewModel.library.collectAsState()
                AutomationScreen(
                    config = config,
                    library = library,
                    serviceConnected = serviceConnected,
                    viewModel = viewModel,
                    onOpenAccessibility = {
                        startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                    },
                    onShowControls = {
                        if (!AutoClickAccessibilityService.showControls()) {
                            toast("先に操作サービスを有効にしてください")
                        }
                    },
                    onExport = {
                        exportLauncher.launch("AutoClickerPlus-scripts.json")
                    },
                    onImport = {
                        importLauncher.launch(arrayOf("application/json", "text/plain"))
                    },
                    onPickCoordinates = { actionId ->
                        if (!AutoClickAccessibilityService.requestCoordinatePick(actionId)) {
                            toast("先に操作サービスを有効にしてください")
                        } else {
                            moveTaskToBack(true)
                        }
                    },
                    onPickAllCoordinates = {
                        if (!AutoClickAccessibilityService.requestBulkCoordinatePick()) {
                            toast("先に操作サービスを有効にしてください")
                        } else {
                            moveTaskToBack(true)
                        }
                    },
                    onPickColor = { ifBlockId, conditionId ->
                        if (!AutoClickAccessibilityService.requestColorPick(
                                ifBlockId,
                                conditionId,
                            )
                        ) {
                            toast("先に操作サービスを有効にしてください")
                        } else {
                            moveTaskToBack(true)
                        }
                    },
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        serviceConnected = AutoClickAccessibilityService.isConnected
    }

    private fun toast(message: String) =
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AutomationScreen(
    config: AutomationConfig,
    library: ScriptLibrary,
    serviceConnected: Boolean,
    viewModel: AutomationViewModel,
    onOpenAccessibility: () -> Unit,
    onShowControls: () -> Unit,
    onExport: () -> Unit,
    onImport: () -> Unit,
    onPickCoordinates: (String) -> Unit,
    onPickAllCoordinates: () -> Unit,
    onPickColor: (String, String) -> Unit,
) {
    val focusManager = LocalFocusManager.current
    val jumpPaths = remember(config) { config.collectActionPaths() }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("AutoClickerPlus") },
                actions = {
                    ScriptMenu(
                        library = library,
                        onSelect = viewModel::selectScript,
                    )
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp)
                .clickable(
                    indication = null,
                    interactionSource = remember { MutableInteractionSource() },
                ) { focusManager.clearFocus() },
        ) {
            ScriptSection(library, viewModel, onExport, onImport)
            ServiceSection(serviceConnected, onOpenAccessibility, onShowControls)
            RepeatSection(config, viewModel)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 12.dp, bottom = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = "フロー（上から順に実行）",
                    style = MaterialTheme.typography.titleMedium,
                )
                if (serviceConnected) {
                    OutlinedButton(onClick = onPickAllCoordinates) {
                        Text("全位置を一括指定")
                    }
                }
            }
            LazyColumn(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                itemsIndexed(config.actions, key = { _, action -> action.id }) { index, action ->
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        ActionTreeCard(
                            path = "${index + 1}",
                            action = action,
                            rootNumber = index + 1,
                            jumpPaths = jumpPaths,
                            canMoveUp = index > 0,
                            canMoveDown = index < config.actions.lastIndex,
                            viewModel = viewModel,
                            onPickCoordinates = onPickCoordinates,
                            onPickColor = onPickColor,
                        )
                        if (index < config.actions.lastIndex) {
                            FlowArrow()
                        }
                    }
                }
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Button(onClick = viewModel::addTap, modifier = Modifier.weight(1f)) {
                    Text("タップ")
                }
                Button(onClick = viewModel::addSwipe, modifier = Modifier.weight(1f)) {
                    Text("スクロール")
                }
                Button(onClick = viewModel::addIf, modifier = Modifier.weight(1f)) {
                    Text("IF")
                }
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp, bottom = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Button(onClick = viewModel::addWait, modifier = Modifier.weight(1f)) {
                    Text("待機")
                }
                Button(onClick = viewModel::addJumpTo, modifier = Modifier.weight(1f)) {
                    Text("番号へ")
                }
                Button(onClick = viewModel::addBreak, modifier = Modifier.weight(1f)) {
                    Text("ループ終了")
                }
            }
        }
    }
}

private enum class IfEditTab { CONDITION, THEN, ELSE }

@Composable
private fun ScriptMenu(
    library: ScriptLibrary,
    onSelect: (String) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        TextButton(onClick = { expanded = true }) {
            Text(
                library.activeScript.name,
                maxLines = 1,
            )
            Text(" ▼")
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
        ) {
            library.scripts.forEach { script ->
                DropdownMenuItem(
                    text = {
                        Text(
                            if (script.id == library.activeScriptId) {
                                "✓ ${script.name}"
                            } else {
                                script.name
                            },
                        )
                    },
                    onClick = {
                        onSelect(script.id)
                        expanded = false
                    },
                )
            }
        }
    }
}

@Composable
private fun ScriptSection(
    library: ScriptLibrary,
    viewModel: AutomationViewModel,
    onExport: () -> Unit,
    onImport: () -> Unit,
) {
    val active = library.activeScript
    var pickerOpen by remember { mutableStateOf(false) }
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 8.dp),
    ) {
        Column(Modifier.padding(10.dp)) {
            Text("スクリプト", style = MaterialTheme.typography.titleMedium)
            Box(modifier = Modifier.fillMaxWidth()) {
                OutlinedButton(
                    onClick = { pickerOpen = true },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        active.name,
                        modifier = Modifier.weight(1f),
                        maxLines = 1,
                    )
                    Text("▼")
                }
                DropdownMenu(
                    expanded = pickerOpen,
                    onDismissRequest = { pickerOpen = false },
                ) {
                    library.scripts.forEach { script ->
                        DropdownMenuItem(
                            text = {
                                Text(
                                    if (script.id == active.id) {
                                        "✓ ${script.name}"
                                    } else {
                                        script.name
                                    },
                                )
                            },
                            onClick = {
                                viewModel.selectScript(script.id)
                                pickerOpen = false
                            },
                        )
                    }
                }
            }
            Text(
                "${library.scripts.size} 件から切り替え",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp, bottom = 4.dp),
            )
            Column {
                CommitTextField(
                    value = active.name,
                    label = "スクリプト名",
                    modifier = Modifier.fillMaxWidth(),
                    onCommit = { viewModel.renameActiveScript(it) },
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    OutlinedButton(onClick = { viewModel.addScript("新しいスクリプト") }) {
                        Text("新規")
                    }
                    OutlinedButton(onClick = viewModel::duplicateActiveScript) {
                        Text("複製")
                    }
                    OutlinedButton(
                        onClick = viewModel::deleteActiveScript,
                        enabled = library.scripts.size > 1,
                    ) {
                        Text("削除")
                    }
                    OutlinedButton(onClick = onExport) { Text("全件Export") }
                    OutlinedButton(onClick = onImport) { Text("Import") }
                }
            }
        }
    }
}

@Composable
private fun ServiceSection(
    connected: Boolean,
    onOpenAccessibility: () -> Unit,
    onShowControls: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            Text(if (connected) "操作サービス: 有効" else "操作サービス: 無効")
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onOpenAccessibility) {
                    Text("アクセシビリティ設定")
                }
                if (connected) {
                    OutlinedButton(onClick = onShowControls) {
                        Text("操作パネルを表示")
                    }
                }
            }
        }
    }
}

@Composable
private fun RepeatSection(config: AutomationConfig, viewModel: AutomationViewModel) {
    Column(Modifier.padding(top = 10.dp)) {
        Text("繰り返し", style = MaterialTheme.typography.titleMedium)
        Row(verticalAlignment = Alignment.CenterVertically) {
            RadioButton(
                selected = config.repeatMode == RepeatMode.INFINITE,
                onClick = { viewModel.setRepeatMode(RepeatMode.INFINITE) },
            )
            Text("STOPまで")
            RadioButton(
                selected = config.repeatMode == RepeatMode.COUNT,
                onClick = { viewModel.setRepeatMode(RepeatMode.COUNT) },
            )
            Text("回数指定")
            if (config.repeatMode == RepeatMode.COUNT) {
                CommitNumberField(
                    value = config.repeatCount.toString(),
                    label = "回",
                    modifier = Modifier
                        .padding(start = 8.dp)
                        .weight(1f),
                    onCommit = { value ->
                        value.toIntOrNull()?.let(viewModel::setRepeatCount)
                    },
                )
            }
        }
    }
}

@Composable
private fun FlowArrow() {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier
                .width(2.dp)
                .height(10.dp)
                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.45f)),
        )
        Text(
            "▼",
            color = MaterialTheme.colorScheme.primary,
            style = MaterialTheme.typography.labelSmall,
        )
    }
}

@Composable
private fun FlowNodeHeader(
    path: String,
    title: String,
    summary: String,
    expanded: Boolean,
    accent: Color,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    onToggle: () -> Unit,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    onRemove: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(16.dp))
                .background(accent)
                .clickable(onClick = onToggle)
                .padding(horizontal = 8.dp, vertical = 6.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                path,
                style = MaterialTheme.typography.labelSmall,
                color = Color.White,
            )
        }
        Column(
            Modifier
                .weight(1f)
                .padding(start = 10.dp)
                .clickable(onClick = onToggle),
        ) {
            Text(
                "$title  ${if (expanded) "▲ 詳細" else "▼ 詳細"}",
                style = MaterialTheme.typography.titleSmall,
            )
            Text(
                summary,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        TextButton(onClick = onMoveUp, enabled = canMoveUp) { Text("↑") }
        TextButton(onClick = onMoveDown, enabled = canMoveDown) { Text("↓") }
        TextButton(onClick = onRemove) { Text("削除") }
    }
}

@Composable
private fun CommitNumberField(
    value: String,
    label: String,
    modifier: Modifier = Modifier,
    decimal: Boolean = false,
    onCommit: (String) -> Unit,
) {
    val focusManager = LocalFocusManager.current
    var text by remember { mutableStateOf(value) }
    var focused by remember { mutableStateOf(false) }
    if (!focused && text != value) {
        text = value
    }
    DisposableEffect(Unit) {
        onDispose {
            if (focused) onCommit(text)
        }
    }
    OutlinedTextField(
        value = text,
        onValueChange = { text = it },
        label = { Text(label) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(
            keyboardType = if (decimal) KeyboardType.Decimal else KeyboardType.Number,
            imeAction = ImeAction.Done,
        ),
        keyboardActions = KeyboardActions(onDone = {
            onCommit(text)
            focusManager.clearFocus()
        }),
        modifier = modifier.onFocusChanged { state ->
            val wasFocused = focused
            focused = state.isFocused
            if (wasFocused && !state.isFocused) {
                onCommit(text)
            }
        },
    )
}

@Composable
private fun CommitTextField(
    value: String,
    label: String,
    modifier: Modifier = Modifier,
    onCommit: (String) -> Unit,
) {
    val focusManager = LocalFocusManager.current
    var text by remember { mutableStateOf(value) }
    var focused by remember { mutableStateOf(false) }
    if (!focused && text != value) {
        text = value
    }
    DisposableEffect(Unit) {
        onDispose {
            if (focused) onCommit(text)
        }
    }
    OutlinedTextField(
        value = text,
        onValueChange = { text = it },
        label = { Text(label) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = {
            onCommit(text)
            focusManager.clearFocus()
        }),
        modifier = modifier.onFocusChanged { state ->
            val wasFocused = focused
            focused = state.isFocused
            if (wasFocused && !state.isFocused) {
                onCommit(text)
            }
        },
    )
}

@Composable
private fun WaitWithJitterFields(
    waitMs: Long,
    jitterMs: Int,
    waitLabel: String,
    onWait: (Long) -> Unit,
    onJitter: (Int) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CommitNumberField(
            value = waitMs.toString(),
            label = waitLabel,
            modifier = Modifier.weight(1f),
            onCommit = { value ->
                value.toLongOrNull()?.let { onWait(it.coerceAtLeast(0L)) }
            },
        )
        CommitNumberField(
            value = jitterMs.toString(),
            label = "揺らぎ ±ms",
            modifier = Modifier.weight(1f),
            onCommit = { value ->
                value.toIntOrNull()?.let { onJitter(it.coerceIn(0, MAX_WAIT_JITTER_MS)) }
            },
        )
    }
}

@Composable
private fun ActionTreeCard(
    path: String,
    action: AutomationAction,
    rootNumber: Int,
    jumpPaths: List<String>,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    viewModel: AutomationViewModel,
    onPickCoordinates: (String) -> Unit,
    onPickColor: (String, String) -> Unit,
) {
    when (action) {
        is AutomationAction.Tap, is AutomationAction.Swipe -> ActionCard(
            path = path,
            action = action,
            canMoveUp = canMoveUp,
            canMoveDown = canMoveDown,
            onReplace = viewModel::replace,
            onRemove = { viewModel.remove(action.id) },
            onMoveUp = { viewModel.move(action.id, -1) },
            onMoveDown = { viewModel.move(action.id, 1) },
            onPickCoordinates = { onPickCoordinates(action.id) },
        )
        is AutomationAction.Wait -> WaitCard(
            path = path,
            action = action,
            canMoveUp = canMoveUp,
            canMoveDown = canMoveDown,
            onReplace = viewModel::replace,
            onRemove = { viewModel.remove(action.id) },
            onMoveUp = { viewModel.move(action.id, -1) },
            onMoveDown = { viewModel.move(action.id, 1) },
        )
        is AutomationAction.JumpTo -> JumpToCard(
            path = path,
            action = action,
            jumpPaths = jumpPaths,
            canMoveUp = canMoveUp,
            canMoveDown = canMoveDown,
            onReplace = viewModel::replace,
            onRemove = { viewModel.remove(action.id) },
            onMoveUp = { viewModel.move(action.id, -1) },
            onMoveDown = { viewModel.move(action.id, 1) },
        )
        is AutomationAction.BreakLoop -> BreakLoopCard(
            path = path,
            action = action,
            canMoveUp = canMoveUp,
            canMoveDown = canMoveDown,
            onRemove = { viewModel.remove(action.id) },
            onMoveUp = { viewModel.move(action.id, -1) },
            onMoveDown = { viewModel.move(action.id, 1) },
        )
        is AutomationAction.IfBlock -> IfBlockCard(
            path = path,
            block = action,
            rootNumber = rootNumber,
            jumpPaths = jumpPaths,
            canMoveUp = canMoveUp,
            canMoveDown = canMoveDown,
            viewModel = viewModel,
            onPickCoordinates = onPickCoordinates,
            onPickColor = onPickColor,
        )
    }
}

@Composable
private fun IfBlockCard(
    path: String,
    block: AutomationAction.IfBlock,
    rootNumber: Int,
    jumpPaths: List<String>,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    viewModel: AutomationViewModel,
    onPickCoordinates: (String) -> Unit,
    onPickColor: (String, String) -> Unit,
) {
    var expanded by remember(block.id) { mutableStateOf(false) }
    var tab by remember(block.id) { mutableStateOf(IfEditTab.CONDITION) }
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            FlowNodeHeader(
                path = path,
                title = "IF",
                summary = block.flowSummary(),
                expanded = expanded,
                accent = Color(0xFF6A4C93),
                canMoveUp = canMoveUp,
                canMoveDown = canMoveDown,
                onToggle = { expanded = !expanded },
                onMoveUp = { viewModel.move(block.id, -1) },
                onMoveDown = { viewModel.move(block.id, 1) },
                onRemove = { viewModel.remove(block.id) },
            )
            if (expanded) {
                TabRow(
                    selectedTabIndex = tab.ordinal,
                    containerColor = Color.Transparent,
                    modifier = Modifier.padding(top = 4.dp),
                ) {
                    Tab(
                        selected = tab == IfEditTab.CONDITION,
                        onClick = { tab = IfEditTab.CONDITION },
                        text = { Text("条件 (${block.conditions.size})", maxLines = 1) },
                    )
                    Tab(
                        selected = tab == IfEditTab.THEN,
                        onClick = { tab = IfEditTab.THEN },
                        text = { Text("成立時 (${block.thenActions.size})", maxLines = 1) },
                    )
                    Tab(
                        selected = tab == IfEditTab.ELSE,
                        onClick = { tab = IfEditTab.ELSE },
                        text = { Text("不成立時 (${block.elseActions.size})", maxLines = 1) },
                    )
                }
                when (tab) {
                    IfEditTab.CONDITION -> {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(top = 4.dp),
                        ) {
                            Text("条件の結び", style = MaterialTheme.typography.bodySmall)
                            TextButton(
                                onClick = {
                                    viewModel.replace(
                                        block.copy(
                                            operator = if (block.operator == ConditionOperator.AND) {
                                                ConditionOperator.OR
                                            } else {
                                                ConditionOperator.AND
                                            },
                                        ),
                                    )
                                },
                            ) { Text(block.operator.name) }
                        }
                        WaitWithJitterFields(
                            waitMs = block.waitAfterMs,
                            jitterMs = block.waitJitterMs,
                            waitLabel = "次の動作までの待機時間 ms",
                            onWait = { viewModel.replace(block.copy(waitAfterMs = it)) },
                            onJitter = { viewModel.replace(block.copy(waitJitterMs = it)) },
                        )
                        block.conditions.forEach { condition ->
                            ConditionEditor(
                                condition = condition,
                                onReplace = { viewModel.replaceCondition(block.id, it) },
                                onRemove = { viewModel.removeCondition(block.id, condition.id) },
                                onPickColor = { onPickColor(block.id, condition.id) },
                            )
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            TextButton(onClick = {
                                viewModel.addCondition(block.id, AutomationCondition.TextExists())
                            }) { Text("+文字") }
                            TextButton(onClick = {
                                viewModel.addCondition(block.id, AutomationCondition.UiState())
                            }) { Text("+活性") }
                            TextButton(onClick = {
                                viewModel.addCondition(block.id, AutomationCondition.PixelColor())
                            }) { Text("+色") }
                        }
                    }
                    IfEditTab.THEN -> BranchEditor(
                        pathPrefix = "$path-T",
                        actions = block.thenActions,
                        rootNumber = rootNumber,
                        jumpPaths = jumpPaths,
                        blockId = block.id,
                        side = BranchSide.THEN,
                        viewModel = viewModel,
                        onPickCoordinates = onPickCoordinates,
                        onPickColor = onPickColor,
                    )
                    IfEditTab.ELSE -> BranchEditor(
                        pathPrefix = "$path-E",
                        actions = block.elseActions,
                        rootNumber = rootNumber,
                        jumpPaths = jumpPaths,
                        blockId = block.id,
                        side = BranchSide.ELSE,
                        viewModel = viewModel,
                        onPickCoordinates = onPickCoordinates,
                        onPickColor = onPickColor,
                    )
                }
            }
        }
    }
}

@Composable
private fun ConditionEditor(
    condition: AutomationCondition,
    onReplace: (AutomationCondition) -> Unit,
    onRemove: () -> Unit,
    onPickColor: () -> Unit,
) {
    Column(Modifier.padding(start = 8.dp, bottom = 6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                condition.summary(),
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodyMedium,
            )
            TextButton(onClick = onRemove) { Text("条件削除") }
        }
        when (condition) {
            is AutomationCondition.TextExists -> {
                CommitTextField(
                    value = condition.query,
                    label = "検索文字",
                    modifier = Modifier.fillMaxWidth(),
                    onCommit = { onReplace(condition.copy(query = it)) },
                )
                TextButton(onClick = {
                    onReplace(condition.copy(matchMode = condition.matchMode.toggled()))
                }) { Text("一致方法: ${condition.matchMode.label}") }
            }
            is AutomationCondition.UiState -> {
                CommitTextField(
                    value = condition.query,
                    label = "対象文字",
                    modifier = Modifier.fillMaxWidth(),
                    onCommit = { onReplace(condition.copy(query = it)) },
                )
                Row {
                    TextButton(onClick = {
                        onReplace(condition.copy(matchMode = condition.matchMode.toggled()))
                    }) { Text(condition.matchMode.label) }
                    TextButton(onClick = {
                        onReplace(condition.copy(
                            expectedEnabled = condition.expectedEnabled.nextExpected(),
                        ))
                    }) { Text("enabled: ${condition.expectedEnabled.expectedLabel}") }
                    TextButton(onClick = {
                        onReplace(condition.copy(
                            expectedClickable = condition.expectedClickable.nextExpected(),
                        ))
                    }) { Text("clickable: ${condition.expectedClickable.expectedLabel}") }
                }
            }
            is AutomationCondition.PixelColor -> {
                OutlinedButton(onClick = onPickColor) {
                    Text("画面から色を取得")
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    CommitNumberField(
                        value = condition.x.toString(),
                        label = "X",
                        modifier = Modifier.weight(1f),
                        onCommit = { value ->
                            value.toIntOrNull()?.let { onReplace(condition.copy(x = it)) }
                        },
                    )
                    CommitNumberField(
                        value = condition.y.toString(),
                        label = "Y",
                        modifier = Modifier.weight(1f),
                        onCommit = { value ->
                            value.toIntOrNull()?.let { onReplace(condition.copy(y = it)) }
                        },
                    )
                }
                CommitTextField(
                    value = String.format("#%08X", condition.argb),
                    label = "ARGB色",
                    modifier = Modifier.fillMaxWidth(),
                    onCommit = { value ->
                        value.removePrefix("#").toLongOrNull(16)?.let {
                            onReplace(condition.copy(argb = it.toInt()))
                        }
                    },
                )
                CommitNumberField(
                    value = condition.tolerance.toString(),
                    label = "色の許容差 0～255",
                    modifier = Modifier.fillMaxWidth(),
                    onCommit = { value ->
                        value.toIntOrNull()?.let {
                            onReplace(condition.copy(tolerance = it.coerceIn(0, 255)))
                        }
                    },
                )
            }
        }
    }
}

@Composable
private fun BranchEditor(
    pathPrefix: String,
    actions: List<AutomationAction>,
    rootNumber: Int,
    jumpPaths: List<String>,
    blockId: String,
    side: BranchSide,
    viewModel: AutomationViewModel,
    onPickCoordinates: (String) -> Unit,
    onPickColor: (String, String) -> Unit,
) {
    Column(
        Modifier
            .padding(top = 8.dp)
            .background(
                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
                shape = RoundedCornerShape(10.dp),
            )
            .padding(8.dp),
    ) {
        if (actions.isEmpty()) {
            Text(
                "（空）",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        actions.forEachIndexed { index, child ->
            if (index > 0) {
                FlowArrow()
            }
            ActionTreeCard(
                path = "$pathPrefix${index + 1}",
                action = child,
                rootNumber = rootNumber,
                jumpPaths = jumpPaths,
                canMoveUp = index > 0,
                canMoveDown = index < actions.lastIndex,
                viewModel = viewModel,
                onPickCoordinates = onPickCoordinates,
                onPickColor = onPickColor,
            )
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            TextButton(onClick = {
                viewModel.addToBranch(blockId, side, AutomationAction.Tap())
            }) { Text("+タップ") }
            TextButton(onClick = {
                viewModel.addToBranch(blockId, side, AutomationAction.Swipe())
            }) { Text("+スクロール") }
            TextButton(onClick = {
                viewModel.addToBranch(blockId, side, AutomationAction.IfBlock())
            }) { Text("+IF") }
            TextButton(onClick = {
                viewModel.addToBranch(blockId, side, AutomationAction.BreakLoop())
            }) { Text("+終了") }
            TextButton(onClick = {
                viewModel.addToBranch(blockId, side, AutomationAction.Wait())
            }) { Text("+待機") }
            TextButton(onClick = {
                viewModel.addToBranch(blockId, side, AutomationAction.JumpTo())
            }) { Text("+番号へ") }
        }
    }
}

private fun TextMatchMode.toggled() =
    if (this == TextMatchMode.EXACT) TextMatchMode.CONTAINS else TextMatchMode.EXACT

private val TextMatchMode.label: String
    get() = if (this == TextMatchMode.EXACT) "完全一致" else "部分一致"

private fun Boolean?.nextExpected(): Boolean? = when (this) {
    null -> true
    true -> false
    false -> null
}

private val Boolean?.expectedLabel: String
    get() = when (this) {
        null -> "任意"
        true -> "true"
        false -> "false"
    }

@Composable
private fun WaitCard(
    path: String,
    action: AutomationAction.Wait,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    onReplace: (AutomationAction) -> Unit,
    onRemove: () -> Unit,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
) {
    var expanded by remember(action.id) { mutableStateOf(true) }
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            FlowNodeHeader(
                path = path,
                title = action.flowTitle(),
                summary = action.flowSummary(),
                expanded = expanded,
                accent = Color(0xFF4A5568),
                canMoveUp = canMoveUp,
                canMoveDown = canMoveDown,
                onToggle = { expanded = !expanded },
                onMoveUp = onMoveUp,
                onMoveDown = onMoveDown,
                onRemove = onRemove,
            )
            if (expanded) {
                CommitNumberField(
                    value = action.durationMs.toString(),
                    label = "待機 ms",
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 6.dp),
                    onCommit = { value ->
                        value.toLongOrNull()?.let { ms ->
                            onReplace(action.copy(durationMs = ms.coerceAtLeast(0L)))
                        }
                    },
                )
                CommitNumberField(
                    value = action.waitJitterMs.toString(),
                    label = "揺らぎ ±ms",
                    modifier = Modifier.fillMaxWidth(),
                    onCommit = { value ->
                        value.toIntOrNull()?.let {
                            onReplace(action.copy(waitJitterMs = it.coerceIn(0, MAX_WAIT_JITTER_MS)))
                        }
                    },
                )
            }
        }
    }
}

@Composable
private fun JumpToCard(
    path: String,
    action: AutomationAction.JumpTo,
    jumpPaths: List<String>,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    onReplace: (AutomationAction) -> Unit,
    onRemove: () -> Unit,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
) {
    var expanded by remember(action.id) { mutableStateOf(true) }
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            FlowNodeHeader(
                path = path,
                title = action.flowTitle(),
                summary = action.flowSummary(path),
                expanded = expanded,
                accent = Color(0xFF6A4C93),
                canMoveUp = canMoveUp,
                canMoveDown = canMoveDown,
                onToggle = { expanded = !expanded },
                onMoveUp = onMoveUp,
                onMoveDown = onMoveDown,
                onRemove = onRemove,
            )
            if (expanded) {
                CommitTextField(
                    value = action.resolvedTargetPath(),
                    label = "移動先（1, 2-T1 など）",
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 6.dp),
                    onCommit = { value ->
                        val trimmed = value.trim()
                        if (trimmed.isNotEmpty()) {
                            onReplace(action.copy(targetPath = trimmed))
                        }
                    },
                )
                Text(
                    "分岐内も指定できます（例: 2-T1, 2-E2）",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp),
                )
                LazyRow(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    items(jumpPaths.filter { it != path }) { targetPath ->
                        TextButton(onClick = {
                            onReplace(action.copy(targetPath = targetPath))
                        }) { Text(targetPath) }
                    }
                }
                WaitWithJitterFields(
                    waitMs = action.waitAfterMs,
                    jitterMs = action.waitJitterMs,
                    waitLabel = "次の動作までの待機時間 ms",
                    onWait = { onReplace(action.withWait(it)) },
                    onJitter = { onReplace(action.withWaitJitter(it)) },
                )
            }
        }
    }
}

@Composable
private fun BreakLoopCard(
    path: String,
    action: AutomationAction.BreakLoop,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    onRemove: () -> Unit,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
) {
    var expanded by remember(action.id) { mutableStateOf(false) }
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            FlowNodeHeader(
                path = path,
                title = action.flowTitle(),
                summary = action.flowSummary(),
                expanded = expanded,
                accent = Color(0xFFB5651D),
                canMoveUp = canMoveUp,
                canMoveDown = canMoveDown,
                onToggle = { expanded = !expanded },
                onMoveUp = onMoveUp,
                onMoveDown = onMoveDown,
                onRemove = onRemove,
            )
            if (expanded) {
                Text(
                    "この操作に到達すると繰り返しを終了します",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
        }
    }
}

@Composable
private fun ActionCard(
    path: String,
    action: AutomationAction,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    onReplace: (AutomationAction) -> Unit,
    onRemove: () -> Unit,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    onPickCoordinates: () -> Unit,
) {
    var expanded by remember(action.id) { mutableStateOf(false) }
    val accent = if (action is AutomationAction.Tap) {
        Color(0xFF2E86AB)
    } else {
        Color(0xFF2A9D8F)
    }
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            FlowNodeHeader(
                path = path,
                title = action.flowTitle(),
                summary = action.flowSummary(),
                expanded = expanded,
                accent = accent,
                canMoveUp = canMoveUp,
                canMoveDown = canMoveDown,
                onToggle = { expanded = !expanded },
                onMoveUp = onMoveUp,
                onMoveDown = onMoveDown,
                onRemove = onRemove,
            )

            if (expanded) {
                OutlinedButton(
                    onClick = onPickCoordinates,
                    modifier = Modifier.padding(top = 6.dp),
                ) { Text("画面上で位置を指定") }
                Text(
                    text = when (action) {
                        is AutomationAction.Tap ->
                            "座標 (${action.x.roundToInt()}, ${action.y.roundToInt()})"
                        is AutomationAction.Swipe ->
                            "開始 (${action.startX.roundToInt()}, ${action.startY.roundToInt()})" +
                                " → 終了 (${action.endX.roundToInt()}, ${action.endY.roundToInt()})"
                        else -> ""
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp),
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    CommitNumberField(
                        value = action.waitAfterMs.toString(),
                        label = "次の動作までの待機時間 ms",
                        modifier = Modifier.weight(1f),
                        onCommit = { value ->
                            value.toLongOrNull()?.let { wait ->
                                onReplace(action.withWait(wait))
                            }
                        },
                    )
                    CommitNumberField(
                        value = action.waitJitterMs.toString(),
                        label = "揺らぎ ±ms",
                        modifier = Modifier.weight(1f),
                        onCommit = { value ->
                            value.toIntOrNull()?.let { jitter ->
                                onReplace(action.withWaitJitter(jitter))
                            }
                        },
                    )
                    if (action is AutomationAction.Swipe) {
                        CommitNumberField(
                            value = action.durationMs.toString(),
                            label = "動作時間 ms",
                            modifier = Modifier.weight(1f),
                            onCommit = { value ->
                                value.toLongOrNull()?.let { duration ->
                                    onReplace(action.copy(durationMs = duration.coerceIn(100, 2_000)))
                                }
                            },
                        )
                    }
                }

                if (action is AutomationAction.Swipe) {
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        TextButton(onClick = {
                            onReplace(action.copy(fullScroll = !action.fullScroll))
                        }) {
                            Text(if (action.fullScroll) "端までスクロール: ON" else "端までスクロール: OFF")
                        }
                        TextButton(onClick = {
                            onReplace(action.copy(stopAtEnd = !action.stopAtEnd))
                        }) {
                            Text(if (action.stopAtEnd) "最後で止める: ON" else "最後で止める: OFF")
                        }
                    }
                }

                CommitNumberField(
                    value = action.jitterPx.toString(),
                    label = "位置の揺らぎ ±px（0でなし）",
                    modifier = Modifier.fillMaxWidth(),
                    onCommit = { value ->
                        value.toIntOrNull()?.let { onReplace(action.withJitter(it)) }
                    },
                )
            }
        }
    }
}

private fun AutomationAction.withWait(waitMs: Long): AutomationAction = when (this) {
    is AutomationAction.Tap -> copy(waitAfterMs = waitMs.coerceAtLeast(0L))
    is AutomationAction.Swipe -> copy(waitAfterMs = waitMs.coerceAtLeast(0L))
    is AutomationAction.IfBlock -> copy(waitAfterMs = waitMs.coerceAtLeast(0L))
    is AutomationAction.BreakLoop -> this
    is AutomationAction.Wait -> this
    is AutomationAction.JumpTo -> copy(waitAfterMs = waitMs.coerceAtLeast(0L))
}

private fun AutomationAction.withWaitJitter(jitterMs: Int): AutomationAction = when (this) {
    is AutomationAction.Tap -> copy(waitJitterMs = jitterMs.coerceIn(0, MAX_WAIT_JITTER_MS))
    is AutomationAction.Swipe -> copy(waitJitterMs = jitterMs.coerceIn(0, MAX_WAIT_JITTER_MS))
    is AutomationAction.IfBlock -> copy(waitJitterMs = jitterMs.coerceIn(0, MAX_WAIT_JITTER_MS))
    is AutomationAction.BreakLoop -> this
    is AutomationAction.Wait -> copy(waitJitterMs = jitterMs.coerceIn(0, MAX_WAIT_JITTER_MS))
    is AutomationAction.JumpTo -> copy(waitJitterMs = jitterMs.coerceIn(0, MAX_WAIT_JITTER_MS))
}

private fun AutomationAction.withJitter(jitterPx: Int): AutomationAction = when (this) {
    is AutomationAction.Tap -> copy(jitterPx = jitterPx.coerceIn(0, MAX_POSITION_JITTER_PX))
    is AutomationAction.Swipe -> copy(jitterPx = jitterPx.coerceIn(0, MAX_POSITION_JITTER_PX))
    is AutomationAction.IfBlock -> this
    is AutomationAction.BreakLoop -> this
    is AutomationAction.Wait -> this
    is AutomationAction.JumpTo -> this
}
