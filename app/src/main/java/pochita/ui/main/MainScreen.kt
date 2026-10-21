package pochita.ui.main

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation3.runtime.NavKey
import pochita.data.LsposedStatus
import pochita.data.ModuleConfig
import pochita.data.PreferenceKeys

@Composable
fun MainScreen(
    modifier: Modifier = Modifier,
    onItemClick: (NavKey) -> Unit = {},
    viewModel: MainScreenViewModel = viewModel(),
) {
    val status by viewModel.status.collectAsStateWithLifecycle()
    val config by viewModel.config.collectAsStateWithLifecycle()

    MainScreenContent(
        status = status,
        config = config,
        onRuleUrlChanged = { viewModel.setRuleUrl(it) },
        onUpdateRuleClicked = { viewModel.updateRule() },
        onResetRuleClicked = { viewModel.resetRule() },
        onToggleSource = { id, enabled -> viewModel.toggleSource(id, enabled) },
        onMoveSourceUp = { viewModel.moveSourceUp(it) },
        onMoveSourceDown = { viewModel.moveSourceDown(it) },
        onResetSources = { viewModel.resetSourceOrder() },
        onQualitySelected = { viewModel.setPreferredQuality(it) },
        onToastToggled = { viewModel.setEnableToast(it) },
        onBadgeToggled = { viewModel.setEnablePlayerBadge(it) },
        onAntiRollToggled = { viewModel.setEnableAntiRoll(it) },
        modifier = modifier,
    )
}

@Composable
internal fun MainScreenContent(
    status: LsposedStatus,
    config: ModuleConfig,
    onRuleUrlChanged: (String) -> Unit,
    onUpdateRuleClicked: () -> Unit,
    onResetRuleClicked: () -> Unit = {},
    onToggleSource: (String, Boolean) -> Unit = { _, _ -> },
    onMoveSourceUp: (String) -> Unit = {},
    onMoveSourceDown: (String) -> Unit = {},
    onResetSources: () -> Unit = {},
    onQualitySelected: (String) -> Unit = {},
    onToastToggled: (Boolean) -> Unit = {},
    onBadgeToggled: (Boolean) -> Unit = {},
    onAntiRollToggled: (Boolean) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val scrollState = rememberScrollState()

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(scrollState)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        LsposedStatusCard(status = status)

        SoundSourcesCard(
            enabledSources = config.enabledSources,
            onToggleSource = onToggleSource,
            onMoveSourceUp = onMoveSourceUp,
            onMoveSourceDown = onMoveSourceDown,
            onResetSources = onResetSources,
        )

        AudioQualityCard(
            selectedQuality = config.preferredQuality,
            onQualitySelected = onQualitySelected,
        )

        PlaybackFeedbackCard(
            enableToast = config.enableToast,
            enablePlayerBadge = config.enablePlayerBadge,
            onToastToggled = onToastToggled,
            onBadgeToggled = onBadgeToggled,
        )

        AntiRollFilterCard(
            enableAntiRoll = config.enableAntiRoll,
            onAntiRollToggled = onAntiRollToggled,
        )

        DynamicRuleCard(
            ruleUrl = config.ruleUrl,
            lastUpdateStatus = config.lastUpdateStatus,
            onRuleUrlChanged = onRuleUrlChanged,
            onUpdateRuleClicked = onUpdateRuleClicked,
            onResetRuleClicked = onResetRuleClicked,
        )

        Spacer(modifier = Modifier.height(24.dp))
    }
}

// ----------------------------------------------------------------------------
// Component Cards
// ----------------------------------------------------------------------------

data class SourceItemMeta(
    val id: String,
    val name: String,
    val description: String,
)

val KNOWN_SOURCES_META = listOf(
    SourceItemMeta(PreferenceKeys.SOURCE_BODIAN, "波点音乐", "推荐首选·稳定高质量音源直链"),
    SourceItemMeta(PreferenceKeys.SOURCE_KUWO, "酷我音乐", "曲库庞大·支持 320k / 无损 FLAC"),
    SourceItemMeta(PreferenceKeys.SOURCE_MIGU, "咪咕音乐", "原版无损·高拟合率正版音轨"),
    SourceItemMeta(PreferenceKeys.SOURCE_KUGOU, "酷狗音乐", "备用音源·伴奏与冷门曲目补充"),
    SourceItemMeta(PreferenceKeys.SOURCE_CUSTOMJS, "自定义 JS", "嵌入式 QuickJS 动态远程规则扩展")
)

@Composable
fun SoundSourcesCard(
    enabledSources: List<String>,
    onToggleSource: (String, Boolean) -> Unit,
    onMoveSourceUp: (String) -> Unit,
    onMoveSourceDown: (String) -> Unit,
    onResetSources: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // Maintain display order: enabled sources in priority order, followed by disabled ones
    val displayList = enabledSources + (KNOWN_SOURCES_META.map { it.id } - enabledSources.toSet())

    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "音源调度与优先级",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )
                OutlinedButton(onClick = onResetSources) {
                    Text("恢复默认")
                }
            }
            Text(
                text = "按列表自上而下的顺序尝试换源。点击箭头可调整优先级，关闭开关可临时停用对应音源。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            displayList.forEachIndexed { index, sourceId ->
                val meta = KNOWN_SOURCES_META.find { it.id == sourceId } ?: SourceItemMeta(sourceId, sourceId, "")
                val isEnabled = enabledSources.contains(sourceId)
                val priorityIndex = enabledSources.indexOf(sourceId)

                if (index > 0) {
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    // Priority Badge
                    Surface(
                        shape = CircleShape,
                        color = if (isEnabled) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface,
                        modifier = Modifier.padding(end = 12.dp)
                    ) {
                        Text(
                            text = if (isEnabled) "#${priorityIndex + 1}" else "--",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold,
                            color = if (isEnabled) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                        )
                    }

                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = meta.name,
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(
                            text = meta.description,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }

                    // Move Up / Move Down buttons
                    IconButton(
                        onClick = { onMoveSourceUp(sourceId) },
                        enabled = isEnabled && priorityIndex > 0
                    ) {
                        Text("▲", style = MaterialTheme.typography.bodyMedium)
                    }
                    IconButton(
                        onClick = { onMoveSourceDown(sourceId) },
                        enabled = isEnabled && priorityIndex in 0 until enabledSources.size - 1
                    ) {
                        Text("▼", style = MaterialTheme.typography.bodyMedium)
                    }

                    Spacer(modifier = Modifier.width(4.dp))

                    Switch(
                        checked = isEnabled,
                        onCheckedChange = { onToggleSource(sourceId, it) },
                    )
                }
            }
        }
    }
}

data class QualityItem(
    val key: String,
    val title: String,
    val badge: String,
    val description: String,
)

val QUALITY_LIST = listOf(
    QualityItem(PreferenceKeys.QUALITY_320K, "极高品质", "320 kbps", "推荐·高清晰度且缓冲极快"),
    QualityItem(PreferenceKeys.QUALITY_FLAC, "无损母带", "FLAC / 999k", "最高保真度·消耗较多流量"),
    QualityItem(PreferenceKeys.QUALITY_192K, "较高品质", "192 kbps", "适中码率·兼顾品质与流量"),
    QualityItem(PreferenceKeys.QUALITY_128K, "标准品质", "128 kbps", "标准清晰度·弱网环境适用"),
)

@Composable
fun AudioQualityCard(
    selectedQuality: String,
    onQualitySelected: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                text = "目标音质偏好",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
            )
            Text(
                text = "换源时优先请求的目标比特率。若所选音质在目标音源暂不可用，将自动平滑向下回退。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            QUALITY_LIST.forEachIndexed { index, item ->
                if (index > 0) {
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onQualitySelected(item.key) }
                        .padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(
                        selected = selectedQuality == item.key,
                        onClick = { onQualitySelected(item.key) },
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = item.title,
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.SemiBold,
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Surface(
                                shape = RoundedCornerShape(6.dp),
                                color = MaterialTheme.colorScheme.secondaryContainer,
                            ) {
                                Text(
                                    text = item.badge,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                )
                            }
                        }
                        Text(
                            text = item.description,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun PlaybackFeedbackCard(
    enableToast: Boolean,
    enablePlayerBadge: Boolean,
    onToastToggled: (Boolean) -> Unit,
    onBadgeToggled: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = "播放界面感知与反馈",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
            )
            Text(
                text = "控制网易云音乐播放界面中的换源提示与标识呈现。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            // Switch 1: Toast
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "切歌即时轻提示 (Toast)",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        text = "换源成功时弹出轻量 Toast：🎵 云村换源: 已替换为 [酷我]《具体曲名》· 320k（带 3 秒防刷抖动保护）",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(modifier = Modifier.width(12.dp))
                Switch(
                    checked = enableToast,
                    onCheckedChange = onToastToggled,
                )
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

            // Switch 2: Player Badge
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "播放界面动态徽章 (UI Badge)",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        text = "在播放页歌手栏旁注入换源徽章（如 [酷我·320K]），点击可弹窗查看原曲 vs 匹配曲目透视信息",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(modifier = Modifier.width(12.dp))
                Switch(
                    checked = enablePlayerBadge,
                    onCheckedChange = onBadgeToggled,
                )
            }
        }
    }
}

@Composable
fun AntiRollFilterCard(
    enableAntiRoll: Boolean,
    onAntiRollToggled: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = "防翻车智能版本过滤",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "过滤非原版曲目 (Live / 现场 / 电台 / 翻唱)",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        text = "当原歌曲名不含 Live/现场/电台/翻唱/DJ 时，自动惩罚此类关键词候选并拟合歌曲时长，大幅降低误换为演唱会版或翻唱版的概率",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(modifier = Modifier.width(12.dp))
                Switch(
                    checked = enableAntiRoll,
                    onCheckedChange = onAntiRollToggled,
                )
            }
        }
    }
}

@Composable
fun DynamicRuleCard(
    ruleUrl: String,
    lastUpdateStatus: String,
    onRuleUrlChanged: (String) -> Unit,
    onUpdateRuleClicked: () -> Unit,
    onResetRuleClicked: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = "动态 QuickJS 规则脚本",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
            )
            Text(
                text = "基于嵌入式 QuickJS 引擎执行音源解析规则，支持通过远程脚本进行热更新。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            OutlinedTextField(
                value = ruleUrl,
                onValueChange = onRuleUrlChanged,
                label = { Text("规则订阅 URL") },
                supportingText = {
                    Text(
                        text = "默认源：https://raw.githubusercontent.com/jnshuiji/unm/refs/heads/main/rules/unm_rule.js",
                        style = MaterialTheme.typography.labelSmall,
                    )
                },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "状态: $lastUpdateStatus",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.weight(1f),
                )
                OutlinedButton(onClick = onResetRuleClicked) {
                    Text("恢复内置")
                }
                Button(onClick = onUpdateRuleClicked) {
                    Text("立即同步")
                }
            }
        }
    }
}

@Composable
fun LsposedStatusCard(status: LsposedStatus, modifier: Modifier = Modifier) {
    val containerColor = if (status.isActivated) {
        MaterialTheme.colorScheme.primaryContainer
    } else {
        MaterialTheme.colorScheme.errorContainer
    }
    val contentColor = if (status.isActivated) {
        MaterialTheme.colorScheme.onPrimaryContainer
    } else {
        MaterialTheme.colorScheme.onErrorContainer
    }

    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = containerColor,
            contentColor = contentColor,
        ),
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = if (status.isActivated) "● 已激活 (API ${status.apiVersion})" else "○ 未激活",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )
            }
            Text(
                text = if (status.isActivated) {
                    "${status.frameworkName} ${status.frameworkVersion} 正在生效于目标应用 com.netease.cloudmusic\n(配置由 RemotePreferences 驱动，实时热同步无需重启)"
                } else {
                    "请在 LSPosed 管理器中启用模块并勾选目标应用"
                },
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}
