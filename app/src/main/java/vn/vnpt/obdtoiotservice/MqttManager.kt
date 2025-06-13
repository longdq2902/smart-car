package vn.vnpt.obdtoiotservice

import android.content.Context
import android.util.Log
import kotlinx.coroutines.*
import org.eclipse.paho.client.mqttv3.*
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.log

class MqttManager(private val context: Context) {

    companion object {
        private const val TAG = "MqttManager"
        // ================== TODO: ĐIỀN THÔNG TIN THẬT CỦA BẠN VÀO ĐÂY ==================
        private const val MQTT_BROKER_URL = "tcp://oneiot.com.vn:2007" // << THAY BẰNG IP/DOMAIN CỦA SERVER
        private const val DEVICE_ID = "S95977e4f-8a9d-4cbf-a550-ad7895c03b2b"                // << THAY BẰNG DEVICE ID CỦA BẠN
        private const val ACCESS_TOKEN = "eyJhbGciOiJIUzI1NiJ9.eyJqdGkiOiJ0ay03ZTA2MGRmYi1lODZjLTRiNTctYTUwZi0yYTEzYjJiODU3MmIiLCJleHAiOjE3NzU2NDY0Nzd9.T-dW0YjNsAWydLA5KIQlxEO2yh5wSHopOxyCJQ1taN4"   // << THAY BẰNG ACCESS TOKEN ĐƯỢC CẤP
        private const val APP_ID = "S95977e4f-8a9d-4cbf-a550-ad7895c03b2b"                    // << THAY BẰNG APP ID CỦA BẠN
        // =================================================================================

        private const val CLIENT_ID_PREFIX = "mqtt://"
        private const val CSE_ID = "in-cse"
        private const val CSE_NAME = "in-name"
    }

    private lateinit var mqttClient: MqttClient
    private val sessionManager = SessionManager(context)
    private val coroutineScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val pendingRequests = ConcurrentHashMap<String, CompletableDeferred<JSONObject>>()
    // Thêm dòng này vào đầu lớp MqttManager
    var onTelemetryReceived: ((String) -> Unit)? = null

    var onReadyToPublish: (() -> Unit)? = null
    var onConnectionFailure: ((Throwable?) -> Unit)? = null


    fun connect() {
        coroutineScope.launch {
            try {
                val fullClientId = DEVICE_ID // Chỉ dùng DEVICE_ID làm Client ID, bỏ tiền tố
                // Sử dụng MemoryPersistence để lưu trữ trạng thái trong bộ nhớ
                Log.d(TAG, fullClientId)
                val persistence = MemoryPersistence()
                // Sử dụng MqttClient thay cho MqttAndroidClient
                mqttClient = MqttClient(MQTT_BROKER_URL, fullClientId, persistence)

                val options = MqttConnectOptions().apply {
                    userName = DEVICE_ID
                    password = ACCESS_TOKEN.toCharArray()
                    isAutomaticReconnect = true
                    isCleanSession = true
                    connectionTimeout = 35
                }

                // Đặt callback trước khi kết nối
                setCallback()

                Log.d(TAG, "Đang kết nối đến MQTT Broker...")
                mqttClient.connect(options)
                Log.d(TAG, "Kết nối MQTT thành công!")

                subscribeToResponseTopic(DEVICE_ID)
                initialSetupFlow() // Bắt đầu flow khởi tạo sau khi kết nối

            } catch (e: Exception) {
                Log.e(TAG, "Kết nối MQTT thất bại: ${e.message}", e)
                withContext(Dispatchers.Main) {
                    onConnectionFailure?.invoke(e)
                }
            }
        }
    }

    private fun setCallback() {
        mqttClient.setCallback(object : MqttCallbackExtended {
            override fun connectComplete(reconnect: Boolean, serverURI: String?) {
                Log.d(TAG, "Connect complete. Reconnect: $reconnect")
                // Đăng ký lại topic khi kết nối lại
                if (reconnect) {
                    coroutineScope.launch {
                        subscribeToResponseTopic(DEVICE_ID)
                    }
                }
            }

            override fun connectionLost(cause: Throwable?) {
                Log.e(TAG, "Kết nối MQTT bị mất: ${cause?.message}")
            }

            override fun messageArrived(topic: String?, message: MqttMessage?) {
                val responseString = message?.toString()
                Log.d(TAG, "Nhận được tin nhắn từ topic: $topic, payload: $responseString")
                if (responseString.isNullOrEmpty()) return

                try {
                    val responseJson = JSONObject(responseString)

                    // KIỂM TRA XEM ĐÂY LÀ RESPONSE HAY LÀ NOTIFY
                    if (responseJson.has("rqi")) {
                        // Đây là một response cho request, xử lý như cũ
                        val rqi = responseJson.optString("rqi", "")
                        pendingRequests[rqi]?.complete(responseJson)
                        pendingRequests.remove(rqi)
                    } else if (responseJson.has("pc") && responseJson.getJSONObject("pc").has("m2m:sgn")) {
                        // Đây là một tin nhắn Notify (thông báo)
                        Log.d(TAG, "Phát hiện tin nhắn Notify!")
                        val sgn = responseJson.getJSONObject("pc").getJSONObject("m2m:sgn")
                        if (sgn.has("nev") && sgn.getJSONObject("nev").has("rep") && sgn.getJSONObject("nev").getJSONObject("rep").has("m2m:cin")) {
                            val cin = sgn.getJSONObject("nev").getJSONObject("rep").getJSONObject("m2m:cin")
                            if (cin.has("con")) {
                                val telemetryData = cin.getString("con")
                                // Gọi callback để gửi dữ liệu về MainActivity
                                onTelemetryReceived?.invoke(telemetryData)
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Lỗi parse JSON từ response", e)
                }
            }

            override fun deliveryComplete(token: IMqttDeliveryToken?) {}
        })
    }

    private suspend fun subscribeToResponseTopic(originator: String) {
        // Lấy CSE_ID đã được định nghĩa trong lớp MqttManager
        val cseId = CSE_ID
        // Tạo topic chính xác như trong sơ đồ luồng Registration
        val responseTopic = "/oneM2M/resp/$originator/$cseId/json"
        try {
            withContext(Dispatchers.IO) {
                mqttClient.subscribe(responseTopic, 1)
            }
            Log.d(TAG, "Đã đăng ký topic: $responseTopic")
        } catch (e: MqttException) {
            Log.e(TAG, "Lỗi đăng ký topic: ${e.message}", e)
        }
    }


    private fun createRetrieveAePayload(): String {
        val payload = JSONObject()
        // op: 2 là thao tác RETRIEVE (Truy vấn)
        payload.put("op", 2)
        // ty: 2 là loại tài nguyên AE
        payload.put("ty", 2)
        // Yêu cầu retrieve không cần "pc" (payload content)
        return payload.toString()
    }
    // Thêm 2 hàm mới này vào trong lớp MqttManager

    private fun createRetrievePayload(resourceType: Int): String {
        val payload = JSONObject()
        payload.put("op", 2) // op: 2 là thao tác RETRIEVE (Truy vấn)
        payload.put("ty", resourceType) // ty: loại tài nguyên cần truy vấn
        return payload.toString()
    }

    private fun verifyResources(aeId: String) = coroutineScope.launch {
        delay(1000) // Chờ 1 giây để đảm bảo mọi thứ đã ổn định
        Log.d(TAG, "--- BẮT ĐẦU KIỂM TRA TÀI NGUYÊN ĐÃ TẠO ---")

        // 1. Kiểm tra lại thông tin AE
        Log.d(TAG, "Đang truy vấn thông tin AE...")
        val aeInfo = request(createRetrievePayload(2), "/$CSE_ID/$CSE_NAME/$aeId")
        Log.i(TAG, "==> Thông tin AE: $aeInfo")

        // 2. Kiểm tra lại thông tin các container
        val containers = listOf("cnt_telemetry", "cnt_command", "cnt_config_platform", "cnt_device_status")
        for (name in containers) {
            Log.d(TAG, "Đang truy vấn container '$name'...")
            val containerInfo = request(createRetrievePayload(3), "/$CSE_ID/$CSE_NAME/$aeId/$name")
            Log.i(TAG, "==> Thông tin container '$name': $containerInfo")
            delay(300) // Tránh gửi quá nhanh
        }
        Log.d(TAG, "--- KẾT THÚC KIỂM TRA TÀI NGUYÊN ---")
    }

    fun initialSetupFlow() = coroutineScope.launch {
        Log.d(TAG, "Bắt đầu kiểm tra và khởi tạo tài nguyên...")

        try {
            var aeId = sessionManager.getAeId()

            // Bước 1: Kiểm tra và Tạo/Truy vấn AE nếu cần
            if (aeId == null) {
                Log.d(TAG, "AE-ID chưa tồn tại, bắt đầu tạo mới...")
                val createAeResponse = request(createAePayload(), "/$CSE_ID/$CSE_NAME")

                if (createAeResponse != null && createAeResponse.optInt("rsc") == 2001) {
                    // TẠO MỚI THÀNH CÔNG
                    aeId = createAeResponse.getJSONObject("pc").getJSONObject("m2m:ae").getString("aei")
                    Log.d(TAG, "Tạo AE thành công! AE-ID: $aeId")

                } else if (createAeResponse != null && createAeResponse.optInt("rsc") == 4105) {
                    // AE ĐÃ TỒN TẠI -> GỬI YÊU CẦU TRUY VẤN
                    Log.d(TAG, "AE đã tồn tại (CONFLICT/4105). Bắt đầu truy vấn thông tin AE...")
                    // Đường dẫn đến AE cần truy vấn chính là /<CSE_ID>/<CSE_NAME>/<DEVICE_ID>
                    val retrieveAeResponse = request(createRetrieveAePayload(), "/$CSE_ID/$CSE_NAME/$DEVICE_ID")

                    if (retrieveAeResponse != null && retrieveAeResponse.optInt("rsc") == 2000) {
                        // TRUY VẤN THÀNH CÔNG
                        aeId = retrieveAeResponse.getJSONObject("pc").getJSONObject("m2m:ae").getString("aei")
                        Log.d(TAG, "Truy vấn AE thành công! AE-ID: $aeId")
                    } else {
                        // Truy vấn cũng thất bại, đây là lỗi nghiêm trọng
                        Log.e(TAG, "Truy vấn AE thất bại! Dừng flow. Response: $retrieveAeResponse")
                        throw IllegalStateException("Truy vấn AE đã tồn tại thất bại.")
                    }
                } else {
                    // Các trường hợp lỗi khác khi tạo AE
                    Log.e(TAG, "Tạo AE thất bại! Dừng flow. Response: $createAeResponse")
                    throw IllegalStateException("Tạo AE thất bại, không thể tiếp tục.")
                }

                // Lưu AE-ID vào session sau khi đã có
                sessionManager.saveAeId(aeId)

            } else {
                Log.d(TAG, "Đã có AE-ID từ session: $aeId. Bỏ qua bước tạo/truy vấn AE.")
            }

            // Nếu qua được đây, aeId chắc chắn không null
            val finalAeId = aeId!!
            Log.d(TAG, "Sử dụng AE-ID: $finalAeId để tạo các tài nguyên con.")

            // Bước 2: Create Containers (Bỏ qua nếu lỗi)
            val containers = listOf("cnt_telemetry", "cnt_command", "cnt_config_platform", "cnt_device_status")
            Log.d(TAG, "Bắt đầu tạo các container...")
            for (name in containers) {
                val containerResponse = request(createContainerPayload(name), "/$CSE_ID/$CSE_NAME/$finalAeId")
                if (containerResponse == null || containerResponse.optInt("rsc") != 2001) {
                    Log.e(TAG, "Tạo container '$name' thất bại! Bỏ qua và tiếp tục. Response: $containerResponse")
                } else {
                    Log.d(TAG, "Tạo container '$name' thành công.")
                }
                delay(1000)
            }

            // Bước 3: Create Subscriptions (Bỏ qua nếu lỗi)

//            val subs = listOf("cnt_command", "cnt_config_platform")
            val subs = listOf("cnt_command", "cnt_config_platform", "cnt_telemetry")
            Log.d(TAG, "Bắt đầu tạo các subscription...")
            for (containerName in subs) {
                val subResponse = request(createSubscriptionPayload(finalAeId, containerName), "/$CSE_ID/$CSE_NAME/$finalAeId/$containerName")
                if (subResponse == null || subResponse.optInt("rsc") != 2001) {
                    Log.e(TAG, "Tạo subscription cho '$containerName' thất bại! Bỏ qua và tiếp tục. Response: $subResponse")
                } else {
                    Log.d(TAG, "Tạo subscription cho '$containerName' thành công.")
                }
                delay(200)
            }

            if (!sessionManager.isSetupComplete()) {
                sessionManager.setSetupComplete(true)
            }

            Log.d(TAG, "Hoàn tất luồng khởi tạo tài nguyên!")

            // =================== THÊM DÒNG NÀY ===================
            // Gọi hàm kiểm tra lại các tài nguyên vừa tạo
            verifyResources(finalAeId)
            // ======================================================


            withContext(Dispatchers.Main) { onReadyToPublish?.invoke() }

        } catch (e: Exception) {
            Log.e(TAG, "Lỗi nghiêm trọng trong quá trình khởi tạo tài nguyên", e)
            withContext(Dispatchers.Main) {
                onConnectionFailure?.invoke(e)
            }
        }
    }


    fun publishTelemetry(obdDataMap: Map<String, String>) {
        if (!::mqttClient.isInitialized || !mqttClient.isConnected || !sessionManager.isSetupComplete()) {
            Log.w(TAG, "Chưa sẵn sàng để gửi telemetry.")
            return
        }

        val aeId = sessionManager.getAeId() ?: return
        val telemetryJsonString = JSONObject(obdDataMap).toString()

        // Sử dụng lại coroutine để gọi hàm request bất đồng bộ
        coroutineScope.launch {
            // Tạo payload cho việc tạo contentInstance
            val cinPayload = createContentInstancePayload(telemetryJsonString)

            // Xác định đường dẫn đến container telemetry
            val toSuffix = "/$CSE_ID/$CSE_NAME/$aeId/cnt_telemetry"

            // =================== LOG BỔ SUNG ===================
            Log.d(TAG, "Chuẩn bị gửi Telemetry đến container tại: $toSuffix")
            Log.d(TAG, "Payload của contentInstance: $cinPayload")
            // ====================================================

            // Gửi yêu cầu và chờ phản hồi
            val response = request(cinPayload, toSuffix)

            // Ghi log kết quả
            if (response != null && response.optInt("rsc") == 2001) {
                Log.i(TAG, "==> Gửi Telemetry THÀNH CÔNG (rsc: 2001)")
            } else {
                Log.e(TAG, "==> Gửi Telemetry THẤT BẠI. Response: $response")
            }
        }
    }

    private suspend fun request(payload: String, toSuffix: String): JSONObject? {
        val rqi = "req_${System.currentTimeMillis()}"
        val originator = sessionManager.getAeId() ?: DEVICE_ID

        val fullPayloadObject = JSONObject(payload)
        fullPayloadObject.put("rqi", rqi)
        fullPayloadObject.put("fr", originator)
        fullPayloadObject.put("to", toSuffix)
        fullPayloadObject.put("tkns", JSONArray().put(ACCESS_TOKEN))

        // =================== SỬA LẠI DÒNG NÀY ===================
        // Topic MQTT chỉ cần đến CSE-ID, không cần đường dẫn đầy đủ.
        // Đường dẫn đầy đủ đã có trong trường "to" của payload.
        val targetCse = CSE_ID // Lấy hằng số "in-cse"
        val topic = "/oneM2M/req/$originator/$targetCse/json"
        // =========================================================

        val deferred = CompletableDeferred<JSONObject>()
        pendingRequests[rqi] = deferred

        publish(topic, fullPayloadObject.toString())
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
        coroutineScope.launch {
            try {
                if(::mqttClient.isInitialized && mqttClient.isConnected) {
                    val message = MqttMessage(payload.toByteArray())
                    message.qos = 1
                    withContext(Dispatchers.IO) {
                        mqttClient.publish(topic, message)
                    }
                    Log.d(TAG, "Đã publish tới topic $topic")
                } else {
                    Log.e(TAG, "Publish thất bại, MQTT không kết nối.")
                }
            } catch (e: MqttException) {
                Log.e(TAG, "Lỗi khi publish: ${e.message}", e)
            }
        }
    }

    // --- CÁC HÀM TẠO PAYLOAD THEO ĐẶC TẢ ---
    private fun createAePayload(): String {
        val aeContent = JSONObject().apply {
            put("api", APP_ID)
            put("rr", true)
            put("poa", JSONArray().put("$CLIENT_ID_PREFIX$DEVICE_ID"))
            put("rn", DEVICE_ID) // resourceName
            put("srv", JSONArray().put("3")) // supportedReleaseVersions
        }
        val payload = JSONObject()
        payload.put("op", 1) // operation Create
        payload.put("ty", 2) // type AE
        payload.put("pc", JSONObject().put("m2m:ae", aeContent))
        return payload.toString()
    }

    private fun createContainerPayload(resourceName: String): String {
        val cntContent = JSONObject().apply { put("rn", resourceName) }
        val payload = JSONObject()
        payload.put("op", 1) // operation Create
        payload.put("ty", 3) // type Container
        payload.put("pc", JSONObject().put("m2m:cnt", cntContent))
        return payload.toString()
    }

    private fun createSubscriptionPayload(aeId: String, containerName: String): String {
        val subContent = JSONObject().apply {
            put("rn", "sub_to_$containerName")
            put("nu", JSONArray().put("/$CSE_ID/$aeId")) // notificationURI
            put("nct", 1) // notificationContentType = MODIFIED_ATTRIBUTES
        }
        val payload = JSONObject()
        payload.put("op", 1) // operation Create
        payload.put("ty", 23) // type Subscription
        payload.put("pc", JSONObject().put("m2m:sub", subContent))
        return payload.toString()
    }

    private fun createContentInstancePayload(content: String): String {
        val cinContent = JSONObject().apply {
            put("cnf", "text/plain:0")
            put("con", content)
        }
        val payload = JSONObject()
        payload.put("op", 1) // operation Create
        payload.put("ty", 4) // type ContentInstance
        payload.put("pc", JSONObject().put("m2m:cin", cinContent))
        return payload.toString()
    }


    fun disconnect() {
        coroutineScope.launch {
            try {
                if (::mqttClient.isInitialized && mqttClient.isConnected) {
                    withContext(Dispatchers.IO) {
                        mqttClient.disconnect()
                    }
                    Log.d(TAG, "Đã ngắt kết nối MQTT.")
                }
            } catch (e: MqttException) {
                Log.e(TAG, "Lỗi khi ngắt kết nối: ${e.message}", e)
            } finally {
                coroutineScope.cancel() // Hủy tất cả các coroutine con
            }
        }
    }
}