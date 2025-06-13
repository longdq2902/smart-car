package vn.vnpt.obdtoiotservice


import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.*

@SuppressLint("MissingPermission")
class MainActivity : AppCompatActivity() {

    // --- Biến giao diện ---
    private lateinit var statusTextView: TextView
    private lateinit var bluetoothSpinner: Spinner
    private lateinit var startStopButton: Button
    private lateinit var selectPidsButton: Button
    private lateinit var obdDataRecyclerView: RecyclerView
    private lateinit var obdDataAdapter: ObdDataAdapter
    private lateinit var connectMqttButton: Button
    private lateinit var sendDataButton: Button

    // --- Biến quản lý ---
    private lateinit var bluetoothManager: OBDBluetoothManager
    private lateinit var mqttManager: MqttManager

    // --- Biến trạng thái và dữ liệu ---
    private var pairedDeviceList = listOf<BluetoothDevice>()
    private var selectedDevice: BluetoothDevice? = null
    private var isServiceRunning = false
    private var latestObdDataMap = mutableMapOf<String, String>()
    private var isMqttConnected = false

    // BIẾN MỚI: Quản lý luồng đọc và gửi dữ liệu
    private var dataPollingJob: Job? = null
    private var dataSendingJob: Job? = null
    private var isSendingData = false

    private lateinit var loopbackTextView: TextView



    private val allSupportedPids = listOf(
        ObdPid("010C", "Vong_tua_may", unit = "rpm"),
        ObdPid("010D", "Toc_do_xe", unit = "km/h"),
        ObdPid("0105", "Nhiet_do_nuoc_lam_mat", unit = "°C"),
        ObdPid("0104", "Tai_dong_co", unit = "%"),
        ObdPid("010F", "Nhiet_do_khi_nap", unit = "°C"),
        ObdPid("0111", "Vi_tri_buom_ga", unit = "%"),
        ObdPid("015E", "Tieu_thu_nhien_lieu", unit = "L/h"),
        ObdPid("0131", "Quang_duong", unit = "km"),
        ObdPid("0142", "Dien_ap_Module", unit = "V")
    )
    private var selectedPids = mutableListOf<ObdPid>()

    private val PERMISSIONS_REQUEST_CODE = 2

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        bluetoothManager = OBDBluetoothManager(this)
        mqttManager = MqttManager(this)

        setupUI()
        checkAndRequestPermissions()
    }

    private fun setupUI() {
        statusTextView = findViewById(R.id.statusTextView)
        bluetoothSpinner = findViewById(R.id.bluetoothSpinner)
        startStopButton = findViewById(R.id.startStopButton)
        selectPidsButton = findViewById(R.id.selectPidsButton)
        obdDataRecyclerView = findViewById(R.id.obdDataRecyclerView)
        connectMqttButton = findViewById(R.id.connectMqttButton)
        sendDataButton = findViewById(R.id.sendDataButton)
        loopbackTextView = findViewById(R.id.loopbackTextView)


        obdDataAdapter = ObdDataAdapter(selectedPids)
        obdDataRecyclerView.layoutManager = LinearLayoutManager(this)
        obdDataRecyclerView.adapter = obdDataAdapter

        startStopButton.setOnClickListener {
            if (isServiceRunning) {
                stopDataPolling()
            } else {
                startDataPolling()
            }
        }

        selectPidsButton.setOnClickListener { showPidSelectionDialog() }

        connectMqttButton.setOnClickListener {
            statusTextView.text = "Đang kết nối MQTT..."
            connectMqttButton.isEnabled = false
            mqttManager.connect()

            mqttManager.onReadyToPublish = {
                runOnUiThread {
                    statusTextView.text = "MQTT đã sẵn sàng."
                    isMqttConnected = true
                    sendDataButton.isEnabled = true
                    connectMqttButton.text = "MQTT Đã kết nối"
                    Toast.makeText(this, "Kết nối và khởi tạo MQTT thành công!", Toast.LENGTH_SHORT).show()
                }
            }


            // Thêm xử lý khi kết nối thất bại
            mqttManager.onConnectionFailure = { error ->
                runOnUiThread {
                    statusTextView.text = "Lỗi MQTT: ${error?.message}"
                    connectMqttButton.isEnabled = true
                }
            }

            // CÀI ĐẶT CALLBACK MỚI
            mqttManager.onTelemetryReceived = { telemetryJson ->
                runOnUiThread {
                    // Cập nhật TextView khi nhận được dữ liệu
                    loopbackTextView.text = telemetryJson
                }
            }
        }

        // --- CẬP NHẬT LOGIC NÚT GỬI DỮ LIỆU ---
        sendDataButton.setOnClickListener {
            if (isSendingData) {
                stopDataSending()
            } else {
                startDataSending()
            }
        }
    }

    // --- HÀM MỚI: Bắt đầu gửi dữ liệu theo chu kỳ ---
    private fun startDataSending() {
        if (!isServiceRunning) {
            Toast.makeText(this, "Vui lòng 'Bắt đầu đọc' dữ liệu từ xe trước", Toast.LENGTH_SHORT).show()
            return
        }
        if (!isMqttConnected) {
            Toast.makeText(this, "MQTT chưa kết nối.", Toast.LENGTH_SHORT).show()
            return
        }

        dataSendingJob?.cancel()
        dataSendingJob = CoroutineScope(Dispatchers.IO).launch {
            Log.d("DataSending", "Bắt đầu chu kỳ gửi dữ liệu...")
            withContext(Dispatchers.Main) {
                isSendingData = true
                sendDataButton.text = "Dừng gửi"
            }
            while (isActive) {
                if (latestObdDataMap.isNotEmpty()) {
                    Log.d("DataSending", "Đang gửi dữ liệu: $latestObdDataMap")
                    mqttManager.publishTelemetry(latestObdDataMap)
                } else {
                    Log.d("DataSending", "Bỏ qua vì chưa có dữ liệu.")
                }
                delay(10000) // Chờ 10 giây
            }
        }
    }

    // --- HÀM MỚI: Dừng gửi dữ liệu theo chu kỳ ---
    private fun stopDataSending() {
        dataSendingJob?.cancel()
        isSendingData = false
        sendDataButton.text = "Bắt đầu gửi"
        Log.d("DataSending", "Đã dừng chu kỳ gửi dữ liệu.")
    }

    private fun startDataPolling() {
        if (selectedDevice == null) {
            Toast.makeText(this, "Vui lòng chọn một thiết bị Bluetooth", Toast.LENGTH_SHORT).show()
            return
        }
        if (selectedPids.isEmpty()) {
            Toast.makeText(this, "Vui lòng chọn ít nhất một thông số", Toast.LENGTH_SHORT).show()
            return
        }

        statusTextView.text = "Đang kết nối Bluetooth..."
        dataPollingJob?.cancel()
        dataPollingJob = CoroutineScope(Dispatchers.IO).launch {
            if (bluetoothManager.connect(selectedDevice!!)) {
                withContext(Dispatchers.Main) {
                    statusTextView.text = "Bluetooth đã kết nối. Bắt đầu lấy dữ liệu."
                    isServiceRunning = true
                    startStopButton.text = "Dừng đọc"
                }

                bluetoothManager.sendCommand("ATZ"); bluetoothManager.readResponse()
                bluetoothManager.sendCommand("ATE0"); bluetoothManager.readResponse()

                while (isActive) {
                    val currentPidStates = selectedPids.map { it.copy() }
                    val tempDataMap = mutableMapOf<String, String>()

                    currentPidStates.forEach { pid ->
                        bluetoothManager.sendCommand(pid.command)
                        val response = bluetoothManager.readResponse()
                        pid.value = parseObdResponse(pid.command, response)
                        tempDataMap[pid.name] = pid.value
                    }
                    latestObdDataMap = tempDataMap

                    withContext(Dispatchers.Main) {
                        obdDataAdapter.updateData(currentPidStates)
                    }
                    delay(5000)
                }
            } else {
                withContext(Dispatchers.Main) {
                    statusTextView.text = "Kết nối Bluetooth thất bại!"
                }
            }
        }
    }

    private fun stopDataPolling() {
        dataPollingJob?.cancel()
        bluetoothManager.disconnect()
        isServiceRunning = false
        statusTextView.text = "Đã dừng"
        startStopButton.text = "Bắt đầu đọc"
        latestObdDataMap.clear()
        obdDataAdapter.updateData(emptyList())

        // Tích hợp: Dừng gửi dữ liệu khi dừng đọc
        if (isSendingData) {
            stopDataSending()
        }
    }

    // ... các hàm còn lại giữ nguyên (showPidSelectionDialog, parseObdResponse, checkAndRequestPermissions, ...)
    private fun showPidSelectionDialog() {
        val pidNames = allSupportedPids.map { it.name.replace("_", " ") }.toTypedArray()
        val checkedItems = allSupportedPids.map { selectedPids.any { s -> s.command == it.command } }.toBooleanArray()

        AlertDialog.Builder(this)
            .setTitle("Chọn thông số để hiển thị")
            .setMultiChoiceItems(pidNames, checkedItems) { _, which, isChecked ->
                checkedItems[which] = isChecked
            }
            .setPositiveButton("OK") { _, _ ->
                val newSelection = mutableListOf<ObdPid>()
                for (i in pidNames.indices) {
                    if (checkedItems[i]) {
                        newSelection.add(allSupportedPids[i])
                    }
                }
                selectedPids = newSelection
                obdDataAdapter.updateData(selectedPids)
                Toast.makeText(this, "Đã chọn ${selectedPids.size} thông số", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Hủy", null)
            .show()
    }

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
        } catch (e: Exception) {
            "Lỗi Parse"
        }
    }

    private fun checkAndRequestPermissions() {
        if (!bluetoothManager.isBluetoothSupported()) {
            Toast.makeText(this, "Thiết bị này không hỗ trợ Bluetooth", Toast.LENGTH_LONG).show()
            return
        }

        val permissionsToRequest = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED) {
                permissionsToRequest.add(Manifest.permission.BLUETOOTH_SCAN)
            }
            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
                permissionsToRequest.add(Manifest.permission.BLUETOOTH_CONNECT)
            }
        } else {
            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
                permissionsToRequest.add(Manifest.permission.ACCESS_FINE_LOCATION)
            }
        }

        if (permissionsToRequest.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, permissionsToRequest.toTypedArray(), PERMISSIONS_REQUEST_CODE)
        } else {
            listPairedDevices()
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == PERMISSIONS_REQUEST_CODE) {
            if (grantResults.isNotEmpty() && grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
                listPairedDevices()
            } else {
                Toast.makeText(this, "Bạn cần cấp quyền Bluetooth để ứng dụng hoạt động", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun listPairedDevices() {
        pairedDeviceList = bluetoothManager.getPairedDevices()
        val deviceNameList = pairedDeviceList.map { it.name + "\n" + it.address }

        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, deviceNameList)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        bluetoothSpinner.adapter = adapter

        if (deviceNameList.isEmpty()) {
            Toast.makeText(this, "Không tìm thấy thiết bị nào đã ghép nối", Toast.LENGTH_LONG).show()
        }

        bluetoothSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                if (pairedDeviceList.isNotEmpty()) {
                    selectedDevice = pairedDeviceList[position]
                }
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {
                selectedDevice = null
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        stopDataPolling()
        if (isMqttConnected) {
            mqttManager.disconnect()
        }
    }
}