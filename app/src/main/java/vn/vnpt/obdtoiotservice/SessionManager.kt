package vn.vnpt.obdtoiotservice


import android.content.Context
import android.content.SharedPreferences

class SessionManager(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences("OBD_APP_PREFS", Context.MODE_PRIVATE)

    companion object {
        const val KEY_AE_ID = "key_ae_id"
        const val KEY_IS_SETUP_COMPLETE = "key_is_setup_complete"
    }

    fun saveAeId(aeId: String) {
        val editor = prefs.edit()
        editor.putString(KEY_AE_ID, aeId)
        editor.apply()
    }

    fun getAeId(): String? {
        return prefs.getString(KEY_AE_ID, null)
    }

    fun setSetupComplete(isComplete: Boolean) {
        val editor = prefs.edit()
        editor.putBoolean(KEY_IS_SETUP_COMPLETE, isComplete)
        editor.apply()
    }

    fun isSetupComplete(): Boolean {
        return prefs.getBoolean(KEY_IS_SETUP_COMPLETE, false)
    }
}