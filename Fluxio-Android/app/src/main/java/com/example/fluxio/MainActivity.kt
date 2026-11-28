package com.example.fluxio

import android.app.Application
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.fluxio.ui.theme.FluxioTheme
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

enum class UploadMode(val label: String) {
    NORMAL("Upload bình thường — single PUT"),
    OPTIMIZED("Upload tối ưu — multipart")
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val model = ViewModelProvider(this)[TransferViewModel::class.java]
        setContent {
            FluxioTheme {
                TransferApp(model)
            }
        }
    }
}

class TransferViewModel(application: Application) : AndroidViewModel(application) {
    private val backendUrl = BackendEndpoint.parse(BuildConfig.BACKEND_BASE_URL)
    private val preferences = application.getSharedPreferences("fluxio-transfers", 0)
    private var client: FluxioClient? = null
    private var token: String? by mutableStateOf(null)
    private var historyKey = ""
    private var pendingDownload: UUID? = null

    var username by mutableStateOf("fluxio")
    var password by mutableStateOf("")
    var fileId by mutableStateOf("")
    var uploadMode by mutableStateOf(UploadMode.NORMAL)
    var status by mutableStateOf("")
        private set
    var isError by mutableStateOf(false)
        private set
    var busy by mutableStateOf(false)
        private set
    val signedIn get() = token != null

    fun login() = runTask("Signing in...") {
        val account = username.trim()
        val secret = password
        val api = FluxioClient(getApplication(), backendUrl)
        val session = withContext(Dispatchers.IO) { api.login(account, secret) }
        client = api
        token = session
        password = ""
        historyKey = "$backendUrl\n$account"
        fileId = preferences.getString(historyKey, "").orEmpty()
        isError = false
        status = "Signed in."
    }

    fun signOut() {
        token = null
        client = null
        isError = false
        status = ""
    }

    fun upload(source: Uri) = runTask(
        if (uploadMode == UploadMode.NORMAL) "Uploading with single PUT..."
        else "Uploading with multipart..."
    ) {
        val api = checkNotNull(client)
        val session = checkNotNull(token)
        val id = withContext(Dispatchers.IO) {
            if (uploadMode == UploadMode.NORMAL) api.uploadSingle(session, source)
            else api.uploadMultipart(session, source)
        }
        fileId = id.toString()
        preferences.edit().putString(historyKey, fileId).apply()
        isError = false
        status = "Upload complete. Keep the file ID to restore it later."
    }

    fun prepareDownload(): Boolean {
        val input = fileId.trim()
        pendingDownload = runCatching { UUID.fromString(input) }.getOrNull()
            ?.takeIf { it.toString().equals(input, ignoreCase = true) }
        if (pendingDownload == null) {
            isError = true
            status = "Enter a valid file ID."
        }
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
            isError = false
            status = "File restored."
        }
    }

    private fun runTask(message: String, task: suspend () -> Unit) {
        if (busy) return
        busy = true
        isError = false
        status = message
        viewModelScope.launch {
            try {
                task()
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                isError = true
                status = error.localizedMessage ?: "Operation failed."
            } finally {
                busy = false
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TransferApp(model: TransferViewModel) {
    Scaffold(
        modifier = Modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = "Fluxio",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = if (model.signedIn) "Kết nối: ${model.username}" else "Truyền tải file hiệu năng cao",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                },
                actions = {
                    if (model.signedIn) {
                        TextButton(
                            onClick = model::signOut,
                            enabled = !model.busy
                        ) {
                            Text("Sign out")
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    titleContentColor = MaterialTheme.colorScheme.onSurface
                )
            )
        }
    ) { padding ->
        TransferScreen(
            model = model,
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        )
    }
}

@Composable
private fun TransferScreen(model: TransferViewModel, modifier: Modifier = Modifier) {
    val uploadLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let(model::upload)
    }
    val downloadLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/octet-stream"),
        model::download
    )

    BoxWithConstraints(
        modifier = modifier
            .fillMaxSize()
            .imePadding()
            .verticalScroll(rememberScrollState()),
        contentAlignment = Alignment.TopCenter
    ) {
        val isExpanded = maxWidth >= 640.dp
        val horizontalPadding = if (isExpanded) 32.dp else 16.dp
        val maxContentWidth = if (model.signedIn) {
            if (isExpanded) 1040.dp else 560.dp
        } else {
            460.dp
        }

        Column(
            modifier = Modifier
                .widthIn(max = maxContentWidth)
                .fillMaxWidth()
                .padding(horizontal = horizontalPadding, vertical = 20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            StatusBanner(
                status = model.status,
                busy = model.busy,
                isError = model.isError
            )

            if (!model.signedIn) {
                LoginCard(model = model)
            } else if (isExpanded) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                    verticalAlignment = Alignment.Top
                ) {
                    UploadCard(
                        model = model,
                        onPickFile = { uploadLauncher.launch(arrayOf("*/*")) },
                        modifier = Modifier.weight(1f)
                    )
                    DownloadCard(
                        model = model,
                        onDownloadClick = {
                            if (model.prepareDownload()) {
                                downloadLauncher.launch("restored-file")
                            }
                        },
                        modifier = Modifier.weight(1f)
                    )
                }
            } else {
                UploadCard(
                    model = model,
                    onPickFile = { uploadLauncher.launch(arrayOf("*/*")) },
                    modifier = Modifier.fillMaxWidth()
                )
                DownloadCard(
                    model = model,
                    onDownloadClick = {
                        if (model.prepareDownload()) {
                            downloadLauncher.launch("restored-file")
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
    }
}

@Composable
private fun StatusBanner(
    status: String,
    busy: Boolean,
    isError: Boolean,
    modifier: Modifier = Modifier
) {
    AnimatedVisibility(visible = status.isNotEmpty() || busy) {
        val containerColor = when {
            isError -> MaterialTheme.colorScheme.errorContainer
            busy -> MaterialTheme.colorScheme.primaryContainer
            else -> MaterialTheme.colorScheme.secondaryContainer
        }
        val contentColor = when {
            isError -> MaterialTheme.colorScheme.onErrorContainer
            busy -> MaterialTheme.colorScheme.onPrimaryContainer
            else -> MaterialTheme.colorScheme.onSecondaryContainer
        }

        Surface(
            modifier = modifier.fillMaxWidth(),
            shape = RoundedCornerShape(14.dp),
            color = containerColor,
            contentColor = contentColor
        ) {
            Column(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (status.isNotEmpty()) {
                    Text(
                        text = status,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium
                    )
                }
                if (busy) {
                    LinearProgressIndicator(
                        modifier = Modifier.fillMaxWidth(),
                        color = contentColor,
                        trackColor = contentColor.copy(alpha = 0.22f)
                    )
                }
            }
        }
    }
}

@Composable
private fun LoginCard(
    model: TransferViewModel,
    modifier: Modifier = Modifier
) {
    val focusManager = LocalFocusManager.current

    ElevatedCard(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.elevatedCardColors(
            containerColor = MaterialTheme.colorScheme.surface
        )
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text = "Đăng nhập máy chủ",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = "Nhập tài khoản để bắt đầu tải lên hoặc khôi phục file.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            OutlinedTextField(
                value = model.username,
                onValueChange = { model.username = it },
                modifier = Modifier.fillMaxWidth(),
                enabled = !model.busy,
                label = { Text("Username") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next)
            )

            OutlinedTextField(
                value = model.password,
                onValueChange = { model.password = it },
                modifier = Modifier.fillMaxWidth(),
                enabled = !model.busy,
                label = { Text("Password") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Password,
                    imeAction = ImeAction.Done
                ),
                keyboardActions = KeyboardActions(
                    onDone = {
                        focusManager.clearFocus()
                        if (!model.busy && model.password.isNotEmpty()) {
                            model.login()
                        }
                    }
                )
            )

            Spacer(modifier = Modifier.height(2.dp))

            Button(
                onClick = {
                    focusManager.clearFocus()
                    model.login()
                },
                enabled = !model.busy && model.password.isNotEmpty(),
                modifier = Modifier.fillMaxWidth(),
                contentPadding = PaddingValues(vertical = 14.dp),
                shape = RoundedCornerShape(12.dp)
            ) {
                Text("Sign in")
            }
        }
    }
}

@Composable
private fun UploadCard(
    model: TransferViewModel,
    onPickFile: () -> Unit,
    modifier: Modifier = Modifier
) {
    ElevatedCard(
        modifier = modifier,
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.elevatedCardColors(
            containerColor = MaterialTheme.colorScheme.surface
        )
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text = "Tải lên (Upload)",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = "Chọn phương thức truyền tải để so sánh hiệu năng hoặc tải file lên MinIO.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                UploadModeOption(
                    mode = UploadMode.NORMAL,
                    description = "Truyền 1 luồng trực tiếp (B1), không chia chunk, không resume.",
                    selected = model.uploadMode == UploadMode.NORMAL,
                    enabled = !model.busy,
                    onClick = { model.uploadMode = UploadMode.NORMAL }
                )
                UploadModeOption(
                    mode = UploadMode.OPTIMIZED,
                    description = "Chia nhiều part, streaming giới hạn bộ nhớ và hỗ trợ resume.",
                    selected = model.uploadMode == UploadMode.OPTIMIZED,
                    enabled = !model.busy,
                    onClick = { model.uploadMode = UploadMode.OPTIMIZED }
                )
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.35f))

            Button(
                onClick = onPickFile,
                enabled = !model.busy,
                modifier = Modifier.fillMaxWidth(),
                contentPadding = PaddingValues(vertical = 14.dp),
                shape = RoundedCornerShape(12.dp)
            ) {
                Text("Chọn file và upload")
            }
        }
    }
}

@Composable
private fun UploadModeOption(
    mode: UploadMode,
    description: String,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit
) {
    val borderColor = if (selected) {
        MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.outline.copy(alpha = 0.5f)
    }
    val containerColor = if (selected) {
        MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f)
    } else {
        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
    }

    OutlinedCard(
        onClick = onClick,
        enabled = enabled,
        shape = RoundedCornerShape(14.dp),
        border = BorderStroke(if (selected) 1.5.dp else 1.dp, borderColor),
        colors = CardDefaults.outlinedCardColors(containerColor = containerColor),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            RadioButton(
                selected = selected,
                onClick = null,
                enabled = enabled
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = mode.label,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium
                )
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun DownloadCard(
    model: TransferViewModel,
    onDownloadClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    ElevatedCard(
        modifier = modifier,
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.elevatedCardColors(
            containerColor = MaterialTheme.colorScheme.surface
        )
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text = "Tải xuống & Khôi phục",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = "Nhập File ID (UUID) đã lưu để tải về và khôi phục file.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            OutlinedTextField(
                value = model.fileId,
                onValueChange = { model.fileId = it },
                modifier = Modifier.fillMaxWidth(),
                enabled = !model.busy,
                label = { Text("File ID") },
                placeholder = { Text("xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done)
            )

            Box(modifier = Modifier.fillMaxWidth()) {
                FilledTonalButton(
                    onClick = onDownloadClick,
                    enabled = !model.busy && model.fileId.isNotBlank(),
                    modifier = Modifier.fillMaxWidth(),
                    contentPadding = PaddingValues(vertical = 14.dp),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text("Download and restore")
                }
            }
        }
    }
}
