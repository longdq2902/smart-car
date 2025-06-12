package vn.vnpt.obdtoiotservice


import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

class ObdDataAdapter(private var pidList: List<ObdPid>) : RecyclerView.Adapter<ObdDataAdapter.ObdViewHolder>() {

    // Lớp ViewHolder: Đại diện cho giao diện của MỘT dòng trong danh sách.
    // Nó giữ các tham chiếu đến các TextView trong file item_obd_data.xml
    class ObdViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val pidNameTextView: TextView = itemView.findViewById(R.id.pidNameTextView)
        val pidValueTextView: TextView = itemView.findViewById(R.id.pidValueTextView)
    }

    // Được gọi khi RecyclerView cần tạo một ViewHolder mới (một dòng mới).
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ObdViewHolder {
        // "Thổi phồng" (inflate) layout item_obd_data.xml để tạo ra view cho một dòng
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_obd_data, parent, false)
        return ObdViewHolder(view)
    }

    // Trả về tổng số mục trong danh sách
    override fun getItemCount(): Int {
        return pidList.size
    }

    // Được gọi khi RecyclerView cần hiển thị dữ liệu tại một vị trí cụ thể.
    // Nó lấy dữ liệu từ danh sách và điền vào các view của ViewHolder.
    override fun onBindViewHolder(holder: ObdViewHolder, position: Int) {
        val currentPid = pidList[position]
        holder.pidNameTextView.text = currentPid.name
        holder.pidValueTextView.text = "${currentPid.value} ${currentPid.unit}"
    }

    // Hàm helper để cập nhật danh sách dữ liệu từ MainActivity và báo cho Adapter vẽ lại.
    fun updateData(newPidList: List<ObdPid>) {
        this.pidList = newPidList
        notifyDataSetChanged() // Yêu cầu RecyclerView vẽ lại toàn bộ danh sách
    }
}