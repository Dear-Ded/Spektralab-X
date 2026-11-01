package com.dearded.spektralab

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/**
 * 正在处理: every export, running first, with progress and the time left.
 * A sheet rather than a screen, so the photo being edited stays in view.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QueueSheet(
    jobs: List<ProcessingJob>,
    onCancel: (Long) -> Unit,
    onRetry: (Long) -> Unit,
    onClear: () -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        val active = jobs.filter { it.active }.sortedBy { it.id }
        val done = jobs.filter { !it.active }.sortedByDescending { it.finishedAt }
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp).navigationBarsPadding()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("正在处理", style = MaterialTheme.typography.titleMedium)
                    Text(
                        if (active.isEmpty()) "没有进行中的任务"
                        else "${active.size} 张 · 全部完成还需约 ${ProcessingQueue.formatEta(ProcessingQueue.remainingSeconds(active))}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (done.isNotEmpty()) TextButton(onClick = onClear) { Text("清除已完成") }
            }
            Text(
                "可以切到后台，进度会显示在通知和灵动岛里。若被系统中断，下次打开应用会自动继续。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(vertical = 6.dp),
            )
            LazyColumn(Modifier.fillMaxWidth().height(360.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                items(active + done, key = { it.id }) { job ->
                    JobRow(job, onCancel, onRetry)
                    HorizontalDivider()
                }
            }
            Spacer(Modifier.height(12.dp))
        }
    }
}

@Composable
private fun JobRow(job: ProcessingJob, onCancel: (Long) -> Unit, onRetry: (Long) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(vertical = 10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(job.name, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                val status = when (job.state) {
                    JobState.QUEUED -> "排队中 · ${job.label}"
                    JobState.RUNNING -> "${job.phase.ifEmpty { "处理中" }} · ${job.label} · 剩余约 " +
                        ProcessingQueue.formatEta(job.etaSeconds)
                    JobState.DONE -> "已保存 · ${job.label}"
                    JobState.FAILED -> "失败：${job.message}"
                    JobState.CANCELLED -> "已取消"
                }
                Text(
                    status,
                    style = MaterialTheme.typography.bodySmall.copy(fontFeatureSettings = "tnum"),
                    color = if (job.state == JobState.FAILED) MaterialTheme.colorScheme.error
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2, overflow = TextOverflow.Ellipsis,
                )
            }
            when {
                job.active -> TextButton(onClick = { onCancel(job.id) }) { Text("取消") }
                job.state == JobState.FAILED || job.state == JobState.CANCELLED ->
                    TextButton(onClick = { onRetry(job.id) }) { Text("重试") }
            }
        }
        if (job.state == JobState.RUNNING) {
            LinearProgressIndicator(progress = { job.progress }, modifier = Modifier.fillMaxWidth().padding(top = 6.dp))
        }
    }
}
