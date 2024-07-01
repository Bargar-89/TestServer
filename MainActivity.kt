package com.bargarapp.testserver

import android.content.Context
import android.net.wifi.WifiManager
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.ktor.server.application.install
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import io.ktor.server.routing.routing
import io.ktor.server.websocket.WebSockets
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.Frame
import io.ktor.websocket.WebSocketSession
import io.ktor.websocket.readText
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.consumeEach
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import kotlin.random.Random

class MainActivity : ComponentActivity() {
    private var server: NettyApplicationEngine? = null
    private var serverStatus by mutableStateOf("Stopped")
    private val dbHelper = DatabaseHelper(this)
    private val clients = ConcurrentHashMap<String, WebSocketSession>()
    private val clientJobs = ConcurrentHashMap<String, Job?>()



    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            var port by remember { mutableIntStateOf(8080) }
            var deviceIp by remember { mutableStateOf(getDeviceIpAddress(this)) }
            var showLogs by remember { mutableStateOf(false) }

            MaterialTheme {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(16.dp),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text("Device IP: $deviceIp")
                    Spacer(modifier = Modifier.height(16.dp))
                    OutlinedTextField(
                        value = port.toString(),
                        onValueChange = { port = it.toIntOrNull() ?: port },
                        label = { Text("Server Port") },
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceEvenly
                    ) {
                        Button(
                            onClick = { startServer(port) },
                            enabled = serverStatus == "Stopped"
                        ) {
                            Text("Start Server")
                        }
                        Button(
                            onClick = { stopServer() },
                            enabled = serverStatus == "Running"
                        ) {
                            Text("Stop Server")
                        }
                    }
                    Spacer(modifier = Modifier.height(16.dp))
                    Text("Server Status: $serverStatus")
                    Button(
                        onClick = { showLogs = !showLogs },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Show Logs")
                    }
                    if (showLogs) {
                        LogsView(dbHelper.getAllLogs())
                    }
                }
            }
        }
    }

    private fun startServer(port: Int) {
        CoroutineScope(Dispatchers.Main).launch {
            var clientIdFromMessage = " "
            var count = 0
            var clientId = " "
            server = embeddedServer(Netty, port = port, host = getDeviceIpAddress(this@MainActivity)) {
                install(WebSockets)
                routing {
                    webSocket("/gestures") {
                        try {
                            // Обработчик события onMessage
                            incoming.consumeEach { frame ->
                                if (frame is Frame.Text) {
                                    val message = frame.readText()
                                    println(message)
                                    if(message.split(",").size >1){
                                        clientIdFromMessage = message.split(",")[1]
                                    }

                                    println(clientIdFromMessage)
                                    if(clientIdFromMessage == "null"){
                                        clientId = count++.toString()
                                        send(Frame.Text(clientId))
                                    }else if( clientIdFromMessage.length < 4){
                                        clientId = clientIdFromMessage
                                    }
                                    Log.d("WebSocket", "Received message: $message")
                                    when {
                                        message.startsWith("firstcConnect") -> {
                                            //подключились и отправили id
                                            clients[clientId] = this
                                            send(Frame.Text("Connected to the server"))
                                            Log.d("WebSocket", "Client $clientId connected")
                                            //подключились
                                            dbHelper.insertLog(System.currentTimeMillis(), "Client $clientId connected")

                                        }
                                        message.startsWith("disconnect") -> {
                                            //отключились
                                            Log.d("WebSocket", "Disconnected")
                                            clients.remove(clientId)
                                            dbHelper.insertLog(System.currentTimeMillis(), "Client $clientId disconnected")
                                        }

                                        message.startsWith("start") -> {
                                            val client = clients[clientId]
                                            if(client == null){
                                                Log.d("WebSocket", "Client = null")
                                            }else{
                                                clientJobs[clientId] = startGestures(client,clientId,clientJobs)
                                                Log.d("WebSocket", "start gesture for client $clientId")
                                            }

                                        }
                                        message.startsWith("pause") -> {
                                            // Pause
                                            pauseGestures(clientId,clientJobs)
                                            Log.d("WebSocket", "pause gesture for client $clientId")
                                        }
                                        message.startsWith("Result") -> {
                                            //пишем результат
                                            dbHelper.insertLog(System.currentTimeMillis(),
                                                "Client $clientId result gesture = $message"
                                            )
                                            Log.d("WebSocket", "result from client $clientId")
                                        }
                                    }

                                }
                            }
                        } catch (e: Exception) {
                            e.printStackTrace()
                        } finally {
                            // Обработчик события onDisconnect
                            val clientJob = clientJobs.remove(clientId)
                            clientJob?.cancel() // Отменяем корутину клиента
                            clients.remove(clientId)
                        }
                    }
                }
            }.start()
            serverStatus = "Running"
        }
    }
    private fun stopServer() {
        try {
            server?.stop(1000, 1000)
            serverStatus = "Stopped"
        }catch (e:Exception){
            e.printStackTrace()
            serverStatus = "Stopped"
        }

    }

    private suspend fun startGestures(
        client: WebSocketSession,
        clientId: String,
        clientJobs: ConcurrentHashMap<String, Job?>
    ): Job {
        val gestures = generateGestures()
        return CoroutineScope(Dispatchers.Main).launch {
            for (gesture in gestures) {
                // Проверяем, активна ли корутина перед отправкой каждого жеста
                val job = clientJobs[clientId]
                if (job == null || !job.isActive) {
                    Log.d("Gestures", "Sending gestures to client $clientId is paused")
                    break
                }
                val (direction, duration) = gesture
                client.send(Frame.Text("Swipe $direction for $duration ms"))
                dbHelper.insertLog(System.currentTimeMillis(), "Sent 'Swipe $direction for $duration ms' to client $clientId ")
                delay(duration.toLong())
            }
        }
    }

    private fun pauseGestures(
        clientId: String,
        clientJobs: ConcurrentHashMap<String, Job?>
    ) {
        clientJobs[clientId]?.let { job ->
            if (job != null) {
                job.cancel()
                dbHelper.insertLog(System.currentTimeMillis(), "Paused sending gestures to client $clientId ")
            } else {
                Log.d("Gestures", "Job for client $clientId is null")

            }
        } ?: run {
            Log.d("Gestures", "No job found for client $clientId")
        }
    }

    private fun getDeviceIpAddress(context: Context): String {
        val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        val ipAddress = wifiManager.connectionInfo.ipAddress
        return String.format(
            "%d.%d.%d.%d",
            ipAddress and 0xff,
            ipAddress shr 8 and 0xff,
            ipAddress shr 16 and 0xff,
            ipAddress shr 24 and 0xff
        )
    }
    @Composable
    fun LogsView(logs: List<DatabaseHelper.LogEntry>) {
        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .height(400.dp)
        ) {
            items(logs) { log ->
                LogItem(log)
            }
        }
    }

    @Composable
    fun LogItem(log: DatabaseHelper.LogEntry) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(8.dp),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(log.message)
            Text(formatTimestamp(log.timestamp))
        }
    }

    private fun formatTimestamp(timestamp: Long): String {
        val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
        return dateFormat.format(Date(timestamp))
    }
    private fun generateGestures(): List<Pair<String, Int>> {
        val gestures = mutableListOf<Pair<String, Int>>()

        for (i in 1..999) { // Генерируем 999 жестов
            val direction = if (Random.nextBoolean()) "up" else "down"
            val duration = Random.nextInt(500, 2000) // Длительность свайпа от 500 до 2000 мс
            gestures.add(Pair(direction, duration))
        }

        return gestures
    }
}



