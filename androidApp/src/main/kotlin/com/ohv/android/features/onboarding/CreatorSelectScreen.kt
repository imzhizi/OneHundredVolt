package com.ohv.android.features.onboarding

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.outlined.Circle
import androidx.compose.material.icons.outlined.WifiOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import coil3.compose.AsyncImage
import com.ohv.android.theme.OhvColors
import com.ohv.shared.api.AfdianApiService
import com.ohv.shared.models.Creator
import com.ohv.shared.platform.SecureStorage
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

// ─── ViewModel ────────────────────────────────────────────────────────────────

data class CreatorSelectUiState(
    val creators: List<Creator> = emptyList(),
    val selectedIds: Set<String> = emptySet(),
    val isLoading: Boolean = false,
    val errorMessage: String? = null
)

class CreatorSelectViewModel : ViewModel() {

    private val secureStorage = SecureStorage()
    private val api = AfdianApiService(secureStorage)

    private val _uiState = MutableStateFlow(CreatorSelectUiState())
    val uiState: StateFlow<CreatorSelectUiState> = _uiState.asStateFlow()

    fun loadCreators() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true, errorMessage = null)
            try {
                val list = api.fetchSponsoringCreators()
                _uiState.value = _uiState.value.copy(
                    creators = list,
                    selectedIds = list.map { it.id }.toSet(), // 默认全选
                    isLoading = false
                )
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    errorMessage = e.message ?: "加载失败"
                )
            }
        }
    }

    fun toggleSelection(id: String) {
        val current = _uiState.value.selectedIds.toMutableSet()
        if (current.contains(id)) current.remove(id) else current.add(id)
        _uiState.value = _uiState.value.copy(selectedIds = current)
    }

    fun selectedIds(): List<String> = _uiState.value.selectedIds.toList()
}

// ─── Screen ───────────────────────────────────────────────────────────────────

/**
 * 创作者选择页（对应 iOS CreatorSelectView.swift）
 *
 * @param onConfirm 用户点击确认后，传入选中的 creatorId 列表，导航到 SyncProgressScreen
 * @param onBack    返回上一页
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CreatorSelectScreen(
    onConfirm: (List<String>) -> Unit,
    onBack: () -> Unit,
    vm: CreatorSelectViewModel = viewModel()
) {
    val uiState by vm.uiState.collectAsState()

    LaunchedEffect(Unit) { vm.loadCreators() }

    Scaffold(
        containerColor = OhvColors.Background,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        "选择要同步的项目",
                        color = OhvColors.White,
                        fontWeight = FontWeight.SemiBold
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "返回",
                            tint = OhvColors.Accent
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = OhvColors.Background)
            )
        }
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .background(OhvColors.Background)
        ) {
            when {
                uiState.isLoading -> {
                    // 加载中
                    Column(
                        modifier = Modifier.align(Alignment.Center),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        CircularProgressIndicator(color = OhvColors.Accent)
                        Text("正在获取项目列表...", color = OhvColors.SecondaryText)
                    }
                }

                uiState.errorMessage != null -> {
                    // 错误状态
                    Column(
                        modifier = Modifier
                            .align(Alignment.Center)
                            .padding(32.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        Icon(
                            Icons.Outlined.WifiOff,
                            contentDescription = null,
                            tint = OhvColors.SecondaryText,
                            modifier = Modifier.size(44.dp)
                        )
                        Text(
                            uiState.errorMessage!!,
                            color = OhvColors.SecondaryText,
                            fontSize = 14.sp
                        )
                        TextButton(onClick = { vm.loadCreators() }) {
                            Text("重试", color = OhvColors.Accent)
                        }
                    }
                }

                else -> {
                    // 创作者列表 + 底部确认按钮
                    Column(modifier = Modifier.fillMaxSize()) {
                        // 提示文字
                        Text(
                            "你正在支持的项目（共 ${uiState.creators.size} 个）",
                            color = OhvColors.SecondaryText,
                            fontSize = 12.sp,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 8.dp)
                        )

                        // 列表
                        LazyColumn(
                            modifier = Modifier.weight(1f),
                            contentPadding = PaddingValues(
                                start = 16.dp,
                                end = 16.dp,
                                bottom = 16.dp
                            ),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            items(uiState.creators, key = { it.id }) { creator ->
                                CreatorSelectRow(
                                    creator = creator,
                                    isSelected = uiState.selectedIds.contains(creator.id),
                                    onTap = { vm.toggleSelection(creator.id) }
                                )
                            }
                        }

                        // 底部确认按钮
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(OhvColors.Background)
                                .padding(horizontal = 16.dp, vertical = 16.dp)
                        ) {
                            val count = uiState.selectedIds.size
                            Button(
                                onClick = { onConfirm(vm.selectedIds()) },
                                enabled = count > 0,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(52.dp),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = OhvColors.Accent,
                                    contentColor = Color.Black,
                                    disabledContainerColor = OhvColors.Accent.copy(alpha = 0.4f),
                                    disabledContentColor = Color.Black.copy(alpha = 0.4f)
                                ),
                                shape = RoundedCornerShape(12.dp)
                            ) {
                                Text(
                                    if (count > 0) "确认同步（$count 个项目）" else "请至少选择一个项目",
                                    fontWeight = FontWeight.SemiBold,
                                    fontSize = 16.sp
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

// ─── 行视图 ───────────────────────────────────────────────────────────────────

@Composable
private fun CreatorSelectRow(
    creator: Creator,
    isSelected: Boolean,
    onTap: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(OhvColors.CardBackground)
            .clickable(onClick = onTap)
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // 头像
        AsyncImage(
            model = creator.avatarUrl,
            contentDescription = creator.name,
            modifier = Modifier
                .size(48.dp)
                .clip(CircleShape)
                .background(OhvColors.Separator)
        )

        // 名称 + 简介
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(creator.name, color = OhvColors.White, fontWeight = FontWeight.SemiBold)
            creator.doing?.let {
                Text(it, color = OhvColors.SecondaryText, fontSize = 12.sp)
            }
        }

        // 勾选状态
        Icon(
            imageVector = if (isSelected) Icons.Filled.CheckCircle else Icons.Outlined.Circle,
            contentDescription = if (isSelected) "已选" else "未选",
            tint = if (isSelected) OhvColors.Accent else OhvColors.SecondaryText,
            modifier = Modifier.size(24.dp)
        )
    }
}
