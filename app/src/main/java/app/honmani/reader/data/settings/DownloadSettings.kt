package app.honmani.reader.data.settings

import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Settings → Books → "Download books over mobile data": whether the automatic import from book
 * catalogues runs on a metered network. Off by default; books a reader downloads by hand are not
 * affected.
 */
class DownloadSettings(private val preferences: SharedPreferences) {
    private val _overMobileData = MutableStateFlow(stored())
    val overMobileData: StateFlow<Boolean> = _overMobileData.asStateFlow()

    fun setOverMobileData(value: Boolean) {
        _overMobileData.value = value
        preferences.edit().putBoolean(KEY_OVER_MOBILE_DATA, value).apply()
    }

    /** Reads the stored value again (a backup was restored). */
    fun reload() {
        _overMobileData.value = stored()
    }

    private fun stored() = preferences.getBoolean(KEY_OVER_MOBILE_DATA, false)

    companion object {
        const val KEY_OVER_MOBILE_DATA = "download_over_mobile_data"
    }
}
