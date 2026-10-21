package com.example.esp32hub

import android.app.Application
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.AndroidViewModel

class HubVm(app: Application) : AndroidViewModel(app) {
    val log = mutableStateListOf<String>()
    var connected by mutableStateOf(false)
    private val ui = Handler(Looper.getMainLooper())
    private val client = HubClient(app)

    init {
        client.onLine = { l -> ui.post { log.add(l); if (log.size > 600) log.removeRange(0, 100) } }
        client.onState = { c -> ui.post { connected = c } }
    }

    fun connect(host: String, port: Int, pass: String) = client.connect(host, port, pass)
    fun disconnect() = client.disconnect()
    fun send(cmd: String) { if (cmd.isBlank()) return; log.add("> $cmd"); client.send(cmd.trim()) }
    fun clear() = log.clear()
    override fun onCleared() { client.disconnect() }
}

class MainActivity : ComponentActivity() {
    private val vm: HubVm by viewModels()
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { MaterialTheme { Surface(Modifier.fillMaxSize()) { HubScreen(vm) } } }
    }
}

@Composable
fun HubScreen(vm: HubVm) {
    var host by rememberSaveable { mutableStateOf("192.168.4.1") }
    var port by rememberSaveable { mutableStateOf("6000") }
    var pass by rememberSaveable { mutableStateOf("") }
    var cmd by rememberSaveable { mutableStateOf("") }
    var sid by rememberSaveable { mutableStateOf("1") }
    var reg by rememberSaveable { mutableStateOf("0") }
    var value by rememberSaveable { mutableStateOf("") }
    val listState = rememberLazyListState()
    LaunchedEffect(vm.log.size) { if (vm.log.isNotEmpty()) listState.animateScrollToItem(vm.log.size - 1) }

    Column(Modifier.fillMaxSize().systemBarsPadding().imePadding().padding(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {

        if (!vm.connected) {
            Text("ESP32 Hub", style = MaterialTheme.typography.titleLarge)
            Text("First join the hidden Wi-Fi in Android settings.", fontSize = 12.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(host, { host = it }, label = { Text("Master IP") }, singleLine = true, modifier = Modifier.weight(2f))
                OutlinedTextField(port, { port = it }, label = { Text("Port") }, singleLine = true, modifier = Modifier.weight(1f),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
            }
            OutlinedTextField(pass, { pass = it }, label = { Text("Master password") }, singleLine = true,
                visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
            Button({ vm.connect(host.trim(), port.toIntOrNull() ?: 6000, pass) }, Modifier.fillMaxWidth()) { Text("Connect") }
        } else {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Connected", color = Color(0xFF2E7D32), modifier = Modifier.weight(1f))
                TextButton({ vm.clear() }) { Text("Clear") }
                OutlinedButton({ vm.disconnect() }) { Text("Disconnect") }
            }
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf("SLAVES", "STATUS", "ZONE LIST", "MON 1", "MON 0", "HELP").forEach { c ->
                    AssistChip(onClick = { vm.send(c) }, label = { Text(c) })
                }
            }
            // register panel
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                val kb = KeyboardOptions(keyboardType = KeyboardType.Number)
                OutlinedTextField(sid, { sid = it }, label = { Text("Slave") }, singleLine = true, modifier = Modifier.weight(1f), keyboardOptions = kb)
                OutlinedTextField(reg, { reg = it }, label = { Text("Reg") }, singleLine = true, modifier = Modifier.weight(1f), keyboardOptions = kb)
                OutlinedTextField(value, { value = it }, label = { Text("Value") }, singleLine = true, modifier = Modifier.weight(1.3f))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                OutlinedButton({ vm.send("PING $sid") }, Modifier.weight(1f)) { Text("Ping") }
                OutlinedButton({ vm.send("READ $sid $reg") }, Modifier.weight(1f)) { Text("Read") }
                OutlinedButton({ vm.send("READALL $sid") }, Modifier.weight(1f)) { Text("All") }
                Button({ vm.send("WRITE $sid $reg ${value.trim()}") }, Modifier.weight(1f)) { Text("Write") }
            }
        }

        LazyColumn(Modifier.weight(1f).fillMaxWidth(), state = listState) {
            items(vm.log) { l ->
                val c = when {
                    l.startsWith("ERR") || l.startsWith("# Error") -> Color(0xFFC62828)
                    l.startsWith("EVT") -> Color(0xFF1565C0)
                    l.startsWith(">") -> Color(0xFF6A1B9A)
                    l.startsWith("#") -> Color.Gray
                    else -> Color.Unspecified
                }
                Text(l, color = c, fontFamily = FontFamily.Monospace, fontSize = 12.sp)
            }
        }

        if (vm.connected) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                OutlinedTextField(cmd, { cmd = it }, placeholder = { Text("command, e.g. ZONE SELECT 0") }, singleLine = true, modifier = Modifier.weight(1f))
                Button({ vm.send(cmd); cmd = "" }) { Text("Send") }
            }
        }
    }
}
