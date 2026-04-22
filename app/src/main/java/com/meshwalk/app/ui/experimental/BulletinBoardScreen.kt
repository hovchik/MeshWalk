package com.meshwalk.app.ui.experimental

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.meshwalk.app.experimental.BulletinBoardManager
import com.meshwalk.app.experimental.BulletinPost
import com.meshwalk.app.util.TimeUtils
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class BulletinBoardViewModel @Inject constructor(
    private val manager: BulletinBoardManager
) : ViewModel() {
    data class UiState(
        val posts: List<BulletinPost> = emptyList(),
        val mining: Boolean = false
    )
    private val _state = MutableStateFlow(UiState())
    val state = _state.asStateFlow()

    init {
        viewModelScope.launch {
            manager.observeActive().collect { posts ->
                _state.value = _state.value.copy(posts = posts)
            }
        }
    }

    fun post(body: String, category: String) {
        viewModelScope.launch {
            _state.value = _state.value.copy(mining = true)
            manager.createLocal(body, category)
            _state.value = _state.value.copy(mining = false)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BulletinBoardScreen(
    onBack: () -> Unit,
    viewModel: BulletinBoardViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsState()
    var showComposer by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Bulletin Board") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                    }
                }
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { showComposer = true }) {
                Icon(Icons.Filled.Add, "New post")
            }
        }
    ) { padding ->
        if (state.posts.isEmpty()) {
            Column(
                modifier = Modifier
                    .padding(padding)
                    .fillMaxSize(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Text("No posts yet", style = MaterialTheme.typography.titleMedium)
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    "Posts are anonymous and flood over the mesh.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier.padding(padding).fillMaxSize(),
                contentPadding = PaddingValues(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(state.posts, key = { it.postId }) { post ->
                    BulletinCard(post)
                }
            }
        }
    }

    if (showComposer) {
        BulletinComposer(
            mining = state.mining,
            onDismiss = { showComposer = false },
            onPost = { body, category ->
                viewModel.post(body, category)
                showComposer = false
            }
        )
    }
}

@Composable
private fun BulletinCard(post: BulletinPost) {
    Card(
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                AssistChip(
                    onClick = {},
                    label = { Text(post.category) }
                )
                Spacer(modifier = Modifier.weight(1f))
                Text(
                    TimeUtils.formatTimestamp(post.createdAt),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(modifier = Modifier.height(6.dp))
            Text(post.body, style = MaterialTheme.typography.bodyMedium)
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                "anonymous • PoW ${post.difficultyBits}b • ${if (post.isOwn) "you" else "relayed"}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun BulletinComposer(
    mining: Boolean,
    onDismiss: () -> Unit,
    onPost: (String, String) -> Unit
) {
    var body by remember { mutableStateOf("") }
    var category by remember { mutableStateOf("general") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("New Bulletin Post") },
        text = {
            Column {
                OutlinedTextField(
                    value = body,
                    onValueChange = { body = it },
                    label = { Text("Body") },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 3,
                    maxLines = 6
                )
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = category,
                    onValueChange = { category = it },
                    label = { Text("Category") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                if (mining) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Mining proof-of-work…")
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = body.isNotBlank() && !mining,
                onClick = { onPost(body, category.ifBlank { "general" }) }
            ) { Text("Post") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}
