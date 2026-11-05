package com.dearded.spektralab

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 正在处理: every export, running first, with progress and the time left.
 * A sheet rather than a screen, so the photo being edited stays in view.
 * (ui-screens_research.md 3) One summary card on top says when it will all
 * be done, the way Google Photos' backup banner gives one clear ETA; the jobs
 * follow in two groups, each row marked by shape as well as words: a ring
 * waiting, an arc filling while it runs, a tick done, a bang failed.
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
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = Palette.Panel) {
        val active = jobs.filter { it.active }.sortedBy { it.id }
        val done = jobs.filter { !it.active }.sortedByDescending { it.finishedAt }
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp).navigationBarsPadding()) {
            PanelHeader(
                title = "正在处理",
                hint = "可以切到后台，进度会显示在通知和灵动岛里。若被系统中断，下次打开应用会自动继续。",
            )
            Spacer(Modifier.height(RowGap))
            Summary(active, saved = done.count { it.state == JobState.DONE },
                failed = done.count { it.state == JobState.FAILED })
            QueueNotices()
            // Height follows the list, up to about half the screen; the sheet scrolls no further.
            LazyColumn(Modifier.fillMaxWidth().heightIn(max = 420.dp)) {
                if (active.isNotEmpty()) {
                    item(key = "h-active") { GroupHead("进行中", active.size) }
                    items(active, key = { it.id }) { job -> JobRow(job, onCancel, onRetry) }
                }
                if (done.isNotEmpty()) {
                    item(key = "h-done") {
                        GroupHead("已完成", done.size) { QuietButton("清除", onClick = onClear) }
                    }
                    items(done, key = { it.id }) { job -> JobRow(job, onCancel, onRetry) }
                }
            }
            Spacer(Modifier.height(12.dp))
        }
    }
}

/** The time left in large tabular figures, what is running under it, one bar for the whole queue. */
@Composable
private fun Summary(active: List<ProcessingJob>, saved: Int, failed: Int) {
    val finished = saved + failed
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Palette.PanelHigh),
    ) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 14.dp)) {
            if (active.isEmpty()) {
                // Only what was saved counts as done; a failure is named, never folded in.
                Text(if (failed > 0) "有 $failed 张没有完成" else if (saved > 0) "全部完成" else "没有进行中的任务",
                    style = MaterialTheme.typography.titleLarge, color = Palette.OnSurface)
                Hint(when {
                    failed > 0 -> "在下面点“重试”；" + if (saved > 0) "另外 $saved 张已保存到相册" else "原因写在每一行里"
                    saved > 0 -> "$saved 张已保存到相册"
                    else -> "导出的照片会在这里排队"
                })
            } else {
                val left = ProcessingQueue.remainingSeconds(active)
                Text("还需约", style = MaterialTheme.typography.bodySmall, color = Palette.OnSurfaceSoft)
                Text(
                    ProcessingQueue.formatEta(left),
                    style = Readout.copy(fontSize = 34.sp, lineHeight = 40.sp, fontWeight = FontWeight.Medium),
                    color = Palette.OnSurface,
                )
                val running = active.firstOrNull { it.state == JobState.RUNNING }
                Hint(
                    "${active.size} 张" + (running?.let { " · 正在处理「${it.name}」" } ?: " · 排队中"),
                )
                Spacer(Modifier.height(10.dp))
                // The whole queue, counting a running job by its own progress.
                val total = active.size + finished
                val doneShare = if (total == 0) 0f else (finished + active.sumOf { it.progress.toDouble() }.toFloat()) / total
                LinearProgressIndicator(
                    progress = { doneShare.coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth().height(4.dp).clip(CircleShape),
                    color = Palette.Accent, trackColor = Palette.Panel, drawStopIndicator = {},
                )
            }
        }
        // Working: the Aura's sliding glow along the card's foot (UI2.md 1.1, the processing bar).
        if (active.isNotEmpty()) AuraBar()
    }
}

@Composable
private fun GroupHead(title: String, count: Int, trailing: @Composable () -> Unit = {}) {
    Row(
        Modifier.fillMaxWidth().padding(top = GroupGap - RowGap).heightIn(min = 40.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, style = MaterialTheme.typography.labelLarge, color = Palette.OnSurfaceSoft)
        Spacer(Modifier.width(6.dp))
        Text("$count", style = Readout, color = Palette.OnSurfaceSoft)
        Spacer(Modifier.weight(1f))
        trailing()
    }
}

@Composable
private fun JobRow(job: ProcessingJob, onCancel: (Long) -> Unit, onRetry: (Long) -> Unit) {
    val status = when (job.state) {
        JobState.QUEUED -> "排队中 · ${job.label}"
        JobState.RUNNING -> "${job.phase.ifEmpty { "处理中" }} · ${job.label} · 剩余约 " +
            ProcessingQueue.formatEta(job.etaSeconds)
        JobState.DONE -> "已保存 · ${job.label}"
        JobState.FAILED -> "没有完成：${job.message}"
        JobState.CANCELLED -> "已取消"
    }
    Row(
        Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(vertical = 6.dp)
            .semantics(mergeDescendants = true) { contentDescription = "${job.name}，$status" },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        StateMark(job)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(job.name, style = MaterialTheme.typography.bodyMedium, color = Palette.OnSurface,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                status,
                style = MaterialTheme.typography.bodySmall.copy(fontFeatureSettings = "tnum"),
                color = if (job.state == JobState.FAILED) Palette.Warning else Palette.OnSurfaceSoft,
                maxLines = 2, overflow = TextOverflow.Ellipsis,
            )
        }
        when {
            job.active -> QuietButton("取消") { onCancel(job.id) }
            job.state == JobState.FAILED || job.state == JobState.CANCELLED ->
                TonalButton("重试") { onRetry(job.id) }
        }
    }
}

/** 28 dp: ○ waiting, an arc that fills while running, ✓ done, ! failed, – cancelled. Shape first, never colour alone. */
@Composable
private fun StateMark(job: ProcessingJob) {
    val ink = Palette.OnSurface
    val soft = Palette.OnSurfaceSoft
    when (job.state) {
        JobState.DONE -> Box(Modifier.size(28.dp).background(Palette.Accent, CircleShape), contentAlignment = Alignment.Center) {
            Text("✓", color = Palette.OnAccent, fontSize = 14.sp)
        }
        JobState.FAILED -> Box(Modifier.size(28.dp).background(Palette.PanelHighest, CircleShape), contentAlignment = Alignment.Center) {
            Text("!", color = Palette.Warning, fontSize = 15.sp, fontWeight = FontWeight.Bold)
        }
        JobState.CANCELLED -> Box(Modifier.size(28.dp).background(Palette.PanelHigh, CircleShape), contentAlignment = Alignment.Center) {
            Text("–", color = soft, fontSize = 15.sp)
        }
        JobState.QUEUED, JobState.RUNNING -> Canvas(Modifier.size(28.dp)) {
            val w = 2.5.dp.toPx()
            val inset = w / 2
            val arcSize = androidx.compose.ui.geometry.Size(size.width - w, size.height - w)
            drawArc(soft.copy(alpha = 0.35f), 0f, 360f, false, Offset(inset, inset), arcSize, style = Stroke(w))
            if (job.state == JobState.RUNNING) {
                drawArc(ink, -90f, 360f * job.progress.coerceIn(0.02f, 1f), false, Offset(inset, inset), arcSize,
                    style = Stroke(w, cap = StrokeCap.Round))
            }
        }
    }
}
