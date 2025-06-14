package vn.vnpt.obdtoiotservice

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.*
import java.io.Serializable

@SuppressLint("MissingPermission")
class ObdService : Service() {

    companion object {
        private const val TAG = "ObdService" // Thêm TAG cho ObdService
        const val ACTION_START_SERVICE = "vn.vnpt.obdtoiotservice.ACTION_START_OBD_SERVICE"
        const val ACTION_STOP_SERVICE = "vn.vnpt.obdtoiotservice.ACTION_STOP_OBD_SERVICE"
        const val ACTION_OBD_STATUS_UPDATE = "vn.vnpt.obdtoiotservice.ACTION_OBD_STATUS_UPDATE"
        const val ACTION_OBD_DATA_UPDATE = "vn.vnpt.obdtoiotservice.ACTION_OBD_DATA_UPDATE"
        const val EXTRA_STATUS_MESSAGE = "extra_status_message"
        const val EXTRA_PID_LIST_UPDATED = "extra_pid_list_updated"
        const val EXTRA_DEVICE_ADDRESS = "extra_device_address"
        const val EXTRA_PID_LIST = "extra_pid_list"

        private const val NOTIFICATION_CHANNEL_ID = "OBD_SERVICE_CHANNEL"
        private const val NOTIFICATION_ID = 1

        var isRunning = false
            private set
        var lastStatusMessage = "Đã dừng"
            private set
    }

    private lateinit var bluetoothManager: OBDBluetoothManager
    private val serviceScope = CoroutineScope(Dispatchers.IO)
    private var dataPollingJob: Job? = null

    private var pidsToPoll = mutableListOf<ObdPid>()

    override fun onCreate() {
        super.onCreate()
        bluetoothManager = OBDBluetoothManager(this)
        startForegroundService()
        Log.d(TAG, "ObdService created.")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START_SERVICE -> {
                if (!isRunning) {
                    val deviceAddress = intent.getStringExtra(EXTRA_DEVICE_ADDRESS)
                    @Suppress("UNCHECKED_CAST")
                    val receivedPids = intent.getSerializableExtra(EXTRA_PID_LIST) as? List<ObdPid> ?: emptyList()

                    if (deviceAddress != null && receivedPids.isNotEmpty()) {
                        pidsToPoll.clear()
                        pidsToPoll.addAll(receivedPids)
                        Log.d(TAG, "Starting data polling for device: $deviceAddress")
                        startDataPolling(deviceAddress)
                        isRunning = true
                    } else {
                        Log.e(TAG, "Device address or PIDs not provided. Stopping service.")
                        stopSelf()
                    }
                }
            }
            ACTION_STOP_SERVICE -> {
                Log.d(TAG, "Stopping service via action.")
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    private fun startDataPolling(deviceAddress: String) {
        dataPollingJob?.cancel()
        dataPollingJob = serviceScope.launch {
            val device = bluetoothManager.getPairedDevices().find { it.address == deviceAddress }
            if (device == null) {
                broadcastStatus("Lỗi: Không tìm thấy thiết bị Bluetooth với địa chỉ $deviceAddress")
                stopSelf()
                return@launch
            }

            broadcastStatus("Đang kết nối tới ${device.name}...")
            if (bluetoothManager.connect(device)) {
                broadcastStatus("Đã kết nối. Bắt đầu đọc dữ liệu.")
                bluetoothManager.sendCommand("ATZ"); bluetoothManager.readResponse()
                bluetoothManager.sendCommand("ATE0"); bluetoothManager.readResponse()

                while (isActive) {
                    pidsToPoll.forEach { pid ->
                        bluetoothManager.sendCommand(pid.command)
                        val response = bluetoothManager.readResponse()
                        pid.value = parseObdResponse(pid.command, response)
                    }
                    broadcastData(ArrayList(pidsToPoll))
                    delay(5000)
                }
            } else {
                broadcastStatus("Kết nối Bluetooth thất bại!")
                stopSelf()
            }
        }
    }

    // ================= SỬA LỖI Ở ĐÂY =================
    private fun broadcastStatus(message: String) {
        // Cập nhật biến static mỗi khi có trạng thái mới
        lastStatusMessage = message

        val intent = Intent(ACTION_OBD_STATUS_UPDATE).apply {
            setPackage(packageName)
            putExtra(EXTRA_STATUS_MESSAGE, message)
        }
        sendBroadcast(intent)
    }

    private fun broadcastData(updatedPids: ArrayList<ObdPid>) {
        Log.d("ObdService_Broadcast", "Đang gửi broadcast dữ liệu. Số lượng PIDs: ${updatedPids.size}")
        if (updatedPids.isNotEmpty()) {
            Log.d("ObdService_Broadcast", "Dữ liệu PID đầu tiên: ${updatedPids[0].name} = ${updatedPids[0].value}")
        }

        val intent = Intent(ACTION_OBD_DATA_UPDATE).apply {
            // Thêm dòng này để biến broadcast thành dạng tường minh
            setPackage(packageName)
            putExtra(EXTRA_PID_LIST_UPDATED, updatedPids as Serializable)
        }
        sendBroadcast(intent)
    }
    // ==================================================

    // Các hàm còn lại giữ nguyên
    private fun parseObdResponse(command: String, response: String?): String {
        if (response.isNullOrEmpty()) return "Lỗi"
        val cleanResponse = response.replace(" ", "").replace(">", "")
        val expectedHeader = "41" + command.substring(2)
        if (!cleanResponse.startsWith(expectedHeader)) return "N/A"
        val hexData = cleanResponse.substring(expectedHeader.length)
        return try {
            when (command) {
                "010D" -> hexData.toInt(16).toString()
                "010C" -> (((hexData.substring(0, 2).toInt(16) * 256) + hexData.substring(2, 4).toInt(16)) / 4).toString()
                "0105", "010F" -> (hexData.toInt(16) - 40).toString()
                "0104", "0111" -> "%.1f".format(hexData.toInt(16) * 100 / 255.0)
                "015E" -> "%.2f".format(((hexData.substring(0, 2).toInt(16) * 256) + hexData.substring(2, 4).toInt(16)) / 20.0)
                "0131" -> ((hexData.substring(0, 2).toInt(16) * 256) + hexData.substring(2, 4).toInt(16)).toString()
                "0142" -> "%.2f".format(((hexData.substring(0, 2).toInt(16) * 256) + hexData.substring(2, 4).toInt(16)) / 1000.0)
                else -> "Chưa hỗ trợ"
            }
        } catch (e: Exception) { "Lỗi Parse" }
    }
    private fun startForegroundService() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(NOTIFICATION_CHANNEL_ID, "OBD Service Channel", NotificationManager.IMPORTANCE_LOW)
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }
        val notification: Notification = NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID).setContentTitle("Dịch vụ OBD").setContentText("Đang chạy...").setSmallIcon(R.drawable.ic_launcher_foreground).build()
        startForeground(NOTIFICATION_ID, notification)
    }
    private fun stopServiceTasks() {
        dataPollingJob?.cancel()
        bluetoothManager.disconnect()
        isRunning = false
        // Khi dừng, cập nhật trạng thái cuối cùng là "Đã dừng"
        broadcastStatus("Đã dừng")
        Log.d(TAG, "All service tasks stopped.")
    }
    override fun onDestroy() {
        stopServiceTasks()
        super.onDestroy()
        Log.d(TAG, "ObdService destroyed.")
    }
    override fun onBind(intent: Intent?): IBinder? { return null }
}