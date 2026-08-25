package com.ola.keyboard.ui

import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ola.keyboard.R
import com.ola.keyboard.ClipItem
import com.ola.keyboard.ClipboardData

/**
 * Full-screen clip manager, reachable from Settings > Clipboard > "Clips Manager".
 * Shows every saved clip grouped into Pinned / Others, same grouping as the
 * in-keyboard clipboard panel. Supports:
 *  - Multi-select + delete (with a confirmation dialog before anything is removed)
 *  - Pin / unpin
 *  - Manually adding a new clip that didn't come from a system copy
 *
 * Selection is entered either by long-pressing a clip or by tapping the trash
 * FAB; tapping a clip while selecting toggles it instead of pasting it (this
 * screen never pastes - that's only what the in-keyboard panel does).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ClipsManagerScreen(onBackClick: () -> Unit) {
    val context = LocalContext.current
    ClipboardData.load(context)

    // Bumped after every add/delete/pin so the list below re-reads from storage.
    var refreshToken by remember { mutableIntStateOf(0) }
    val clips = remember(refreshToken) { ClipboardData.all() }
    val pinned = remember(clips) { clips.filter { it.pinned } }
    val others = remember(clips) { clips.filter { !it.pinned } }

    var selectedIds by remember { mutableStateOf(setOf<Long>()) }
    val selectionMode = selectedIds.isNotEmpty()

    var showAddDialog by remember { mutableStateOf(false) }
    var showDeleteConfirm by remember { mutableStateOf(false) }

    fun toggleSelected(id: Long) {
        selectedIds = if (id in selectedIds) selectedIds - id else selectedIds + id
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.clips_manager_title)) },
                navigationIcon = {
                    IconButton(onClick = {
                        if (selectionMode) selectedIds = emptySet() else onBackClick()
                    }) {
                        Icon(
                            if (selectionMode) Icons.Filled.Close else Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back"
                        )
                    }
                }
            )
        },
        floatingActionButton = {
            Column(horizontalAlignment = Alignment.End) {
                FloatingActionButton(
                    onClick = {
                        if (selectionMode) showDeleteConfirm = true else selectedIds = emptySet()
                    },
                    modifier = Modifier.size(48.dp)
                ) {
                    Icon(Icons.Filled.Delete, contentDescription = stringResource(R.string.clip_delete))
                }
                Spacer(modifier = Modifier.height(16.dp))
                FloatingActionButton(onClick = { showAddDialog = true }) {
                    Icon(Icons.Filled.Add, contentDescription = stringResource(R.string.clips_manager_add_title))
                }
            }
        }
    ) { paddingValues ->
        if (clips.isEmpty()) {
            Box(
                modifier = Modifier.padding(paddingValues).fillMaxSize().padding(32.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = stringResource(R.string.clips_manager_empty),
                    fontSize = 14.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier.padding(paddingValues).fillMaxSize(),
                contentPadding = PaddingValues(bottom = 96.dp)
            ) {
                if (pinned.isNotEmpty()) {
                    item(key = "header_pinned") { SectionHeader(stringResource(R.string.clipboard_section_pinned)) }
                    items(pinned, key = { it.id }) { clip ->
                        ClipRow(
                            clip = clip,
                            selectionMode = selectionMode,
                            selected = clip.id in selectedIds,
                            onTap = {
                                if (selectionMode) toggleSelected(clip.id)
                            },
                            onLongPress = { toggleSelected(clip.id) },
                            onTogglePin = {
                                ClipboardData.setPinned(context, clip.id, !clip.pinned)
                                refreshToken++
                            }
                        )
                    }
                }
                if (others.isNotEmpty()) {
                    item(key = "header_others") { SectionHeader(stringResource(R.string.clipboard_section_others)) }
                    items(others, key = { it.id }) { clip ->
                        ClipRow(
                            clip = clip,
                            selectionMode = selectionMode,
                            selected = clip.id in selectedIds,
                            onTap = {
                                if (selectionMode) toggleSelected(clip.id)
                            },
                            onLongPress = { toggleSelected(clip.id) },
                            onTogglePin = {
                                ClipboardData.setPinned(context, clip.id, !clip.pinned)
                                refreshToken++
                            }
                        )
                    }
                }
            }
        }
    }

    if (showAddDialog) {
        AddClipDialog(
            onDismiss = { showAddDialog = false },
            onAdd = { text ->
                ClipboardData.add(context, text)
                refreshToken++
                showAddDialog = false
            }
        )
    }

    if (showDeleteConfirm) {
        val count = selectedIds.size
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = { Text(stringResource(R.string.clips_manager_delete_confirm_title)) },
            text = { Text(stringResource(R.string.clips_manager_delete_confirm_message, count)) },
            confirmButton = {
                TextButton(onClick = {
                    ClipboardData.deleteAll(context, selectedIds)
                    selectedIds = emptySet()
                    showDeleteConfirm = false
                    refreshToken++
                }) { Text(stringResource(R.string.clips_manager_delete_confirm_action)) }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirm = false }) {
                    Text(stringResource(R.string.clips_manager_cancel))
                }
            }
        )
    }
}

@Composable
private fun SectionHeader(title: String) {
    Text(
        text = title,
        color = MaterialTheme.colorScheme.primary,
        fontSize = 13.sp,
        fontWeight = FontWeight.Bold,
        letterSpacing = 0.5.sp,
        modifier = Modifier.padding(start = 16.dp, top = 20.dp, bottom = 8.dp)
    )
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun ClipRow(
    clip: ClipItem,
    selectionMode: Boolean,
    selected: Boolean,
    onTap: () -> Unit,
    onLongPress: () -> Unit,
    onTogglePin: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp)
            .combinedClickable(onClick = onTap, onLongClick = onLongPress),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (selected)
                MaterialTheme.colorScheme.primaryContainer
            else
                MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (selectionMode) {
                Icon(
                    imageVector = if (selected) Icons.Filled.CheckCircle else Icons.Filled.RadioButtonUnchecked,
                    contentDescription = stringResource(R.string.clip_selected),
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(end = 12.dp)
                )
            }
            Text(
                text = clip.text,
                fontSize = 15.sp,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            if (!selectionMode) {
                IconButton(onClick = onTogglePin) {
                    Icon(
                        imageVector = if (clip.pinned) Icons.Filled.PushPin else Icons.Outlined.PushPin,
                        contentDescription = stringResource(if (clip.pinned) R.string.clip_unpin else R.string.clip_pin),
                        tint = if (clip.pinned) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AddClipDialog(
    onDismiss: () -> Unit,
    onAdd: (String) -> Unit
) {
    var text by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.clips_manager_add_title)) },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                placeholder = { Text(stringResource(R.string.clips_manager_add_hint)) },
                modifier = Modifier.fillMaxWidth()
            )
        },
        confirmButton = {
            TextButton(onClick = {
                if (text.isNotBlank()) onAdd(text)
            }) { Text(stringResource(R.string.clips_manager_add_action)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.clips_manager_cancel)) }
        }
    )
}
