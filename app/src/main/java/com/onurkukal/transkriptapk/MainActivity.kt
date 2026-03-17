package com.onurkukal.transkriptapk

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.documentfile.provider.DocumentFile
import com.onurkukal.transkriptapk.ui.theme.TranskriptAPKTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import java.io.File
import java.io.FileOutputStream

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            TranskriptAPKTheme {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    AppScreen()
                }
            }
        }
    }
}

@Composable
private fun AppScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var apiKey by remember { mutableStateOf("") }
    var selectedUri by remember { mutableStateOf<Uri?>(null) }
    var selectedName by remember { mutableStateOf("Henüz dosya seçilmedi") }
    var transcript by remember { mutableStateOf("") }
    var isLoading by remember { mutableStateOf(false) }
    var model by remember { mutableStateOf("whisper-1") }

    val picker = rememberLauncher(context) { uri, name ->
        selectedUri = uri
        selectedName = name ?: "Seçilen dosya"
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("Transkript APK", style = MaterialTheme.typography.headlineMedium)
        Text("Ses dosyası seç, OpenAI API anahtarını gir ve transkript oluştur.")

        OutlinedTextField(
            value = apiKey,
            onValueChange = { apiKey = it },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("OpenAI API Key") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            singleLine = true
        )

        OutlinedTextField(
            value = model,
            onValueChange = { model = it },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Model") },
            supportingText = { Text("Örn: whisper-1 veya gpt-4o-mini-transcribe") },
            singleLine = true
        )

        Button(onClick = { picker.launch(arrayOf("audio/*")) }) {
            Text("Ses Dosyası Seç")
        }

        Text("Seçilen dosya: $selectedName")

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = {
                    val uri = selectedUri
                    if (apiKey.isBlank() || uri == null) {
                        Toast.makeText(context, "API key ve dosya gerekli.", Toast.LENGTH_SHORT).show()
                        return@Button
                    }
                    scope.launch {
                        isLoading = true
                        transcript = ""
                        val result = runCatching {
                            transcribeAudio(context, uri, apiKey.trim(), model.trim())
                        }
                        transcript = result.getOrElse { "Hata: ${it.message}" }
                        isLoading = false
                    }
                },
                enabled = !isLoading
            ) {
                Text("Transkript Oluştur")
            }

            Button(
                onClick = {
                    saveTranscript(context, transcript)
                },
                enabled = transcript.isNotBlank() && !isLoading
            ) {
                Text("TXT Kaydet")
            }
        }

        if (isLoading) {
            CircularProgressIndicator()
        }

        Spacer(Modifier.height(8.dp))

        OutlinedTextField(
            value = transcript,
            onValueChange = { transcript = it },
            modifier = Modifier
                .fillMaxWidth()
                .height(360.dp),
            label = { Text("Transkript") }
        )
    }
}

@Composable
private fun rememberLauncher(
    context: Context,
    onPicked: (Uri?, String?) -> Unit
): androidx.activity.result.ActivityResultLauncher<Array<String>> {
    return androidx.activity.compose.rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        val name = uri?.let {
            DocumentFile.fromSingleUri(context, it)?.name
        }
        onPicked(uri, name)
    }
}

private suspend fun transcribeAudio(
    context: Context,
    uri: Uri,
    apiKey: String,
    model: String
): String = withContext(Dispatchers.IO) {
    val inputStream = context.contentResolver.openInputStream(uri)
        ?: error("Dosya açılamadı")

    val tempFile = File.createTempFile("audio_upload", ".bin", context.cacheDir)
    inputStream.use { input ->
        FileOutputStream(tempFile).use { output ->
            input.copyTo(output)
        }
    }

    val fileBody = tempFile.asRequestBody("application/octet-stream".toMediaTypeOrNull())
    val multipart = MultipartBody.Builder()
        .setType(MultipartBody.FORM)
        .addFormDataPart("model", model.ifBlank { "whisper-1" })
        .addFormDataPart("file", tempFile.name, fileBody)
        .build()

    val request = Request.Builder()
        .url("https://api.openai.com/v1/audio/transcriptions")
        .addHeader("Authorization", "Bearer $apiKey")
        .post(multipart)
        .build()

    OkHttpClient().newCall(request).execute().use { response ->
        val body = response.body?.string().orEmpty()
        if (!response.isSuccessful) {
            error("API hatası: ${response.code} - $body")
        }
        val match = Regex("\"text\"\\s*:\\s*\"(.*?)\"", setOf(RegexOption.DOT_MATCHES_ALL))
            .find(body)
        return@use match?.groupValues?.get(1)
            ?.replace("\\n", "\n")
            ?.replace("\\\"", "\"")
            ?: body
    }
}

private fun saveTranscript(context: Context, transcript: String) {
    if (transcript.isBlank()) {
        Toast.makeText(context, "Kaydedilecek metin yok.", Toast.LENGTH_SHORT).show()
        return
    }

    val file = File(context.getExternalFilesDir(null), "transkript.txt")
    file.writeText(transcript)
    Toast.makeText(context, "Kaydedildi: ${file.absolutePath}", Toast.LENGTH_LONG).show()
}
