package com.ohv.android.features.creator

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import coil3.compose.AsyncImage
import com.ohv.android.components.InAppBrowserSheet
import com.ohv.android.theme.OhvColors
import com.ohv.shared.db.DatabaseService
import com.ohv.shared.models.Album
import com.ohv.shared.models.Creator
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

// ─── Creator Detail ──────────────────────────────────────────────────────────

data class CreatorUiState(
    val creator: Creator? = null,
    val albums: List<Album> = emptyList(),
    val totalAudioCount: Int = 0
)

class CreatorViewModel(private val creatorId: String) : ViewModel() {

    private val db = DatabaseService.shared

    private val _uiState = MutableStateFlow(CreatorUiState())
    val uiState: StateFlow<CreatorUiState> = _uiState.asStateFlow()

    init { load() }

    private fun load() {
        viewModelScope.launch {
            val creator = db.creators.value.firstOrNull { it.id == creatorId }
            val albums = db.albumsForCreator(creatorId)
            _uiState.value = CreatorUiState(
                creator = creator,
                albums = albums,
                totalAudioCount = albums.sumOf { it.audioCount }
            )
        }
    }

    class Factory(private val creatorId: String) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            CreatorViewModel(creatorId) as T
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CreatorScreen(
    creatorId: String,
    onBack: () -> Unit,
    onAlbumClick: (String) -> Unit,
    vm: CreatorViewModel = viewModel(factory = CreatorViewModel.Factory(creatorId))
) {
    val uiState by vm.uiState.collectAsState()
    val creator = uiState.creator
    var browserUrl by remember { mutableStateOf<String?>(null) }

    Scaffold(
        containerColor = OhvColors.Background,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        creator?.name ?: "",
                        color = OhvColors.White,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth()
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回", tint = OhvColors.Accent)
                    }
                },
                actions = {
                    if (creator?.urlSlug != null) {
                        TextButton(onClick = { browserUrl = creator.afdianPageUrl }) {
                            Text("爱发电", color = OhvColors.Accent, fontSize = 14.sp)
                            Icon(
                                Icons.AutoMirrored.Filled.OpenInNew,
                                contentDescription = null,
                                tint = OhvColors.Accent,
                                modifier = Modifier.size(14.dp).padding(start = 2.dp)
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = OhvColors.Background)
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(bottom = 24.dp)
        ) {
            // Creator header
            item {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    AsyncImage(
                        model = creator?.avatarUrl,
                        contentDescription = creator?.name,
                        modifier = Modifier.size(80.dp).clip(CircleShape).background(OhvColors.CardBackground),
                        contentScale = ContentScale.Crop
                    )
                    Text(
                        creator?.name ?: "",
                        color = OhvColors.White,
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold
                    )
                    creator?.doing?.let {
                        Text(it, color = OhvColors.SecondaryText, fontSize = 14.sp)
                    }
                    Text(
                        "${uiState.albums.size} 个专辑 · ${uiState.totalAudioCount} 期音频",
                        color = OhvColors.SecondaryText,
                        fontSize = 13.sp
                    )
                }
                HorizontalDivider(color = OhvColors.Separator, modifier = Modifier.padding(horizontal = 16.dp))
            }

            // Album list
            items(uiState.albums, key = { it.id }) { album ->
                AlbumRow(
                    album = album,
                    onClick = { onAlbumClick(album.id) }
                )
                HorizontalDivider(color = OhvColors.Separator, modifier = Modifier.padding(start = 88.dp))
            }
        }
    }

    browserUrl?.let { url ->
        InAppBrowserSheet(url = url, onDismiss = { browserUrl = null })
    }
}

@Composable
private fun AlbumRow(album: Album, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Box(
            modifier = Modifier.size(60.dp).clip(RoundedCornerShape(8.dp)).background(OhvColors.CardBackground),
            contentAlignment = Alignment.Center
        ) {
            if (album.coverUrl != null) {
                AsyncImage(model = album.coverUrl, contentDescription = album.title, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            } else {
                Icon(Icons.Default.MusicNote, contentDescription = null, tint = OhvColors.SecondaryText, modifier = Modifier.size(24.dp))
            }
            if (!album.isAccessible) {
                Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.5f)), contentAlignment = Alignment.Center) {
                    Icon(Icons.Default.Lock, contentDescription = "未购买", tint = Color.White, modifier = Modifier.size(20.dp))
                }
            }
        }

        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(
                album.title,
                color = OhvColors.White,
                fontSize = 15.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            if (album.isAccessible) {
                Text(
                    "${album.audioCount} 期 · ${album.totalDuration.toHumanReadable()}",
                    color = OhvColors.SecondaryText,
                    fontSize = 12.sp
                )
            } else {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    Icon(Icons.Default.Lock, contentDescription = null, tint = OhvColors.SecondaryText, modifier = Modifier.size(12.dp))
                    Text("未购买", color = OhvColors.SecondaryText, fontSize = 12.sp)
                }
            }
        }

        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, tint = OhvColors.SecondaryText)
    }
}

// ─── All Creators ────────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AllCreatorsScreen(
    onBack: () -> Unit,
    onCreatorClick: (String) -> Unit
) {
    val db = DatabaseService.shared
    val creators by db.creators.collectAsState()
    val albums by db.albums.collectAsState()

    Scaffold(
        containerColor = OhvColors.Background,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        "全部创作者",
                        color = OhvColors.White,
                        fontWeight = FontWeight.SemiBold,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth()
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回", tint = OhvColors.Accent)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = OhvColors.Background)
            )
        }
    ) { padding ->
        LazyColumn(modifier = Modifier.fillMaxSize().padding(padding)) {
            items(creators.filter { it.isSelected }, key = { it.id }) { creator ->
                val albumCount = albums.count { it.creatorId == creator.id }
                CreatorListRow(
                    creator = creator,
                    albumCount = albumCount,
                    onClick = { onCreatorClick(creator.id) }
                )
                HorizontalDivider(color = OhvColors.Separator, modifier = Modifier.padding(start = 76.dp))
            }
        }
    }
}

@Composable
private fun CreatorListRow(creator: Creator, albumCount: Int, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        AsyncImage(
            model = creator.avatarUrl,
            contentDescription = creator.name,
            modifier = Modifier.size(52.dp).clip(CircleShape).background(OhvColors.CardBackground),
            contentScale = ContentScale.Crop
        )
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(creator.name, color = OhvColors.White, fontSize = 16.sp, fontWeight = FontWeight.Medium)
            Text(
                creator.doing ?: "$albumCount 个专辑",
                color = OhvColors.SecondaryText,
                fontSize = 13.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, tint = OhvColors.SecondaryText)
    }
}

private fun Double.toHumanReadable(): String {
    val total = roundToInt()
    val h = total / 3600
    val m = (total % 3600) / 60
    return when {
        h > 0 && m > 0 -> "${h}小时${m}分"
        h > 0 -> "${h}小时"
        else -> "${m}分钟"
    }
}
