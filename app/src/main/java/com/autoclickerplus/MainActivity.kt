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
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.rememberScrollState
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.autoclickerplus.model.AutomationAction
import com.autoclickerplus.model.AutomationCondition
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
    onPickColor: (String, String) -> Unit,
) {
    Scaffold(
        topBar = { TopAppBar(title = { Text("AutoClickerPlus") }) },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp),
        ) {
            ScriptSection(library, viewModel, onExport, onImport)
            ServiceSection(serviceConnected, onOpenAccessibility, onShowControls)
            RepeatSection(config, viewModel)
            Text(
                text = "アクション（上から順に実行）",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(top = 12.dp, bottom = 6.dp),
            )
            LazyColumn(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                itemsIndexed(config.actions, key = { _, action -> action.id }) { index, action ->
                    ActionTreeCard(
                        path = "${index + 1}",
                        action = action,
                        canMoveUp = index > 0,
                        canMoveDown = index < config.actions.lastIndex,
                        viewModel = viewModel,
                        onPickCoordinates = onPickCoordinates,
                        onPickColor = onPickColor,
                    )
                }
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Button(onClick = viewModel::addTap, modifier = Modifier.weight(1f)) {
                    Text("タップを追加")
                }
                Button(onClick = viewModel::addSwipe, modifier = Modifier.weight(1f)) {
                    Text("スクロールを追加")
                }
                Button(onClick = viewModel::addIf, modifier = Modifier.weight(1f)) {
                    Text("IFを追加")
                }
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
    var scriptName by remember(active.id, active.name) { mutableStateOf(active.name) }
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 8.dp),
    ) {
        Column(Modifier.padding(10.dp)) {
            Text("保存スクリプト", style = MaterialTheme.typography.titleMedium)
            LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                items(library.scripts, key = { it.id }) { script ->
                    if (script.id == active.id) {
                        Button(onClick = { viewModel.selectScript(script.id) }) {
                            Text(script.name)
                        }
                    } else {
                        OutlinedButton(onClick = { viewModel.selectScript(script.id) }) {
                            Text(script.name)
                        }
                    }
                }
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                OutlinedTextField(
                    value = scriptName,
                    onValueChange = { scriptName = it },
                    label = { Text("スクリプト名") },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
                Button(onClick = { viewModel.renameActiveScript(scriptName) }) {
                    Text("保存")
                }
            }
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
                OutlinedTextField(
                    value = config.repeatCount.toString(),
                    onValueChange = { value ->
                        value.toIntOrNull()?.let(viewModel::setRepeatCount)
                    },
                    label = { Text("回") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier
                        .padding(start = 8.dp)
                        .weight(1f),
                )
            }
        }
    }
}

@Composable
private fun ActionTreeCard(
    path: String,
    action: AutomationAction,
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
        is AutomationAction.IfBlock -> IfBlockCard(
            path = path,
            block = action,
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
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    viewModel: AutomationViewModel,
    onPickCoordinates: (String) -> Unit,
    onPickColor: (String, String) -> Unit,
) {
    var expanded by remember(block.id) { mutableStateOf(true) }
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("IF $path", style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f))
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
                TextButton(onClick = { expanded = !expanded }) {
                    Text(if (expanded) "閉じる" else "開く")
                }
                TextButton(onClick = { viewModel.move(block.id, -1) }, enabled = canMoveUp) {
                    Text("↑")
                }
                TextButton(onClick = { viewModel.move(block.id, 1) }, enabled = canMoveDown) {
                    Text("↓")
                }
                TextButton(onClick = { viewModel.remove(block.id) }) { Text("削除") }
            }
            OutlinedTextField(
                value = block.waitAfterMs.toString(),
                onValueChange = { value ->
                    value.toLongOrNull()?.let { wait ->
                        viewModel.replace(block.copy(waitAfterMs = wait.coerceAtLeast(0L)))
                    }
                },
                label = { Text("次の動作までの待機時間 ms (±30)") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth(),
            )
            if (expanded) {
            Text("条件", style = MaterialTheme.typography.titleSmall)
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
            BranchEditor(
                title = "THEN",
                pathPrefix = "$path-T",
                actions = block.thenActions,
                blockId = block.id,
                side = BranchSide.THEN,
                viewModel = viewModel,
                onPickCoordinates = onPickCoordinates,
                onPickColor = onPickColor,
            )
            BranchEditor(
                title = "ELSE",
                pathPrefix = "$path-E",
                actions = block.elseActions,
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
                when (condition) {
                    is AutomationCondition.TextExists -> "文字存在"
                    is AutomationCondition.UiState -> "活性状態"
                    is AutomationCondition.PixelColor -> "画面色"
                },
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onRemove) { Text("条件削除") }
        }
        when (condition) {
            is AutomationCondition.TextExists -> {
                OutlinedTextField(
                    value = condition.query,
                    onValueChange = { onReplace(condition.copy(query = it)) },
                    label = { Text("検索文字") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                TextButton(onClick = {
                    onReplace(condition.copy(matchMode = condition.matchMode.toggled()))
                }) { Text("一致方法: ${condition.matchMode.label}") }
            }
            is AutomationCondition.UiState -> {
                OutlinedTextField(
                    value = condition.query,
                    onValueChange = { onReplace(condition.copy(query = it)) },
                    label = { Text("対象文字") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
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
                    OutlinedTextField(
                        value = condition.x.toString(),
                        onValueChange = { value ->
                            value.toIntOrNull()?.let { onReplace(condition.copy(x = it)) }
                        },
                        label = { Text("X") },
                        modifier = Modifier.weight(1f),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    )
                    OutlinedTextField(
                        value = condition.y.toString(),
                        onValueChange = { value ->
                            value.toIntOrNull()?.let { onReplace(condition.copy(y = it)) }
                        },
                        label = { Text("Y") },
                        modifier = Modifier.weight(1f),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    )
                }
                OutlinedTextField(
                    value = String.format("#%08X", condition.argb),
                    onValueChange = { value ->
                        value.removePrefix("#").toLongOrNull(16)?.let {
                            onReplace(condition.copy(argb = it.toInt()))
                        }
                    },
                    label = { Text("ARGB色") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = condition.tolerance.toString(),
                    onValueChange = { value ->
                        value.toIntOrNull()?.let {
                            onReplace(condition.copy(tolerance = it.coerceIn(0, 255)))
                        }
                    },
                    label = { Text("色の許容差 0～255") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

@Composable
private fun BranchEditor(
    title: String,
    pathPrefix: String,
    actions: List<AutomationAction>,
    blockId: String,
    side: BranchSide,
    viewModel: AutomationViewModel,
    onPickCoordinates: (String) -> Unit,
    onPickColor: (String, String) -> Unit,
) {
    Column(Modifier.padding(start = 12.dp, top = 8.dp)) {
        Text(title, style = MaterialTheme.typography.titleSmall)
        actions.forEachIndexed { index, child ->
            ActionTreeCard(
                path = "$pathPrefix${index + 1}",
                action = child,
                canMoveUp = index > 0,
                canMoveDown = index < actions.lastIndex,
                viewModel = viewModel,
                onPickCoordinates = onPickCoordinates,
                onPickColor = onPickColor,
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            TextButton(onClick = {
                viewModel.addToBranch(blockId, side, AutomationAction.Tap())
            }) { Text("+タップ") }
            TextButton(onClick = {
                viewModel.addToBranch(blockId, side, AutomationAction.Swipe())
            }) { Text("+スクロール") }
            TextButton(onClick = {
                viewModel.addToBranch(blockId, side, AutomationAction.IfBlock())
            }) { Text("+IF") }
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
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "$path. ${if (action is AutomationAction.Tap) "タップ" else "スクロール"}",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = onMoveUp, enabled = canMoveUp) { Text("↑") }
                TextButton(onClick = onMoveDown, enabled = canMoveDown) { Text("↓") }
                TextButton(onClick = onRemove) { Text("削除") }
            }

            val coordinateText = when (action) {
                is AutomationAction.Tap ->
                    "位置: (${action.x.roundToInt()}, ${action.y.roundToInt()})"
                is AutomationAction.Swipe ->
                    "開始: (${action.startX.roundToInt()}, ${action.startY.roundToInt()}) → " +
                        "終了: (${action.endX.roundToInt()}, ${action.endY.roundToInt()})"
                is AutomationAction.IfBlock -> ""
            }
            Text(coordinateText)
            OutlinedButton(onClick = onPickCoordinates) { Text("画面上で位置を指定") }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedTextField(
                    value = action.waitAfterMs.toString(),
                    onValueChange = { value ->
                        value.toLongOrNull()?.let { wait ->
                            onReplace(action.withWait(wait))
                        }
                    },
                    label = { Text("次の動作までの待機時間 ms (±30)") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.weight(1f),
                )
                if (action is AutomationAction.Swipe) {
                    OutlinedTextField(
                        value = action.durationMs.toString(),
                        onValueChange = { value ->
                            value.toLongOrNull()?.let { duration ->
                                onReplace(action.copy(durationMs = duration.coerceIn(100, 2_000)))
                            }
                        },
                        label = { Text("動作時間 ms") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.weight(1f),
                    )
                }
            }

            if (action is AutomationAction.Swipe) {
                TextButton(onClick = {
                    onReplace(action.copy(stopAtEnd = !action.stopAtEnd))
                }) {
                    Text(if (action.stopAtEnd) "最後で止める: ON" else "最後で止める: OFF")
                }
            }

            Text("位置の揺らぎ: ±${action.jitterPx}px")
            Slider(
                value = action.jitterPx.toFloat(),
                onValueChange = { onReplace(action.withJitter(it.roundToInt())) },
                valueRange = 3f..10f,
                steps = 6,
            )
        }
    }
}

private fun AutomationAction.withWait(waitMs: Long): AutomationAction = when (this) {
    is AutomationAction.Tap -> copy(waitAfterMs = waitMs.coerceAtLeast(0L))
    is AutomationAction.Swipe -> copy(waitAfterMs = waitMs.coerceAtLeast(0L))
    is AutomationAction.IfBlock -> copy(waitAfterMs = waitMs.coerceAtLeast(0L))
}

private fun AutomationAction.withJitter(jitterPx: Int): AutomationAction = when (this) {
    is AutomationAction.Tap -> copy(jitterPx = jitterPx.coerceIn(3, 10))
    is AutomationAction.Swipe -> copy(jitterPx = jitterPx.coerceIn(3, 10))
    is AutomationAction.IfBlock -> this
}
