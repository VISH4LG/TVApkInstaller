package com.example.tvinstaller

import android.content.Context
import android.net.ConnectivityManager
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import dadb.AdbKeyPair
import dadb.Dadb
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.Socket

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val prefs = getSharedPreferences("crash", Context.MODE_PRIVATE)
        Thread.setDefaultUncaughtExceptionHandler { _, e ->
            prefs.edit().putString("log", android.util.Log.getStackTraceString(e)).commit()
            android.os.Process.killProcess(android.os.Process.myPid())
        }
        val saved = prefs.getString("log", null)
        if (saved != null) {
            prefs.edit().remove("log").apply()
            val tv = android.widget.TextView(this).apply {
                text = saved
                setPadding(32, 96, 32, 32)
                setTextIsSelectable(true)
            }
            setContentView(android.widget.ScrollView(this).apply { addView(tv) })
            return
        }
        setContent { MaterialTheme { Surface(Modifier.fillMaxSize()) { App() } } }
    }
}

fun phoneSubnet(ctx: Context): String? {
    val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    val props = cm.getLinkProperties(cm.activeNetwork) ?: return null
    val addr = props.linkAddresses.map { it.address }.filterIsInstance<Inet4Address>().firstOrNull()
        ?: return null
    return addr.hostAddress?.substringBeforeLast('.')
}

suspend fun scanTvs(subnet: String): List<String> = coroutineScope {
    (1..254).map { i ->
        async(Dispatchers.IO) {
            val ip = "$subnet.$i"
            try {
                Socket().use { it.connect(InetSocketAddress(ip, 5555), 400) }
                ip
            } catch (e: Exception) {
                null
            }
        }
    }.awaitAll().filterNotNull()
}

fun displayName(ctx: Context, uri: Uri): String {
    ctx.contentResolver.query(uri, null, null, null, null)?.use { c ->
        val idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
        if (idx >= 0 && c.moveToFirst()) return c.getString(idx)
    }
    return uri.lastPathSegment ?: "selected.apk"
}

suspend fun installApk(ctx: Context, ip: String, uri: Uri) = withContext(Dispatchers.IO) {
    val priv = File(ctx.filesDir, "adbkey")
    val pub = File(ctx.filesDir, "adbkey.pub")
    if (!priv.exists() || !pub.exists()) AdbKeyPair.generate(priv, pub)
    val keyPair = AdbKeyPair.read(priv, pub)

    val apk = File(ctx.cacheDir, "install.apk")
    ctx.contentResolver.openInputStream(uri)!!.use { input ->
        apk.outputStream().use { out -> input.copyTo(out) }
    }
    try {
        Dadb.create(ip, 5555, keyPair).use { it.install(apk) }
    } finally {
        apk.delete()
    }
}

@Composable
fun App() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()

    var tvs by remember { mutableStateOf(listOf<String>()) }
    var scanning by remember { mutableStateOf(false) }
    var selectedTv by remember { mutableStateOf<String?>(null) }
    var manualIp by remember { mutableStateOf("") }
    var apkUri by remember { mutableStateOf<Uri?>(null) }
    var apkName by remember { mutableStateOf<String?>(null) }
    var installing by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("") }

    fun scan() {
        scope.launch {
            scanning = true
            status = ""
            val subnet = phoneSubnet(ctx)
            tvs = if (subnet == null) {
                status = "Not connected to Wi-Fi."
                emptyList()
            } else scanTvs(subnet)
            scanning = false
        }
    }

    LaunchedEffect(Unit) { scan() }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            apkUri = uri
            apkName = displayName(ctx, uri)
        }
    }

    val target = selectedTv ?: manualIp.trim().ifEmpty { null }

    Column(
        Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("TV APK Installer", style = MaterialTheme.typography.headlineSmall)

        Text("1. Select your TV", style = MaterialTheme.typography.titleMedium)
        if (scanning) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                Text("Scanning your Wi-Fi...")
            }
        } else if (tvs.isEmpty()) {
            Text("No TVs found. Check that Network debugging is on, or enter the IP below.")
        }
        tvs.forEach { ip ->
            val sel = ip == selectedTv
            Surface(
                tonalElevation = if (sel) 6.dp else 1.dp,
                shape = MaterialTheme.shapes.medium,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { selectedTv = ip }
            ) {
                Text(
                    (if (sel) "✓  " else "") + "Android TV  •  $ip",
                    Modifier.padding(16.dp)
                )
            }
        }
        OutlinedButton(onClick = { scan() }, enabled = !scanning) { Text("Rescan") }
        OutlinedTextField(
            value = manualIp,
            onValueChange = { manualIp = it; selectedTv = null },
            label = { Text("Or enter TV IP manually") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )

        Text("2. Choose the APK", style = MaterialTheme.typography.titleMedium)
        OutlinedButton(onClick = { picker.launch(arrayOf("*/*")) }) {
            Text(apkName ?: "Pick APK file")
        }

        Text("3. Install", style = MaterialTheme.typography.titleMedium)
        Button(
            enabled = target != null && apkUri != null && !installing,
            onClick = {
                scope.launch {
                    installing = true
                    status = "Installing on $target... accept the prompt on the TV if it appears."
                    status = try {
                        installApk(ctx, target!!, apkUri!!)
                        "Installed successfully."
                    } catch (e: Exception) {
                        "Failed: ${e.message ?: e.javaClass.simpleName}"
                    }
                    installing = false
                }
            }
        ) { Text(if (installing) "Installing..." else "Install on TV") }

        if (status.isNotEmpty()) Text(status)
    }
}