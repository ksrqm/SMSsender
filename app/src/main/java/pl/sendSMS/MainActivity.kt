package pl.sendSMS // dostosuj do swojego pakietu

import android.Manifest
import android.app.Activity
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.telephony.SmsManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

const val SMS_SENT_ACTION = "com.example.smssender.SMS_SENT" // dostosuj do pakietu
const val SEND_DELAY_MS = 1500L // przerwa między wiadomościami
val NUMBER_REGEX = Regex("^\\+?[0-9]{3,15}$") // emulator: 4-cyfrowe numery typu 5556 też przejdą

// SENT = system przyjął wiadomość do wysłania (RESULT_OK), to nie jest potwierdzenie doręczenia
enum class SmsStatus(val label: String) {
    PENDING("oczekuje"),
    SENDING("wysyłanie…"),
    SENT("wysłano"),
    FAILED("błąd")
}

data class SmsItem(
    val number: String,
    val text: String,
    val status: SmsStatus = SmsStatus.PENDING,
    val info: String = ""
)

data class ParseResult(val items: List<SmsItem>, val skipped: Int)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                SmsScreen()
            }
        }
    }
}

@Composable
fun SmsScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var fileName by remember { mutableStateOf("Nie wybrano pliku") }
    val items = remember { mutableStateListOf<SmsItem>() }
    var skipped by remember { mutableStateOf(0) }
    var status by remember { mutableStateOf("") }
    var sendJob by remember { mutableStateOf<Job?>(null) }
    var showConfirm by remember { mutableStateOf(false) }

    // ile części ma wiadomość i ile już zgłosiło RESULT_OK
    val partsExpected = remember { mutableMapOf<Int, Int>() }
    val partsOk = remember { mutableMapOf<Int, Int>() }

    // Odbiornik wyników wysyłki (sentIntent z SmsManager)
    DisposableEffect(Unit) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context, intent: Intent) {
                val index = intent.getIntExtra("index", -1)
                if (index !in items.indices) return
                if (resultCode == Activity.RESULT_OK) {
                    val ok = (partsOk[index] ?: 0) + 1
                    partsOk[index] = ok
                    if (ok >= (partsExpected[index] ?: 1)) {
                        items[index] = items[index].copy(status = SmsStatus.SENT, info = "")
                    }
                } else {
                    items[index] = items[index].copy(
                        status = SmsStatus.FAILED,
                        info = "kod błędu $resultCode"
                    )
                }
            }
        }
        ContextCompat.registerReceiver(
            context,
            receiver,
            IntentFilter(SMS_SENT_ACTION),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        onDispose { context.unregisterReceiver(receiver) }
    }

    fun startSending() {
        partsOk.clear()
        partsExpected.clear()
        for (i in items.indices) items[i] = items[i].copy(status = SmsStatus.PENDING, info = "")

        sendJob = scope.launch {
            for (i in items.indices) {
                items[i] = items[i].copy(status = SmsStatus.SENDING)
                status = "Wysyłanie ${i + 1}/${items.size}"
                try {
                    partsExpected[i] = sendSms(context, i, items[i])
                } catch (e: Exception) {
                    items[i] = items[i].copy(
                        status = SmsStatus.FAILED,
                        info = e.message ?: e.javaClass.simpleName
                    )
                }
                delay(SEND_DELAY_MS)
            }
            status = "Zakończono"
            sendJob = null
        }
    }

    val filePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) {
            fileName = uri.lastPathSegment ?: "plik.txt"
            val result = readMessages(context, uri)
            items.clear()
            items.addAll(result.items)
            skipped = result.skipped
            status = "Wczytano ${items.size} wiadomości"
        }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) startSending() else status = "Brak uprawnienia SEND_SMS"
    }

    fun requestAndSend() {
        val granted = ContextCompat.checkSelfPermission(
            context, Manifest.permission.SEND_SMS
        ) == PackageManager.PERMISSION_GRANTED
        if (granted) startSending() else permissionLauncher.launch(Manifest.permission.SEND_SMS)
    }

    if (showConfirm) {
        AlertDialog(
            onDismissRequest = { showConfirm = false },
            title = { Text("Wysłać SMS-y?") },
            text = { Text("Zostanie wysłanych wiadomości: ${items.size}.") },
            confirmButton = {
                TextButton(onClick = { showConfirm = false; requestAndSend() }) { Text("Wyślij") }
            },
            dismissButton = {
                TextButton(onClick = { showConfirm = false }) { Text("Anuluj") }
            }
        )
    }

    val sending = sendJob != null
    val sentCount = items.count { it.status == SmsStatus.SENT }
    val failedCount = items.count { it.status == SmsStatus.FAILED }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        Text("SMS z pliku txt", style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(16.dp))

        Button(
            onClick = { filePicker.launch(arrayOf("text/plain")) },
            enabled = !sending,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Wybierz plik txt")
        }
        Text(fileName, modifier = Modifier.padding(top = 8.dp))
        if (skipped > 0) {
            Text("Pominięte błędne linie: $skipped", color = MaterialTheme.colorScheme.error)
        }

        // Lista wiadomości z pliku (format linii: numer;treść)
        LazyColumn(modifier = Modifier.weight(1f).padding(vertical = 8.dp)) {
            itemsIndexed(items) { i, item ->
                val info = if (item.info.isNotEmpty()) " (${item.info})" else ""
                Text(
                    "${i + 1}. ${item.number} — ${item.text}\n[${item.status.label}]$info",
                    modifier = Modifier.padding(vertical = 4.dp)
                )
            }
        }

        Text(status)
        if (items.isNotEmpty()) {
            Text("Wysłano: $sentCount, błędy: $failedCount, razem: ${items.size}")
        }
        Spacer(Modifier.height(8.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Button(
                onClick = { showConfirm = true },
                enabled = items.isNotEmpty() && !sending,
                modifier = Modifier.weight(1f)
            ) {
                Text("Wyślij")
            }
            OutlinedButton(
                onClick = {
                    sendJob?.cancel()
                    sendJob = null
                    status = "Zatrzymano"
                },
                enabled = sending,
                modifier = Modifier.weight(1f)
            ) {
                Text("Stop")
            }
        }
    }
}

// Wczytuje plik UTF-8, linie w formacie: numer;treść
fun readMessages(context: Context, uri: Uri): ParseResult {
    val stream = context.contentResolver.openInputStream(uri) ?: return ParseResult(emptyList(), 0)
    return stream.bufferedReader(Charsets.UTF_8).useLines { parseLines(it) }
}

// Osobna funkcja bez Androida, łatwa do testów jednostkowych
fun parseLines(lines: Sequence<String>): ParseResult {
    val result = mutableListOf<SmsItem>()
    var skipped = 0
    var first = true
    for (raw in lines) {
        val line = (if (first) raw.removePrefix("﻿") else raw).trim() // usuwa BOM
        first = false
        if (line.isEmpty()) continue

        val parts = line.split(";", limit = 2)
        val number = parts[0].replace(Regex("[\\s-]"), "")
        val text = parts.getOrNull(1)?.trim().orEmpty()
        if (parts.size == 2 && NUMBER_REGEX.matches(number) && text.isNotEmpty()) {
            result.add(SmsItem(number, text))
        } else {
            skipped++
        }
    }
    return ParseResult(result, skipped)
}

// Zwraca liczbę części wiadomości (długie SMS-y są dzielone)
fun sendSms(context: Context, index: Int, item: SmsItem): Int {
    val sms = context.getSystemService(SmsManager::class.java)
    val parts = sms.divideMessage(item.text)

    val intent = Intent(SMS_SENT_ACTION)
        .setPackage(context.packageName)
        .putExtra("index", index)
    val pendingIntent = PendingIntent.getBroadcast(
        context,
        index,
        intent,
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    )
    val sentIntents = ArrayList<PendingIntent>(parts.size).apply {
        repeat(parts.size) { add(pendingIntent) }
    }

    sms.sendMultipartTextMessage(item.number, null, parts, sentIntents, null)
    return parts.size
}