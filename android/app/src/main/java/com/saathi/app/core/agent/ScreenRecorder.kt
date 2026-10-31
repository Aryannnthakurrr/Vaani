package com.saathi.app.core.agent

import android.content.ContentValues
import android.content.Context
import android.os.Environment
import android.provider.MediaStore
import com.saathi.app.util.SaathiJson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Debug tool (SPEC §9.3): dumps the current screen as a fixture to `Download/saathi_screens/<pkg>_<n>.json`
 * so real Uber/Practo/Amazon screens can be `adb pull`ed into `fixtures/screens/`.
 */
object ScreenRecorder {
    private var counter = 0

    suspend fun record(context: Context, reader: LiveScreenReader): String? = withContext(Dispatchers.IO) {
        val snap = reader.read()
        if (snap.nodes.isEmpty()) return@withContext null
        val fixture = ScreenFixture(snap.pkg, snap.title, snap.secure, snap.nodes)
        val json = SaathiJson.encodeToString(ScreenFixture.serializer(), fixture)
        val name = "${snap.pkg}_${System.currentTimeMillis() / 1000}_${++counter}.json"
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, name)
            put(MediaStore.Downloads.MIME_TYPE, "application/json")
            put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/saathi_screens")
        }
        val uri = context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: return@withContext null
        context.contentResolver.openOutputStream(uri)?.use { it.write(json.toByteArray()) }
        "Download/saathi_screens/$name"
    }
}
