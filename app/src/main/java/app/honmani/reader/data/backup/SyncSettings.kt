package app.honmani.reader.data.backup

import android.content.SharedPreferences
import app.honmani.reader.data.settings.DaySettings
import app.honmani.reader.data.settings.ReviewSettings

/**
 * The few settings that follow the reader across phones, because they decide what the shared data
 * means (the rest stay per phone). The later change wins. Edits are found by comparing with the value
 * recorded in the "device" preferences, so the settings' own setters stay as they are.
 */
class SyncSettings(
    private val settings: SharedPreferences,
    private val device: SharedPreferences,
    private val now: () -> Long = System::currentTimeMillis,
) {
    /** The settings for this phone's sync file; one the reader never set is left out. */
    fun stamped(): Map<String, BackupSyncSetting> {
        val stamped = HashMap<String, BackupSyncSetting>()
        for (key in KEYS) {
            if (!settings.contains(key)) continue
            val value = settings.getInt(key, 0)
            val recorded = recorded(key)
            stamped[key] = if (recorded?.value == value) recorded else BackupSyncSetting(value, now()).also { record(key, it) }
        }
        return stamped.toSortedMap()
    }

    /** Takes the settings changed later on another phone; returns whether any changed. */
    fun merge(incoming: Map<String, BackupSyncSetting>): Boolean {
        val mine = stamped()
        var changed = false
        for (key in KEYS) {
            val theirs = incoming[key] ?: continue
            val current = mine[key]
            if (current != null && theirs.at <= current.at) continue
            settings.edit().putInt(key, theirs.value).apply()
            record(key, theirs)
            changed = changed || current?.value != theirs.value
        }
        return changed
    }

    private fun recorded(key: String): BackupSyncSetting? {
        if (!device.contains(VALUE + key)) return null
        return BackupSyncSetting(device.getInt(VALUE + key, 0), device.getLong(AT + key, 0))
    }

    private fun record(key: String, setting: BackupSyncSetting) {
        device.edit().putInt(VALUE + key, setting.value).putLong(AT + key, setting.at).apply()
    }

    private companion object {
        val KEYS = listOf(DaySettings.KEY_START_HOUR, ReviewSettings.KEY_NEW_PER_DAY, ReviewSettings.KEY_REVIEWS_PER_DAY)
        const val VALUE = "sync_setting_value_"
        const val AT = "sync_setting_at_"
    }
}
