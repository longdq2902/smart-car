package vn.vnpt.obdtoiotservice

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

class MqttService : Service() {

    companion object {
        private const val TAG = "MqttService"

        // Actions để điều khiển MqttService
        const val ACTION_START_SERVICE = "vn.vnpt.obdtoiotservice.ACTION_START_MQTT_SERVICE"
        const val ACTION_STOP_SERVICE = "vn.vnpt.obdtoiotservice.ACTION_STOP_MQTT_SERVICE"

        // Action để broadcast trạng thái
        const val ACTION_MQTT_STATUS_UPDATE = "vn.vnpt.obdtoiotservice.ACTION_MQTT_STATUS_UPDATE"
        const val EXTRA_STATUS_MESSAGE = "extra_mqtt_status_message"

        // ID cho notification, phải khác với của ObdService
        private const val NOTIFICATION_CHANNEL_ID = "MQTT_SERVICE_CHANNEL"
        private const val NOTIFICATION_ID = 2 // Phải khác ID của ObdService

        var isRunning = false
            private set
        var lastStatusMessage = "Đã dừng"
            private set
    }

    private lateinit var mqttManager: MqttManager
    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // BroadcastReceiver để lắng nghe dữ liệu từ ObdService
    private val obdDataReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == ObdService.ACTION_OBD_DATA_UPDATE) {
                @Suppress("UNCHECKED_CAST")
                val pids = intent.getSerializableExtra(ObdService.EXTRA_PID_LIST_UPDATED) as? ArrayList<ObdPid>

                if (pids != null && isRunning) {
                    Log.d(TAG, "Nhận được dữ liệu từ ObdService, chuẩn bị gửi đi...")
                    // Chuyển đổi List<ObdPid> thành Map<String, String> để gửi
                    val obdDataMap = pids.associate { it.name to it.value }
                    mqttManager.publishTelemetry(obdDataMap)
                }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        mqttManager = MqttManager(this)
        startForegroundService()
        registerObdReceiver()
        Log.d(TAG, "MqttService created.")
    }

    private fun registerObdReceiver() {
        val intentFilter = IntentFilter(ObdService.ACTION_OBD_DATA_UPDATE)
        ContextCompat.registerReceiver(
            this,
            obdDataReceiver,
            intentFilter,
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        Log.d(TAG, "Đã đăng ký lắng nghe dữ liệu từ ObdService.")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START_SERVICE -> {
                if (!isRunning) {
                    Log.d(TAG, "Bắt đầu MqttService...")
                    isRunning = true
                    connectAndSetupMqtt()
                }
            }
            ACTION_STOP_SERVICE -> {
                Log.d(TAG, "Dừng MqttService...")
                stopSelf() // Lệnh này sẽ kích hoạt onDestroy
            }
        }
        return START_NOT_STICKY
    }

    private fun connectAndSetupMqtt() {
        broadcastStatus("MQTT: Đang kết nối...")
        mqttManager.connect()

        mqttManager.onReadyToPublish = {
            broadcastStatus("MQTT: Đã sẵn sàng gửi dữ liệu")
            // Thiết lập callback để nhận dữ liệu loopback từ platform
            mqttManager.onTelemetryReceived = { telemetryJson ->
                // Có thể broadcast dữ liệu loopback này về cho MainActivity nếu cần
                Log.d(TAG, "Nhận được dữ liệu loopback: $telemetryJson")
            }
        }

        mqttManager.onConnectionFailure = { error ->
            broadcastStatus("MQTT: Lỗi - ${error?.message}")
            // Có thể tự động thử kết nối lại hoặc dừng service
        }
    }

    private fun startForegroundService() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                "MQTT Service Channel",
                NotificationManager.IMPORTANCE_LOW
            )
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
        val notification: Notification = NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setContentTitle("Dịch vụ MQTT")
            .setContentText("Đang kết nối và gửi dữ liệu...")
            .setSmallIcon(R.drawable.ic_launcher_foreground) // Nên dùng icon khác để phân biệt
            .build()
        startForeground(NOTIFICATION_ID, notification)
    }

    private fun broadcastStatus(message: String) {
        lastStatusMessage = message
        val intent = Intent(ACTION_MQTT_STATUS_UPDATE).apply {
            setPackage(packageName)
            putExtra(EXTRA_STATUS_MESSAGE, message)
        }
        sendBroadcast(intent)
    }

    override fun onDestroy() {
        super.onDestroy()
        isRunning = false
        broadcastStatus("Đã dừng")
        unregisterReceiver(obdDataReceiver)
        mqttManager.disconnect()
        serviceScope.cancel() // Hủy tất cả coroutine con
        Log.d(TAG, "MqttService destroyed.")
    }

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }
}