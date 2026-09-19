package com.autoclickerplus

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.autoclickerplus.model.AutomationAction
import com.autoclickerplus.model.AutomationConfig
import com.autoclickerplus.model.RepeatMode
import com.autoclickerplus.service.AutoClickAccessibilityService
import kotlin.math.roundToInt

class MainActivity : ComponentActivity() {
    private val viewModel: AutomationViewModel by viewModels()
    private var serviceConnected by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                val config by viewModel.config.collectAsState()
                AutomationScreen(
                    config = config,
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
                    onPickCoordinates = { actionId ->
                        if (!AutoClickAccessibilityService.requestCoordinatePick(actionId)) {
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
    serviceConnected: Boolean,
    viewModel: AutomationViewModel,
    onOpenAccessibility: () -> Unit,
    onShowControls: () -> Unit,
    onPickCoordinates: (String) -> Unit,
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
                    ActionCard(
                        number = index + 1,
                        action = action,
                        canMoveUp = index > 0,
                        canMoveDown = index < config.actions.lastIndex,
                        onReplace = viewModel::replace,
                        onRemove = { viewModel.remove(action.id) },
                        onMoveUp = { viewModel.move(action.id, -1) },
                        onMoveDown = { viewModel.move(action.id, 1) },
                        onPickCoordinates = { onPickCoordinates(action.id) },
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
private fun ActionCard(
    number: Int,
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
                    "$number. ${if (action is AutomationAction.Tap) "タップ" else "スクロール"}",
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
}

private fun AutomationAction.withJitter(jitterPx: Int): AutomationAction = when (this) {
    is AutomationAction.Tap -> copy(jitterPx = jitterPx.coerceIn(3, 10))
    is AutomationAction.Swipe -> copy(jitterPx = jitterPx.coerceIn(3, 10))
}
