package vn.vnpt.obdtoiotservice


// Data class để biểu diễn một thông số OBD
data class ObdPid(
    val command: String,          // Lệnh gửi đi, ví dụ: "010C"
    val name: String,             // Tên hiển thị, ví dụ: "Vòng tua máy"
    var value: String = "--",     // Giá trị hiện tại, mặc định là "--"
    val unit: String = ""         // Đơn vị, ví dụ: "rpm", "km/h"
)