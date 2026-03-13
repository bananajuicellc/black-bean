package tech.bananajuice.adzuki.android

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import android.content.Context
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.IconButton
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.documentfile.provider.DocumentFile
import tech.bananajuice.adzuki.shared.mvi.Block
import tech.bananajuice.adzuki.shared.mvi.BlockEditor
import tech.bananajuice.adzuki.shared.mvi.CodeBlock
import tech.bananajuice.adzuki.shared.mvi.DocumentState
import tech.bananajuice.adzuki.shared.mvi.DocumentViewModel
import tech.bananajuice.adzuki.shared.mvi.ParagraphBlock
import uniffi.adzuki.AstNode
import uniffi.adzuki.ParseTree
import uniffi.adzuki.parseToTree

sealed class Screen {
    object SelectFolder : Screen()
    data class JournalList(val rootUri: String) : Screen()
    data class FileList(val journalUri: String) : Screen()
    data class Editor(val fileUri: String, val journalUri: String) : Screen()
}

@Composable
fun SelectFolderScreen(onFolderSelected: (String) -> Unit) {
    val context = LocalContext.current
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            val takeFlags: Int = Intent.FLAG_GRANT_READ_URI_PERMISSION or
                    Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            context.contentResolver.takePersistableUriPermission(uri, takeFlags)
            onFolderSelected(uri.toString())
        }
    }

    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Button(onClick = { launcher.launch(null) }) {
            Text("Select Folder")
        }
    }
}

@Composable
fun JournalListScreen(rootUri: String, onJournalSelected: (String, String?) -> Unit) {
    val context = LocalContext.current
    val rootFolder = remember(rootUri) { DocumentFile.fromTreeUri(context, Uri.parse(rootUri)) }
    val journals = remember { mutableStateListOf<DocumentFile>() }
    var showNewJournalDialog by remember { mutableStateOf(false) }
    var newJournalName by remember { mutableStateOf("") }
    val prefs = context.getSharedPreferences("adzuki_prefs", Context.MODE_PRIVATE)

    LaunchedEffect(rootUri) {
        journals.clear()
        rootFolder?.listFiles()?.forEach { file ->
            if (file.isDirectory) {
                journals.add(file)
            }
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        Button(onClick = { showNewJournalDialog = true }, modifier = Modifier.padding(16.dp)) {
            Text("New Journal")
        }
        LazyColumn(modifier = Modifier.weight(1f)) {
            items(journals) { journal ->
                Text(
                    text = journal.name ?: "Unknown",
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            val journalUriStr = journal.uri.toString()
                            val mainFileUriStr = prefs.getString("main_file_$journalUriStr", null)

                            if (mainFileUriStr != null && DocumentFile.fromSingleUri(context, Uri.parse(mainFileUriStr))?.exists() == true) {
                                onJournalSelected(journalUriStr, mainFileUriStr)
                            } else {
                                val files = journal.listFiles()
                                val mainBeancountMd = files.find { it.name == "main.beancount.md" }
                                val mainBeancount = files.find { it.name == "main.beancount" }
                                val fileUri = mainBeancountMd?.uri ?: mainBeancount?.uri
                                onJournalSelected(journalUriStr, fileUri?.toString())
                            }
                        }
                        .padding(16.dp)
                )
            }
        }
    }

    if (showNewJournalDialog) {
        AlertDialog(
            onDismissRequest = { showNewJournalDialog = false },
            title = { Text("New Journal") },
            text = {
                OutlinedTextField(
                    value = newJournalName,
                    onValueChange = { newJournalName = it },
                    label = { Text("Name") }
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    val newDir = rootFolder?.createDirectory(newJournalName)
                    if (newDir != null) {
                        val newFile = newDir.createFile("text/markdown", "main.beancount.md")
                        if (newFile != null) {
                            context.contentResolver.openOutputStream(newFile.uri)?.use {
                                it.write("# $newJournalName\n\n".toByteArray())
                            }
                            onJournalSelected(newDir.uri.toString(), newFile.uri.toString())
                        }
                    }
                    showNewJournalDialog = false
                    newJournalName = ""
                }) {
                    Text("Create")
                }
            },
            dismissButton = {
                TextButton(onClick = { showNewJournalDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FileListScreen(journalUri: String, onFileSelected: (String) -> Unit, onBack: () -> Unit) {
    val context = LocalContext.current
    val journalFolder = remember(journalUri) { DocumentFile.fromTreeUri(context, Uri.parse(journalUri)) }
    val files = remember { mutableStateListOf<DocumentFile>() }

    LaunchedEffect(journalUri) {
        files.clear()
        journalFolder?.listFiles()?.forEach { file ->
            if (file.isFile) {
                files.add(file)
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(journalFolder?.name ?: "Files") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { padding ->
        LazyColumn(modifier = Modifier.padding(padding).fillMaxSize()) {
            items(files) { file ->
                Text(
                    text = file.name ?: "Unknown",
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onFileSelected(file.uri.toString()) }
                        .padding(16.dp)
                )
            }
        }
    }
}

fun mapParseTreeToBlocks(tree: ParseTree): List<Block> {
    return tree.nodes.map { node ->
        when (node) {
            is AstNode.Heading -> ParagraphBlock(text = "#".repeat(node.level.toInt()) + " " + node.content)
            is AstNode.Paragraph -> ParagraphBlock(text = node.content)
            is AstNode.CodeBlock -> CodeBlock(text = node.content.trim('`', '\n'), isRaw = false)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditorScreen(fileUri: String, onBack: () -> Unit) {
    val context = LocalContext.current
    val uri = Uri.parse(fileUri)
    val file = DocumentFile.fromSingleUri(context, uri)

    val initialText = remember(fileUri) {
        try {
            context.contentResolver.openInputStream(uri)?.use { inputStream ->
                inputStream.bufferedReader().use { it.readText() }
            } ?: ""
        } catch (e: Exception) {
            ""
        }
    }

    val viewModel = remember(fileUri, initialText) {
        val parseTree = parseToTree(initialText)
        val mappedBlocks = mapParseTreeToBlocks(parseTree)
        DocumentViewModel(
            initialState = DocumentState(
                blocks = mappedBlocks
            )
        )
    }
    val state by viewModel.state.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(file?.name ?: "Editor") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { padding ->
        Box(modifier = Modifier.padding(padding).fillMaxSize()) {
            BlockEditor(
                state = state,
                onIntent = viewModel::processIntent
            )
        }
    }
}

class MainActivity : ComponentActivity() {

    init {
        System.loadLibrary("adzuki")
    }


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val prefs = getSharedPreferences("adzuki_prefs", Context.MODE_PRIVATE)
        val rootFolderUri = prefs.getString("root_folder_uri", null)

        setContent {
            MaterialTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    var currentScreen by remember {
                        mutableStateOf<Screen>(
                            if (rootFolderUri != null) Screen.JournalList(rootFolderUri)
                            else Screen.SelectFolder
                        )
                    }

                    when (val screen = currentScreen) {
                        is Screen.SelectFolder -> SelectFolderScreen(
                            onFolderSelected = { uri ->
                                prefs.edit().putString("root_folder_uri", uri).apply()
                                currentScreen = Screen.JournalList(uri)
                            }
                        )
                        is Screen.JournalList -> JournalListScreen(
                            rootUri = screen.rootUri,
                            onJournalSelected = { journalUri, fileUri ->
                                if (fileUri != null) {
                                    currentScreen = Screen.Editor(fileUri, journalUri)
                                } else {
                                    currentScreen = Screen.FileList(journalUri)
                                }
                            }
                        )
                        is Screen.FileList -> FileListScreen(
                            journalUri = screen.journalUri,
                            onFileSelected = { fileUri ->
                                prefs.edit().putString("main_file_${screen.journalUri}", fileUri).apply()
                                currentScreen = Screen.Editor(fileUri, screen.journalUri)
                            },
                            onBack = {
                                val rootUri = prefs.getString("root_folder_uri", null)
                                if (rootUri != null) {
                                    currentScreen = Screen.JournalList(rootUri)
                                } else {
                                    currentScreen = Screen.SelectFolder
                                }
                            }
                        )
                        is Screen.Editor -> EditorScreen(
                            fileUri = screen.fileUri,
                            onBack = {
                                currentScreen = Screen.FileList(screen.journalUri)
                            }
                        )
                    }
                }
            }
        }
    }
}
