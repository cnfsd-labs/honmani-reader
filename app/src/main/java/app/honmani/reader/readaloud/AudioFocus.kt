package app.honmani.reader.readaloud

import android.media.AudioManager

/**
 * Whether a focus [change] pauses what Honmani speaks: losing focus for good or for a while, and
 * also a request to duck (a navigation prompt, a notification), as Android does not lower spoken
 * content itself and speech under another sound is not understood. Like a transient loss, regaining
 * focus does not resume it.
 */
internal fun pausesForFocusChange(change: Int): Boolean =
    change == AudioManager.AUDIOFOCUS_LOSS ||
        change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT ||
        change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK
