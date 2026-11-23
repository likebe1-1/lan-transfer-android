package com.manst.lantransfer.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.manst.lantransfer.R
import com.manst.lantransfer.transfer.TransferTuning
import kotlin.math.roundToInt

/**
 * 传输设置页：调整并发传输路数（1–6 路，默认 5 路）。
 *
 * 无状态 Composable：当前配置由调用方（[com.manst.lantransfer.AppContainer.transferTuning]）提供，
 * 用户拖动滑块后通过 [onSelectMaxConcurrentTasks] 立即写回 SharedPreferences。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TransferTuningScreen(
    tuning: TransferTuning,
    onSelectMaxConcurrentTasks: (Int) -> Unit,
    onBack: () -> Unit
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = stringResource(R.string.transfer_tuning_title),
                        fontWeight = FontWeight.SemiBold
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.cd_back)
                        )
                    }
                }
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(innerPadding)
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            ConcurrencyCard(tuning = tuning, onSelect = onSelectMaxConcurrentTasks)
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = stringResource(R.string.transfer_tuning_concurrency_hint),
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Text(
                        text = stringResource(R.string.transfer_tuning_apply_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@Composable
private fun ConcurrencyCard(tuning: TransferTuning, onSelect: (Int) -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = stringResource(R.string.transfer_tuning_concurrency_title),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    text = stringResource(
                        R.string.transfer_tuning_concurrency_value,
                        tuning.maxConcurrentTasks
                    ),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.primary
                )
            }
            Text(
                text = stringResource(
                    R.string.transfer_tuning_concurrency_range_hint,
                    TransferTuning.DEFAULT
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(4.dp))
            Slider(
                value = tuning.maxConcurrentTasks.toFloat(),
                onValueChange = { onSelect(it.roundToInt()) },
                valueRange = TransferTuning.MIN_MAX_CONCURRENT_TASKS.toFloat()..
                    TransferTuning.MAX_MAX_CONCURRENT_TASKS.toFloat(),
                steps = TransferTuning.MAX_MAX_CONCURRENT_TASKS -
                    TransferTuning.MIN_MAX_CONCURRENT_TASKS - 1
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                for (value in TransferTuning.MIN_MAX_CONCURRENT_TASKS..TransferTuning.MAX_MAX_CONCURRENT_TASKS) {
                    val selected = value == tuning.maxConcurrentTasks
                    Text(
                        text = value.toString(),
                        style = MaterialTheme.typography.labelMedium,
                        color = if (selected) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        }
                    )
                }
            }
        }
    }
}
