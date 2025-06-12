package vn.vnpt.obdtoiotservice


import android.content.Context
import android.util.Log
import kotlinx.coroutines.*
import org.eclipse.paho.android.service.MqttAndroidClient
import org.eclipse.paho.client.mqttv3.*
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

class MqttManager(private val context: Context) {

    companion object {
        private const val TAG = "MqttManager"
        // ================== TODO: ĐIỀN THÔNG TIN THẬT CỦA BẠN VÀO ĐÂY ==================
        private const val MQTT_BROKER_URL = "tcp://your_server_ip:1883" // << THAY BẰNG IP/DOMAIN CỦA SERVER
        private const val DEVICE_ID = "MyAndroidOBD-001"                // << THAY BẰNG DEVICE ID CỦA BẠN
        private const val ACCESS_TOKEN = "YourSuperSecretAccessToken"   // << THAY BẰNG ACCESS TOKEN ĐƯỢC CẤP
        private const val APP_ID = "vn.vnpt.obd.app"                    // << THAY BẰNG APP ID CỦA BẠN
        // =================================================================================

        private const val CLIENT_ID_PREFIX = "mqtt://"
        private const val CSE_ID = "in-cse"
        private const val CSE_NAME = "in-name"
    }

    private lateinit var mqttClient: MqttAndroidClient
    private val sessionManager = SessionManager(context)
    private val coroutineScope = CoroutineScope(Dispatchers.IO)
    private val pendingRequests = ConcurrentHashMap<String, CompletableDeferred<JSONObject>>()

    var onReadyToPublish: (() -> Unit)? = null

    fun connect() {
        val fullClientId = "$CLIENT_ID_PREFIX$DEVICE_ID"
        mqttClient = MqttAndroidClient(context, MQTT_BROKER_URL, fullClientId)
        val options = MqttConnectOptions().apply {
            userName = DEVICE_ID
            password = ACCESS_TOKEN.toCharArray()
            isAutomaticReconnect = true
            isCleanSession = true
        }

        try {
            mqttClient.connect(options, null, object : IMqttActionListener {
                override fun onSuccess(asyncActionToken: IMqttToken?) {
                    Log.d(TAG, "Kết nối MQTT thành công!")
                    setCallback()
                    subscribeToResponseTopic(DEVICE_ID)
                    initialSetupFlow() // Bắt đầu flow khởi tạo sau khi kết nối
                }

                override fun onFailure(asyncActionToken: IMqttToken?, exception: Throwable?) {
                    Log.e(TAG, "Kết nối MQTT thất bại: ${exception?.message}")
                }
            })
        } catch (e: MqttException) {
            e.printStackTrace()
        }
    }

    private fun setCallback() {
        mqttClient.setCallback(object : MqttCallbackExtended {
            override fun connectComplete(reconnect: Boolean, serverURI: String?) {
                if (reconnect) subscribeToResponseTopic(DEVICE_ID)
            }

            override fun connectionLost(cause: Throwable?) {
                Log.e(TAG, "Kết nối MQTT bị mất: ${cause?.message}")
            }

            override fun messageArrived(topic: String?, message: MqttMessage?) {
                Log.d(TAG, "Nhận được tin nhắn từ topic: $topic")
                val responseJson = JSONObject(message.toString())
                val rqi = responseJson.optString("rqi", "")
                pendingRequests[rqi]?.complete(responseJson)
                pendingRequests.remove(rqi)
            }

            override fun deliveryComplete(token: IMqttDeliveryToken?) {}
        })
    }

    private fun subscribeToResponseTopic(originator: String) {
        val responseTopic = "/oneM2M/resp/$originator/#"
        try {
            mqttClient.subscribe(responseTopic, 1)
            Log.d(TAG, "Đã đăng ký topic: $responseTopic")
        } catch (e: MqttException) {
            e.printStackTrace()
        }
    }

    private fun initialSetupFlow() = coroutineScope.launch {
        if (sessionManager.isSetupComplete()) {
            Log.d(TAG, "Thiết bị đã được khởi tạo. Sẵn sàng gửi dữ liệu.")
            withContext(Dispatchers.Main) { onReadyToPublish?.invoke() }
            return@launch
        }

        Log.d(TAG, "Bắt đầu flow khởi tạo tài nguyên...")

        // Bước 1: Create AE
        val aeResponse = request(createAePayload(), "/$CSE_ID/$CSE_NAME")
        if (aeResponse == null || aeResponse.optInt("rsc") != 2001) {
            Log.e(TAG, "Tạo AE thất bại! Response: $aeResponse")
            return@launch
        }
        val aeId = aeResponse.getJSONObject("pc").getJSONObject("m2m:ae").getString("aei")
        sessionManager.saveAeId(aeId)
        Log.d(TAG, "Tạo AE thành công! AE-ID: $aeId")

        // Bước 2: Create Containers
        val containers = listOf("cnt_telemetry", "cnt_command", "cnt_config_platform", "cnt_device_status")
        for (name in containers) {
            val containerResponse = request(createContainerPayload(name), "/$CSE_ID/$CSE_NAME/$aeId")
            if (containerResponse == null || containerResponse.optInt("rsc") != 2001) {
                Log.e(TAG, "Tạo container '$name' thất bại! Dừng flow.")
                return@launch
            }
            Log.d(TAG, "Tạo container '$name' thành công.")
            delay(200) // Thêm độ trễ nhỏ giữa các request
        }

        // Bước 3: Create Subscriptions
        val subs = listOf("cnt_command", "cnt_config_platform")
        for (containerName in subs) {
            val subResponse = request(createSubscriptionPayload(aeId, containerName), "/$CSE_ID/$CSE_NAME/$aeId/$containerName")
            if (subResponse == null || subResponse.optInt("rsc") != 2001) {
                Log.e(TAG, "Tạo subscription cho '$containerName' thất bại! Dừng flow.")
                return@launch
            }
            Log.d(TAG, "Tạo subscription cho '$containerName' thành công.")
            delay(200)
        }

        Log.d(TAG, "Khởi tạo tài nguyên trên Platform thành công!")
        sessionManager.setSetupComplete(true)
        withContext(Dispatchers.Main) { onReadyToPublish?.invoke() }
    }

    fun publishTelemetry(obdDataMap: Map<String, String>) {
        if (!mqttClient.isConnected || !sessionManager.isSetupComplete()) {
            Log.w(TAG, "Chưa sẵn sàng để gửi telemetry.")
            return
        }

        val aeId = sessionManager.getAeId() ?: return
        val telemetryJsonString = JSONObject(obdDataMap).toString()
        val payload = createContentInstancePayload("cnt_telemetry", telemetryJsonString)
        val topic = "/oneM2M/req/$aeId/$CSE_ID/json"

        publish(topic, payload)
    }

    private suspend fun request(payload: String, toSuffix: String): JSONObject? {
        val rqi = "req_${System.currentTimeMillis()}"
        val fullPayload = JSONObject(payload).put("rqi", rqi).toString()
        val topic = "/oneM2M/req/${sessionManager.getAeId() ?: DEVICE_ID}$toSuffix/json"
        val deferred = CompletableDeferred<JSONObject>()
        pendingRequests[rqi] = deferred

        publish(topic, fullPayload)
        Log.d(TAG, "Đã gửi request đến $topic")

        return try {
            withTimeout(10000L) { // 10 giây timeout
                deferred.await()
            }
        } catch (e: TimeoutCancellationException) {
            Log.e(TAG, "Request $rqi đã hết thời gian chờ.")
            pendingRequests.remove(rqi)
            null
        }
    }

    private fun publish(topic: String, payload: String) {
        try {
            if(mqttClient.isConnected) {
                val message = MqttMessage()
                message.payload = payload.toByteArray()
                message.qos = 1
                mqttClient.publish(topic, message)
            } else {
                Log.e(TAG, "Publish thất bại, MQTT không kết nối.")
            }
        } catch (e: MqttException) {
            e.printStackTrace()
        }
    }

    // --- CÁC HÀM TẠO PAYLOAD THEO ĐẶC TẢ ---
    private fun createAePayload(): String {
        val aeContent = JSONObject().apply {
            put("api", APP_ID)
            put("rr", true)
            put("poa", JSONArray().put("$CLIENT_ID_PREFIX$DEVICE_ID"))
            put("rn", DEVICE_ID)
            put("srv", JSONArray().put("3"))
        }
        val payload = createBasePayload(DEVICE_ID, 1, 2)
        payload.put("pc", JSONObject().put("m2m:ae", aeContent))
        return payload.toString()
    }

    private fun createContainerPayload(resourceName: String): String {
        val cntContent = JSONObject().apply { put("rn", resourceName) }
        val payload = createBasePayload(sessionManager.getAeId()!!, 1, 3)
        payload.put("pc", JSONObject().put("m2m:cnt", cntContent))
        return payload.toString()
    }

    private fun createSubscriptionPayload(aeId: String, containerName: String): String {
        val subContent = JSONObject().apply {
            put("rn", "sub_to_$containerName")
            put("nu", JSONArray().put("/$CSE_ID/$aeId"))
            put("nct", 1)
        }
        val payload = createBasePayload(aeId, 1, 23)
        payload.put("pc", JSONObject().put("m2m:sub", subContent))
        return payload.toString()
    }

    private fun createContentInstancePayload(resourceName: String, content: String): String {
        val cinContent = JSONObject().apply {
            put("rn", resourceName)
            put("cnf", "text/plain:0") // Chú ý: tài liệu ghi plains, nhưng chuẩn là plain
            put("con", content)
        }
        val payload = createBasePayload(sessionManager.getAeId()!!, 1, 4)
        payload.put("pc", JSONObject().put("m2m:cin", cinContent))
        return payload.toString()
    }

    private fun createBasePayload(originator: String, op: Int, ty: Int): JSONObject {
        return JSONObject().apply {
            put("fr", originator)
            put("op", op)
            put("ty", ty)
            put("tkns", JSONArray().put(ACCESS_TOKEN))
        }
    }

    fun disconnect() {
        try {
            // Hủy tất cả các coroutine đang chạy trong scope này (quan trọng!)
            // Việc này sẽ dừng các vòng lặp hoặc các flow đang chờ
            coroutineScope.cancel()

            // Kiểm tra xem client đã được khởi tạo và đang kết nối không
            if (this::mqttClient.isInitialized && mqttClient.isConnected) {
                // Ngắt kết nối khỏi broker
                mqttClient.disconnect()
                Log.d(TAG, "Đã ngắt kết nối MQTT.")
            }
        } catch (e: MqttException) {
            e.printStackTrace()
        }
    }
}