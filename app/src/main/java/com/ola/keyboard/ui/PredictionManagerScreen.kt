package com.ola.keyboard.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ime.suggest.DefaultDictionary
import ime.suggest.SinhalaCollation
import ime.suggest.UserDictionary
import ime.suggest.UserWordFrequency

private const val TAB_MY_PREDICTION = 0
private const val TAB_ALL_USAGE = 1

/** One row in the "My Prediction" tab - a word from either source (or both) that
 *  actually feeds live suggestions (see SuggestionEngine.suggest(), which merges
 *  UserDictionary.getByPrefix() and UserWordFrequency.getByPrefix() together).
 *  [count] is null for a word that was only ever added manually and has never
 *  actually been typed/learned yet. */
private data class PredictionEntry(
    val word: String,
    val count: Int?,
    val isCustom: Boolean
)

/** One row in the "All Usage" tab - either a word the user has actually typed
 *  ([count] set) or one from the keyboard's own bundled dictionary that the
 *  user has never typed yet ([count] null, [isDefault] true). A word that's
 *  in both just shows as the learned entry, since its count is the more
 *  useful of the two. */
private data class AllUsageEntry(
    val word: String,
    val count: Int?,
    val isDefault: Boolean
)

/**
 * Lets the user browse and manage the two word lists that feed keyboard
 * suggestions: the words they've manually added ("My Prediction", via
 * [UserDictionary]) and every word the keyboard has learned from their typing
 * ("All Usage", via [UserWordFrequency]). Both are on-device only.
 *
 * "My Prediction" shows the union of both sources - not just the manually-added
 * ones - since a learned word is just as much a part of "what predicts for me"
 * as a hand-added one; "All Usage" stays a pure, unfiltered view of everything
 * the keyboard has picked up from typing, with its raw usage count.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PredictionManagerScreen(
    onBackClick: () -> Unit
) {
    val context = LocalContext.current
    var selectedTab by remember { mutableIntStateOf(TAB_MY_PREDICTION) }

    // Bumped after every add/delete so the lists below re-read from storage.
    var refreshToken by remember { mutableIntStateOf(0) }

    val myPredictionWords = remember(refreshToken) {
        val customWords = UserDictionary.getAll(context)
        val learnedCounts = UserWordFrequency.getAllWithCount(context).toMap()
        val allWords = LinkedHashSet<String>().apply {
            addAll(customWords)
            addAll(learnedCounts.keys)
        }
        allWords
            .map { word ->
                PredictionEntry(
                    word = word,
                    count = learnedCounts[word],
                    isCustom = word in customWords
                )
            }
            .sortedWith(compareBy(SinhalaCollation.comparator) { it.word })
    }
    // The bundled dictionary (~25k words) is read from assets once and cached in
    // DefaultDictionary itself, but the read is still async so it doesn't jank
    // the screen the first time this tab is opened.
    var defaultWords by remember { mutableStateOf<List<String>>(emptyList()) }
    LaunchedEffect(Unit) {
        defaultWords = DefaultDictionary.getAll(context)
    }

    val allUsageWords = remember(refreshToken, defaultWords) {
        val learnedCounts = UserWordFrequency.getAllWithCount(context).toMap()
        val allWords = LinkedHashSet<String>().apply {
            addAll(learnedCounts.keys)
            addAll(defaultWords)
        }
        allWords
            .map { word ->
                AllUsageEntry(
                    word = word,
                    count = learnedCounts[word],
                    isDefault = word !in learnedCounts
                )
            }
            .sortedWith(compareBy(SinhalaCollation.comparator) { it.word })
    }

    var showAddDialog by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Prediction Manager") },
                navigationIcon = {
                    IconButton(onClick = onBackClick) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = { showAddDialog = true }) {
                        Icon(Icons.Filled.Add, contentDescription = "Add word")
                    }
                }
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .padding(paddingValues)
                .fillMaxSize()
        ) {
            TabRow(selectedTabIndex = selectedTab) {
                Tab(
                    selected = selectedTab == TAB_MY_PREDICTION,
                    onClick = { selectedTab = TAB_MY_PREDICTION },
                    text = { Text("My Prediction") }
                )
                Tab(
                    selected = selectedTab == TAB_ALL_USAGE,
                    onClick = { selectedTab = TAB_ALL_USAGE },
                    text = { Text("All Usage") }
                )
            }

            when (selectedTab) {
                TAB_MY_PREDICTION -> MyPredictionList(
                    entries = myPredictionWords,
                    onDelete = { entry ->
                        // A word can live in either store, or both (manually added
                        // AND since learned from typing) - clear it out of whichever
                        // actually has it so it doesn't silently reappear from the
                        // other one on the next refresh.
                        if (entry.isCustom) UserDictionary.remove(context, entry.word)
                        if (entry.count != null) UserWordFrequency.remove(context, entry.word)
                        refreshToken++
                    }
                )
                else -> AllUsageList(
                    entries = allUsageWords,
                    onDelete = { entry ->
                        // Only learned words can be removed - the bundled
                        // dictionary is a shipped asset, not user data.
                        if (!entry.isDefault) {
                            UserWordFrequency.remove(context, entry.word)
                            refreshToken++
                        }
                    }
                )
            }
        }
    }

    if (showAddDialog) {
        AddWordDialog(
            onDismiss = { showAddDialog = false },
            onAdd = { word ->
                val added = UserDictionary.add(context, word)
                if (added) {
                    refreshToken++
                    selectedTab = TAB_MY_PREDICTION
                }
                added
            }
        )
    }
}

@Composable
private fun MyPredictionList(
    entries: List<PredictionEntry>,
    onDelete: (PredictionEntry) -> Unit
) {
    if (entries.isEmpty()) {
        EmptyState(
            message = "තවම ඔබ වචන එකතු කර නැත. එකතු කිරීමට ඉහත + බොත්තම ඔබන්න."
        )
        return
    }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(vertical = 4.dp)
    ) {
        // Just the word itself now - no "Added" / "Used N times" usage line.
        items(entries, key = { it.word }) { entry ->
            WordRow(
                primaryText = entry.word,
                onDelete = { onDelete(entry) }
            )
        }
    }
}

@Composable
private fun AllUsageList(
    entries: List<AllUsageEntry>,
    onDelete: (AllUsageEntry) -> Unit
) {
    if (entries.isEmpty()) {
        EmptyState(
            message = "ඔබ තවම කිසිදු වචනයක් ටයිප් කර නැත."
        )
        return
    }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(vertical = 4.dp)
    ) {
        items(entries, key = { it.word }) { entry ->
            WordRow(
                primaryText = entry.word,
                // Bundled-dictionary words have no usage count and can't be
                // removed, so they get no secondary line and no delete icon.
                secondaryText = entry.count?.let { "Used $it times" },
                onDelete = if (entry.isDefault) null else { { onDelete(entry) } }
            )
        }
    }
}

@Composable
private fun WordRow(
    primaryText: String,
    secondaryText: String? = null,
    onDelete: (() -> Unit)?
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 14.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = primaryText, fontSize = 17.sp, fontWeight = FontWeight.Bold)
            if (secondaryText != null) {
                Text(
                    text = secondaryText,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        if (onDelete != null) {
            IconButton(onClick = onDelete) {
                Icon(
                    Icons.Filled.Delete,
                    contentDescription = "Delete",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun EmptyState(message: String) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = message,
            fontSize = 14.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AddWordDialog(
    onDismiss: () -> Unit,
    onAdd: (String) -> Boolean
) {
    var text by remember { mutableStateOf("") }
    var alreadyExists by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("වචනයක් එකතු කරන්න") },
        text = {
            Column {
                OutlinedTextField(
                    value = text,
                    onValueChange = {
                        text = it
                        alreadyExists = false
                    },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                if (alreadyExists) {
                    Text(
                        text = "මෙම වචනය දැනටමත් එකතු කර ඇත",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                if (text.isBlank()) return@TextButton
                val added = onAdd(text)
                if (added) {
                    onDismiss()
                } else {
                    alreadyExists = true
                }
            }) { Text("එකතු කරන්න") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("අවලංගු කරන්න") }
        }
    )
}
