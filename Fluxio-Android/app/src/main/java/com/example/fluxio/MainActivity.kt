package com.example.fluxio

import android.app.Application
import android.content.pm.ApplicationInfo
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.fluxio.ui.theme.FluxioTheme
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import java.util.UUID

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val model = ViewModelProvider(this)[TransferViewModel::class.java]
        setContent {
            FluxioTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { padding ->
                    TransferScreen(model, Modifier.padding(padding))
                }
            }
        }
    }
}

class TransferViewModel(application: Application) : AndroidViewModel(application) {
    private val debug = application.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0
    private val preferences = application.getSharedPreferences("fluxio-transfers", 0)
    private var client: FluxioClient? = null
    private var token: String? by mutableStateOf(null)
    private var historyKey = ""
    private var pendingDownload: UUID? = null

    var endpoint by mutableStateOf(if (debug) "http://10.0.2.2:8080/" else "")
    var username by mutableStateOf("fluxio")
    var password by mutableStateOf("")
    var fileId by mutableStateOf("")
    var status by mutableStateOf("")
        private set
    var busy by mutableStateOf(false)
        private set
    val signedIn get() = token != null

    fun login() = runTask("Signing in...") {
        val url = endpoint.trim().toHttpUrl()
        require(url.username.isEmpty() && url.password.isEmpty() &&
            url.query == null && url.fragment == null) { "Enter a server base URL." }
        require(url.isHttps || (debug && url.host == "10.0.2.2")) {
            "Use HTTPS for the server URL."
        }
        val account = username.trim()
        val secret = password
        val api = FluxioClient(getApplication(), url)
        val session = withContext(Dispatchers.IO) { api.login(account, secret) }
        client = api
        token = session
        password = ""
        historyKey = "$url\n$account"
        fileId = preferences.getString(historyKey, "").orEmpty()
        status = "Signed in."
    }

    fun signOut() {
        token = null
        client = null
        status = ""
    }

    fun upload(source: Uri) = runTask("Encrypting and uploading...") {
        val api = checkNotNull(client)
        val session = checkNotNull(token)
        val id = withContext(Dispatchers.IO) { api.upload(session, source) }
        fileId = id.toString()
        preferences.edit().putString(historyKey, fileId).apply()
        status = "Upload complete. Keep the file ID to restore it later."
    }

    fun prepareDownload(): Boolean {
        val input = fileId.trim()
        pendingDownload = runCatching { UUID.fromString(input) }.getOrNull()
            ?.takeIf { it.toString().equals(input, ignoreCase = true) }
        if (pendingDownload == null) status = "Enter a valid file ID."
        return pendingDownload != null
    }

    fun download(destination: Uri?) {
        val id = pendingDownload
        pendingDownload = null
        if (id == null || destination == null) return
        runTask("Downloading and verifying...") {
            val api = checkNotNull(client)
            val session = checkNotNull(token)
            withContext(Dispatchers.IO) { api.download(session, id, destination) }
            status = "File restored."
        }
    }

    private fun runTask(message: String, task: suspend () -> Unit) {
        if (busy) return
        busy = true
        status = message
        viewModelScope.launch {
            try {
                task()
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                status = error.localizedMessage ?: "Operation failed."
            } finally {
                busy = false
            }
        }
    }
}

@Composable
private fun TransferScreen(model: TransferViewModel, modifier: Modifier = Modifier) {
    val upload = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let(model::upload)
    }
    val download = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/octet-stream"), model::download
    )
    Column(
        modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("Fluxio", style = MaterialTheme.typography.headlineMedium)
        if (!model.signedIn) {
            OutlinedTextField(model.endpoint, { model.endpoint = it }, Modifier.fillMaxWidth(),
                enabled = !model.busy, label = { Text("Server URL") }, singleLine = true)
            OutlinedTextField(model.username, { model.username = it }, Modifier.fillMaxWidth(),
                enabled = !model.busy, label = { Text("Username") }, singleLine = true)
            OutlinedTextField(model.password, { model.password = it }, Modifier.fillMaxWidth(),
                enabled = !model.busy, label = { Text("Password") }, singleLine = true,
                visualTransformation = PasswordVisualTransformation())
            Button(onClick = model::login, enabled = !model.busy && model.password.isNotEmpty()) {
                Text("Sign in")
            }
        } else {
            Button(onClick = { upload.launch(arrayOf("*/*")) }, enabled = !model.busy) {
                Text("Select file and upload")
            }
            OutlinedTextField(model.fileId, { model.fileId = it }, Modifier.fillMaxWidth(),
                enabled = !model.busy, label = { Text("File ID") }, singleLine = true)
            Button(
                onClick = { if (model.prepareDownload()) download.launch("restored-file") },
                enabled = !model.busy && model.fileId.isNotBlank()
            ) { Text("Download and restore") }
            Button(onClick = model::signOut, enabled = !model.busy) { Text("Sign out") }
        }
        if (model.status.isNotEmpty()) Text(model.status)
    }
}
