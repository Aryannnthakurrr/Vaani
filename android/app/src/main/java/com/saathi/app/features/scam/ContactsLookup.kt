package com.saathi.app.features.scam

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.ContactsContract
import androidx.core.content.ContextCompat

/** Saved-contact lookups. Returns "not a contact" when the permission is missing or the sender is a short code. */
class ContactsLookup(private val context: Context) {

    private fun allowed() =
        ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED

    fun displayName(number: String): String? {
        if (number.count(Char::isDigit) < 6 || !allowed()) return null
        val uri = Uri.withAppendedPath(ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(number))
        return runCatching {
            context.contentResolver.query(uri, arrayOf(ContactsContract.PhoneLookup.DISPLAY_NAME), null, null, null)
                ?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
        }.getOrNull()
    }

    fun isSavedContact(number: String): Boolean = displayName(number) != null

    /** Display names in the phone book (for fixing misheard names on the phone; never sent anywhere). */
    fun allNames(limit: Int = 2_000): List<String> {
        if (!allowed()) return emptyList()
        return runCatching {
            context.contentResolver.query(
                ContactsContract.Contacts.CONTENT_URI, arrayOf(ContactsContract.Contacts.DISPLAY_NAME_PRIMARY), null, null, null,
            )?.use { c ->
                buildList { while (c.moveToNext() && size < limit) c.getString(0)?.takeIf { it.isNotBlank() }?.let(::add) }
            }.orEmpty()
        }.getOrDefault(emptyList())
    }
}
