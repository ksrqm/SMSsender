package pl.sendSMS

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.telephony.SmsManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

data class SmsItem(val number: String, val text: String)

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
    var items by remember { mutableStateOf(listOf<SmsItem>()) }
    var status by remember { mutableStateOf("") }
    var sending by remember { mutableStateOf(false) }

    fun startSending() {
        scope.launch {
            sending = true
            items.forEachIndexed { i, item ->
                status = "Wysyłanie ${i + 1}/${items.size}"
                sendSms(context, item)
                delay(1500) // przerwa między wiadomościami
            }
            status = "Gotowe"
            sending = false
        }
    }

    val filePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) {
            fileName = uri.lastPathSegment ?: "plik.txt"
            items = readMessages(context, uri)
            status = "Wczytano ${items.size} wiadomości"
        }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) startSending() else status = "Brak uprawnienia SEND_SMS"
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        Text("SMS z pliku txt", style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(16.dp))

        Button(
            onClick = { filePicker.launch(arrayOf("text/plain")) },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Wybierz plik txt")
        }
        Text(fileName, modifier = Modifier.padding(vertical = 8.dp))

        // Lista wiadomości z pliku (format linii: numer;treść)
        LazyColumn(modifier = Modifier.weight(1f)) {
            items(items) { item ->
                Text("${item.number} — ${item.text}", modifier = Modifier.padding(vertical = 4.dp))
            }
        }

        Text(status, modifier = Modifier.padding(vertical = 8.dp))

        Button(
            onClick = {
                val granted = ContextCompat.checkSelfPermission(
                    context, Manifest.permission.SEND_SMS
                ) == PackageManager.PERMISSION_GRANTED
                if (granted) startSending()
                else permissionLauncher.launch(Manifest.permission.SEND_SMS)
            },
            enabled = items.isNotEmpty() && !sending,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Wyślij")
        }
    }
}

// Wczytuje linie w formacie: numer;treść
fun readMessages(context: Context, uri: Uri): List<SmsItem> {
    val stream = context.contentResolver.openInputStream(uri) ?: return emptyList()
    return stream.bufferedReader().useLines { lines ->
        lines.map { it.trim() }
            .filter { it.isNotEmpty() }
            .mapNotNull { line ->
                val parts = line.split(";", limit = 2)
                if (parts.size == 2) SmsItem(parts[0].trim(), parts[1].trim()) else null
            }
            .toList()
    }
}

fun sendSms(context: Context, item: SmsItem) {
    val sms = context.getSystemService(SmsManager::class.java)
    val parts = sms.divideMessage(item.text)
    sms.sendMultipartTextMessage(item.number, null, parts, null, null)
}