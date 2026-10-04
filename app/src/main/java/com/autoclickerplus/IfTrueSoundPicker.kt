package com.autoclickerplus

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import com.autoclickerplus.model.PickedIfSound
import com.autoclickerplus.service.AutoClickAccessibilityService
import java.io.File
import java.util.UUID

object IfTrueSoundPicker {
    fun intent(existingSourceUri: String?): Intent =
        Intent(RingtoneManager.ACTION_RINGTONE_PICKER).apply {
            putExtra(RingtoneManager.EXTRA_RINGTONE_TYPE, RingtoneManager.TYPE_ALL)
            putExtra(RingtoneManager.EXTRA_RINGTONE_TITLE, "true時の音")
            putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_SILENT, true)
            putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_DEFAULT, false)
            existingSourceUri?.takeIf { it.isNotBlank() }?.let { source ->
                putExtra(RingtoneManager.EXTRA_RINGTONE_EXISTING_URI, Uri.parse(source))
            }
        }
}

fun Context.readPickedIfSound(data: Intent?): PickedIfSound {
    val source = data?.pickedRingtoneUri() ?: return PickedIfSound(null, null, "")
    runCatching {
        contentResolver.takePersistableUriPermission(
            source,
            Intent.FLAG_GRANT_READ_URI_PERMISSION,
        )
    }
    val title = runCatching {
        RingtoneManager.getRingtone(this, source)?.getTitle(this)
    }.getOrNull().orEmpty().ifBlank { "選択した音" }
    val playbackUri = cachePickedSound(source) ?: source.toString()
    return PickedIfSound(
        playbackUri = playbackUri,
        sourceUri = source.toString(),
        title = title,
    )
}

private fun Context.cachePickedSound(source: Uri): String? {
    val directory = File(filesDir, "if-sounds").apply { mkdirs() }
    val destination = File(directory, "${UUID.randomUUID()}.audio")
    return try {
        contentResolver.openInputStream(source)?.use { input ->
            destination.outputStream().use { output -> input.copyTo(output) }
        } ?: return null
        if (destination.length() <= 0L) {
            destination.delete()
            null
        } else {
            destination.absolutePath
        }
    } catch (_: Exception) {
        destination.delete()
        null
    }
}

private fun Intent.pickedRingtoneUri(): Uri? =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        getParcelableExtra(RingtoneManager.EXTRA_RINGTONE_PICKED_URI, Uri::class.java)
    } else {
        @Suppress("DEPRECATION")
        getParcelableExtra(RingtoneManager.EXTRA_RINGTONE_PICKED_URI)
    }

class SoundPickerActivity : Activity() {
    override fun onCreate(savedInstanceState: android.os.Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) {
            startActivityForResult(
                IfTrueSoundPicker.intent(intent.getStringExtra(EXTRA_EXISTING_URI)),
                REQUEST_PICK,
            )
        }
    }

    @Deprecated("Trampoline activity uses the framework picker result")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (requestCode == REQUEST_PICK && resultCode == RESULT_OK) {
            intent.getStringExtra(EXTRA_ACTION_ID)?.let { actionId ->
                AutoClickAccessibilityService.applyPickedIfSound(
                    actionId,
                    readPickedIfSound(data),
                )
            }
        }
        finish()
    }

    companion object {
        const val EXTRA_ACTION_ID = "actionId"
        const val EXTRA_EXISTING_URI = "existingUri"
        private const val REQUEST_PICK = 41
    }
}
