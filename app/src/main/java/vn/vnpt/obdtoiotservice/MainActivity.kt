package vn.vnpt.obdtoiotservice

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.content.*
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
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import java.io.Serializable

@SuppressLint("MissingPermission")
class MainActivity : AppCompatActivity() {

    private val TAG = "MainActivity"

    // --- Biến giao diện ---
    private lateinit var statusTextView: TextView
    private lateinit var mqttStatusTextView: TextView // TextView mới cho trạng thái MQTT
    private lateinit var bluetoothSpinner: Spinner
    private lateinit var startStopObdButton: Button
    private lateinit var selectPidsButton: Button
    private lateinit var obdDataRecyclerView: RecyclerView
    private lateinit var obdDataAdapter: ObdDataAdapter
    private lateinit var startStopMqttButton: Button // Đổi tên để rõ ràng hơn
//    private lateinit var sendDataButton: Button // Nút này sẽ bị vô hiệu hóa
    private lateinit var loopbackTextView: TextView

    // --- Biến quản lý ---
    // MqttManager không còn được dùng trực tiếp ở đây nữa
    // private lateinit var mqttManager: MqttManager

    // --- Biến trạng thái và dữ liệu ---
    private var pairedDeviceList = listOf<BluetoothDevice>()
    private var selectedDevice: BluetoothDevice? = null

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

    // BroadcastReceiver giờ sẽ lắng nghe cả 2 service
    private val serviceUpdateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            Log.d(TAG, "Broadcast đã được nhận với action: ${intent?.action}")

            when (intent?.action) {
                // Các case cho ObdService
                ObdService.ACTION_OBD_STATUS_UPDATE -> {
                    val message = intent.getStringExtra(ObdService.EXTRA_STATUS_MESSAGE)
                    statusTextView.text = message
                }
                ObdService.ACTION_OBD_DATA_UPDATE -> {
                    @Suppress("UNCHECKED_CAST")
                    val updatedPids = intent.getSerializableExtra(ObdService.EXTRA_PID_LIST_UPDATED) as? ArrayList<ObdPid>
                    if (updatedPids != null) {
                        selectedPids.clear()
                        selectedPids.addAll(updatedPids)
                        obdDataAdapter.updateData(selectedPids)
                    }
                }
                // Case mới cho MqttService
                MqttService.ACTION_MQTT_STATUS_UPDATE -> {
                    val message = intent.getStringExtra(MqttService.EXTRA_STATUS_MESSAGE)
                    mqttStatusTextView.text = message
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        setupUI()
        checkAndRequestPermissions()
    }

    override fun onResume() {
        super.onResume()
        val intentFilter = IntentFilter().apply {
            addAction(ObdService.ACTION_OBD_STATUS_UPDATE)
            addAction(ObdService.ACTION_OBD_DATA_UPDATE)
            addAction(MqttService.ACTION_MQTT_STATUS_UPDATE) // Thêm action của MqttService
        }
        ContextCompat.registerReceiver(this, serviceUpdateReceiver, intentFilter, ContextCompat.RECEIVER_NOT_EXPORTED)
        updateUiState()
    }

    override fun onPause() {
        super.onPause()
        unregisterReceiver(serviceUpdateReceiver)
    }

    private fun updateUiState() {
        // Cập nhật trạng thái cho ObdService
        if (ObdService.isRunning) {
            startStopObdButton.text = "Dừng đọc"
            statusTextView.text = ObdService.lastStatusMessage
        } else {
            startStopObdButton.text = "Bắt đầu đọc"
            statusTextView.text = "Đã dừng"
        }

        // Cập nhật trạng thái cho MqttService
        if (MqttService.isRunning) {
            startStopMqttButton.text = "Dừng gửi MQTT"
            mqttStatusTextView.text = MqttService.lastStatusMessage
        } else {
            startStopMqttButton.text = "Bắt đầu gửi MQTT"
            mqttStatusTextView.text = "MQTT: Đã dừng"
        }
    }

    private fun setupUI() {
        statusTextView = findViewById(R.id.statusTextView)
        mqttStatusTextView = findViewById(R.id.mqttStatusTextView) // Gán biến cho view mới
        bluetoothSpinner = findViewById(R.id.bluetoothSpinner)
        startStopObdButton = findViewById(R.id.startStopButton)
        selectPidsButton = findViewById(R.id.selectPidsButton)
        obdDataRecyclerView = findViewById(R.id.obdDataRecyclerView)
        startStopMqttButton = findViewById(R.id.connectMqttButton) // Tái sử dụng nút này
//        sendDataButton = findViewById(R.id.sendDataButton)
//        loopbackTextView = findViewById(R.id.loopbackTextView)

        // Vô hiệu hóa nút "Gửi dữ liệu" cũ
//        sendDataButton.isEnabled = false
//        sendDataButton.text = "Gửi (Tự động)"

        obdDataAdapter = ObdDataAdapter(emptyList())
        obdDataRecyclerView.layoutManager = LinearLayoutManager(this)
        obdDataRecyclerView.adapter = obdDataAdapter

        startStopObdButton.setOnClickListener {
            if (ObdService.isRunning) stopObdService() else startObdService()
        }

        startStopMqttButton.setOnClickListener {
            if (MqttService.isRunning) stopMqttService() else startMqttService()
        }

        selectPidsButton.setOnClickListener { showPidSelectionDialog() }
    }

    private fun startMqttService() {
        if (!ObdService.isRunning) {
            Toast.makeText(this, "Vui lòng chạy service đọc OBD trước", Toast.LENGTH_SHORT).show()
            return
        }
        val intent = Intent(this, MqttService::class.java).apply {
            action = MqttService.ACTION_START_SERVICE
        }
        startService(intent)
        updateUiState()
    }

    private fun stopMqttService() {
        val intent = Intent(this, MqttService::class.java).apply {
            action = MqttService.ACTION_STOP_SERVICE
        }
        startService(intent)
        updateUiState()
    }

    // Các hàm của OBD Service giữ nguyên...
    private fun startObdService() {
        if (selectedDevice == null) {
            Toast.makeText(this, "Vui lòng chọn một thiết bị Bluetooth", Toast.LENGTH_SHORT).show()
            return
        }
        if (selectedPids.isEmpty()) {
            selectedPids.addAll(allSupportedPids)
            obdDataAdapter.updateData(selectedPids)
            Toast.makeText(this, "Chưa chọn PID, mặc định đọc tất cả.", Toast.LENGTH_SHORT).show()
        }
        val serviceIntent = Intent(this, ObdService::class.java).apply {
            action = ObdService.ACTION_START_SERVICE
            putExtra(ObdService.EXTRA_DEVICE_ADDRESS, selectedDevice!!.address)
            putExtra(ObdService.EXTRA_PID_LIST, selectedPids as Serializable)
        }
        startService(serviceIntent)
        updateUiState()
    }

    private fun stopObdService() {
        // Khi dừng đọc OBD, cũng nên dừng gửi MQTT
        if (MqttService.isRunning) {
            stopMqttService()
        }
        val serviceIntent = Intent(this, ObdService::class.java).apply {
            action = ObdService.ACTION_STOP_SERVICE
        }
        startService(serviceIntent)
        updateUiState()
        obdDataAdapter.updateData(emptyList())
    }

    // Các hàm còn lại giữ nguyên
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
                if (ObdService.isRunning) {
                    Toast.makeText(this, "Vui lòng dừng service trước khi đổi lựa chọn PIDs.", Toast.LENGTH_LONG).show()
                } else {
                    selectedPids = newSelection
                    obdDataAdapter.updateData(selectedPids)
                    Toast.makeText(this, "Đã chọn ${selectedPids.size} thông số", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Hủy", null)
            .show()
    }

    private fun listPairedDevices() {
        val btManager = OBDBluetoothManager(this)
        pairedDeviceList = btManager.getPairedDevices()
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

    private fun checkAndRequestPermissions() {
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
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                permissionsToRequest.add(Manifest.permission.POST_NOTIFICATIONS)
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
                Toast.makeText(this, "Bạn cần cấp quyền để ứng dụng hoạt động", Toast.LENGTH_LONG).show()
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
    }
}