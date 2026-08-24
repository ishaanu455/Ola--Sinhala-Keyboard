package unicode.sinhala.keyboard.ui

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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ime.suggest.UserDictionary
import ime.suggest.UserWordFrequency

private const val TAB_MY_PREDICTION = 0
private const val TAB_ALL_USAGE = 1

/**
 * Lets the user browse and manage the two word lists that feed keyboard
 * suggestions: the words they've manually added ("My Prediction", via
 * [UserDictionary]) and every word the keyboard has learned from their typing
 * ("All Usage", via [UserWordFrequency]). Both are on-device only.
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

    val myPredictionWords = remember(refreshToken) { UserDictionary.getAll(context) }
    val allUsageWords = remember(refreshToken) { UserWordFrequency.getAllWithCount(context) }

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
                    words = myPredictionWords,
                    onDelete = { word ->
                        UserDictionary.remove(context, word)
                        refreshToken++
                    }
                )
                else -> AllUsageList(
                    words = allUsageWords,
                    onDelete = { word ->
                        UserWordFrequency.remove(context, word)
                        refreshToken++
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
    words: List<String>,
    onDelete: (String) -> Unit
) {
    if (words.isEmpty()) {
        EmptyState(
            message = "තවම ඔබ වචන එකතු කර නැත. එකතු කිරීමට ඉහත + බොත්තම ඔබන්න."
        )
        return
    }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(vertical = 4.dp)
    ) {
        items(words, key = { it }) { word ->
            WordRow(
                primaryText = word,
                secondaryText = null,
                onDelete = { onDelete(word) }
            )
        }
    }
}

@Composable
private fun AllUsageList(
    words: List<Pair<String, Int>>,
    onDelete: (String) -> Unit
) {
    if (words.isEmpty()) {
        EmptyState(
            message = "ඔබ තවම කිසිදු වචනයක් ටයිප් කර නැත."
        )
        return
    }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(vertical = 4.dp)
    ) {
        items(words, key = { it.first }) { (word, count) ->
            WordRow(
                primaryText = word,
                secondaryText = "Used $count times",
                onDelete = { onDelete(word) }
            )
        }
    }
}

@Composable
private fun WordRow(
    primaryText: String,
    secondaryText: String?,
    onDelete: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 14.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = primaryText, fontSize = 17.sp)
            if (secondaryText != null) {
                Text(
                    text = secondaryText,
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        IconButton(onClick = onDelete) {
            Icon(
                Icons.Filled.Delete,
                contentDescription = "Delete",
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
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
