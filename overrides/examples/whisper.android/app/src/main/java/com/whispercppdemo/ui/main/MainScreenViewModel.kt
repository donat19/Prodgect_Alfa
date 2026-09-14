package com.whispercppdemo.ui.main

import android.app.Application
import android.content.Context
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.whispercpp.whisper.WhisperContext
import com.whispercppdemo.media.decodeAudioToMono16k
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

class MainScreenViewModel(application: Application) : AndroidViewModel(application) {
    var status by mutableStateOf("Модель ещё не загружена"); private set
    var progress by mutableStateOf<Float?>(null); private set
    var busy by mutableStateOf(false); private set
    var transcript by mutableStateOf(""); private set
    private val model = File(application.filesDir, "ggml-small.en.bin")
    private var whisper: WhisperContext? = null

    init { if (model.isFile) loadModel() }

    fun downloadModel() = viewModelScope.launch {
        if (busy) return@launch
        busy = true; progress = 0f; status = "Загрузка модели small.en…"
        runCatching {
            withContext(Dispatchers.IO) {
                val temp = File(model.parentFile, "${model.name}.part")
                val connection = URL(MODEL_URL).openConnection() as HttpURLConnection
                connection.connectTimeout = 20_000; connection.readTimeout = 30_000
                connection.instanceFollowRedirects = true; connection.connect()
                if (connection.responseCode !in 200..299) error("HTTP ${connection.responseCode}")
                val total = connection.contentLengthLong
                connection.inputStream.use { input -> temp.outputStream().buffered().use { output ->
                    val buffer = ByteArray(262_144); var received = 0L
                    while (true) {
                        val count = input.read(buffer); if (count < 0) break
                        output.write(buffer, 0, count); received += count
                        if (total > 0) withContext(Dispatchers.Main) {
                            progress = received.toFloat() / total
                            status = "Загружено ${received / 1_048_576} из ${total / 1_048_576} МБ"
                        }
                    }
                } }
                connection.disconnect()
                check(sha1(temp) == MODEL_SHA1) { "Контрольная сумма модели не совпала" }
                if (model.exists()) model.delete()
                check(temp.renameTo(model)) { "Не удалось сохранить модель" }
            }
            loadModelInternal()
        }.onFailure { status = "Ошибка загрузки: ${it.message}" }
        progress = null; busy = false
    }

    fun importModel(uri: Uri) = viewModelScope.launch {
        if (busy) return@launch
        busy = true; status = "Копирование и проверка модели…"
        runCatching {
            withContext(Dispatchers.IO) {
                val temp = File(model.parentFile, "${model.name}.part")
                getApplication<Application>().contentResolver.openInputStream(uri)!!.use { input ->
                    temp.outputStream().use { output -> input.copyTo(output) }
                }
                check(sha1(temp) == MODEL_SHA1) { "Нужен файл ggml-small.en.bin" }
                if (model.exists()) model.delete()
                check(temp.renameTo(model)) { "Не удалось сохранить модель" }
            }
            loadModelInternal()
        }.onFailure { status = "Ошибка: ${it.message}" }
        busy = false
    }

    private fun loadModel() = viewModelScope.launch {
        busy = true
        runCatching { loadModelInternal() }.onFailure { status = "Ошибка модели: ${it.message}" }
        busy = false
    }

    private suspend fun loadModelInternal() = withContext(Dispatchers.IO) {
        whisper?.release(); whisper = WhisperContext.createContextFromFile(model.absolutePath)
        withContext(Dispatchers.Main) { status = "Модель small.en готова" }
    }

    fun transcribe(uri: Uri) = viewModelScope.launch {
        val engine = whisper ?: run { status = "Сначала загрузите модель"; return@launch }
        if (busy) return@launch
        busy = true; transcript = ""; status = "Декодирование MP3…"
        runCatching {
            val samples = withContext(Dispatchers.IO) { decodeAudioToMono16k(getApplication(), uri) }
            status = "Распознавание английской речи…"
            transcript = engine.transcribeData(samples, printTimestamp = false).trim()
            status = "Готово — ${transcript.length} символов"
        }.onFailure { status = "Ошибка: ${it.message}" }
        busy = false
    }

    fun saveText(context: Context, uri: Uri) {
        runCatching { context.contentResolver.openOutputStream(uri, "wt")!!.bufferedWriter().use { it.write(transcript) } }
            .onSuccess { status = "TXT сохранён" }.onFailure { status = "Ошибка сохранения: ${it.message}" }
    }

    override fun onCleared() {
        runBlocking { whisper?.release() }
        whisper = null
        super.onCleared()
    }

    companion object {
        private const val MODEL_URL = "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-small.en.bin"
        private const val MODEL_SHA1 = "db8a495a91d927739e50b3fc1cc4c6b8f6c2d022"
    }
}

private fun sha1(file: File): String {
    val digest = MessageDigest.getInstance("SHA-1")
    file.inputStream().buffered().use { input ->
        val buffer = ByteArray(262_144)
        while (true) { val count = input.read(buffer); if (count < 0) break; digest.update(buffer, 0, count) }
    }
    return digest.digest().joinToString("") { "%02x".format(it) }
}
