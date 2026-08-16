package com.surenjanath.crownfoundry.utils

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.widget.Toast

fun Context.toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_SHORT).show()

/** Hands a link over to whatever browser the reader uses. */
fun Context.openUrl(url: String) {
    try {
        startActivity(
            Intent(Intent.ACTION_VIEW, Uri.parse(url))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    } catch (e: Exception) {
        toast("No app can open this link")
    }
}

fun Context.shareText(text: String, title: String? = null) {
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_TEXT, text)
        title?.let { putExtra(Intent.EXTRA_SUBJECT, it) }
    }

    startActivity(Intent.createChooser(intent, null))
}

fun Context.copyToClipboard(text: String) {
    val manager = getSystemService(Context.CLIPBOARD_SERVICE) as? android.content.ClipboardManager
    manager?.setPrimaryClip(android.content.ClipData.newPlainText(null, text))
    toast("Copied to clipboard")
}

inline val isAtLeastAndroid6
    get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.M

inline val isAtLeastAndroid8
    get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
