package vn.vnpt.obdtoiotservice

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager as AndroidBluetoothManager // Đổi tên để tránh trùng lặp
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.app.ActivityCompat
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID

// Đổi tên lớp để tránh nhầm lẫn với BluetoothManager của Android
class OBDBluetoothManager(private val context: Context) {

    companion object {
        private const val TAG = "OBDBluetoothManager"
        private val SPP_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
    }

    // Cách lấy BluetoothAdapter mới, đúng chuẩn
    private val bluetoothManager: AndroidBluetoothManager? = context.getSystemService(Context.BLUETOOTH_SERVICE) as? AndroidBluetoothManager
    private val bluetoothAdapter: BluetoothAdapter? = bluetoothManager?.adapter

    private var bluetoothSocket: BluetoothSocket? = null
    private var outputStream: OutputStream? = null
    private var inputStream: InputStream? = null

    fun isBluetoothSupported(): Boolean = bluetoothAdapter != null

    // Di chuyển logic lấy danh sách thiết bị vào đây cho gọn
    @SuppressLint("MissingPermission")
    fun getPairedDevices(): List<BluetoothDevice> {
        return bluetoothAdapter?.bondedDevices?.toList() ?: emptyList()
    }

    @SuppressLint("MissingPermission")
    fun connect(device: BluetoothDevice): Boolean {
        bluetoothAdapter?.cancelDiscovery()

        try {
            bluetoothSocket = device.createRfcommSocketToServiceRecord(SPP_UUID)
            bluetoothSocket?.connect()
            outputStream = bluetoothSocket?.outputStream
            inputStream = bluetoothSocket?.inputStream
            Log.d(TAG, "Kết nối Bluetooth thành công!")
            return true
        } catch (e: IOException) {
            Log.e(TAG, "Lỗi kết nối Bluetooth: ${e.message}")
            closeSocket()
            return false
        }
    }

    fun sendCommand(command: String) {
        if (bluetoothSocket?.isConnected == true && outputStream != null) {
            try {
                val fullCommand = command + "\r"
                outputStream?.write(fullCommand.toByteArray())
                outputStream?.flush()
                Log.d(TAG, "Đã gửi lệnh: $command")
            } catch (e: IOException) {
                Log.e(TAG, "Lỗi khi gửi lệnh: ${e.message}")
            }
        } else {
            Log.w(TAG, "Không thể gửi lệnh, socket chưa được kết nối.")
        }
    }

    fun readResponse(): String? {
        if (bluetoothSocket?.isConnected == true && inputStream != null) {
            val stringBuilder = StringBuilder()
            try {
                var charCode: Int
                // Đọc cho đến khi gặp ký tự '>' là dấu nhắc kết thúc của ELM327
                while (true) {
                    charCode = inputStream!!.read()
                    if (charCode == -1 || charCode.toChar() == '>') {
                        break // Kết thúc nếu hết luồng hoặc gặp dấu '>'
                    }
                    val character = charCode.toChar()
                    // Chỉ chấp nhận các ký tự hợp lệ cho phản hồi OBD (hex, số, chữ, và dấu cách)
                    if (character.isLetterOrDigit() || character.isWhitespace()) {
                        stringBuilder.append(character)
                    }
                }
                // Loại bỏ các ký tự rác và các lệnh AT được echo lại
                val cleanResponse = stringBuilder.toString()
                    .lines()
                    .lastOrNull { it.isNotBlank() && !it.contains("AT") }
                    ?.trim()

                Log.d(TAG, "Đã nhận phản hồi (đã làm sạch): $cleanResponse")
                return cleanResponse
            } catch (e: IOException) {
                Log.e(TAG, "Lỗi khi đọc phản hồi: ${e.message}")
                return null
            }
        }
        Log.w(TAG, "Không thể đọc, socket chưa được kết nối.")
        return null
    }

    fun disconnect() {
        try {
            closeSocket()
            Log.d(TAG, "Đã ngắt kết nối Bluetooth.")
        } catch (e: IOException) {
            Log.e(TAG, "Lỗi khi ngắt kết nối: ${e.message}")
        }
    }

    private fun closeSocket() {
        try {
            outputStream?.close()
            inputStream?.close()
            bluetoothSocket?.close()
        } catch (e: IOException) {
            Log.e(TAG, "Lỗi khi đóng socket: ${e.message}")
        }
    }
}