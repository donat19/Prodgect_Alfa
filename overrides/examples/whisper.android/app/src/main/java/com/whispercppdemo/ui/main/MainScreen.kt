package com.whispercppdemo.ui.main

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(viewModel: MainScreenViewModel) {
    val context = LocalContext.current
    val audioPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { it?.let(viewModel::transcribe) }
    val modelPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { it?.let(viewModel::importModel) }
    val textSaver = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) {
        it?.let { uri -> viewModel.saveText(context, uri) }
    }
    Scaffold(topBar = { TopAppBar(title = { Text("Offline MP3 → TXT") }) }) { padding ->
        Column(Modifier.padding(padding).padding(16.dp).fillMaxSize(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Английская речь распознаётся локально моделью Whisper small.en.")
            viewModel.progress?.let { LinearProgressIndicator(progress = it, modifier = Modifier.fillMaxWidth()) }
            Text(viewModel.status, style = MaterialTheme.typography.bodyMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = viewModel::downloadModel, enabled = !viewModel.busy) { Text("Скачать модель") }
                OutlinedButton(onClick = { modelPicker.launch(arrayOf("application/octet-stream", "*/*")) }, enabled = !viewModel.busy) { Text("Выбрать модель") }
            }
            Button(onClick = { audioPicker.launch(arrayOf("audio/mpeg", "audio/*")) }, enabled = !viewModel.busy, modifier = Modifier.fillMaxWidth()) {
                Text("Выбрать MP3 и расшифровать")
            }
            Button(onClick = { textSaver.launch("transcript.txt") }, enabled = viewModel.transcript.isNotBlank() && !viewModel.busy, modifier = Modifier.fillMaxWidth()) {
                Text("Сохранить TXT")
            }
            SelectionContainer { Text(viewModel.transcript.ifBlank { "Здесь появится расшифровка." }, Modifier.weight(1f).verticalScroll(rememberScrollState())) }
        }
    }
}
