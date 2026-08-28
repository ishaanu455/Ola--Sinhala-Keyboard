package com.ola.keyboard

import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import androidx.core.content.FileProvider
import java.io.File

/**
 * Thin wrapper around [DownloadManager] + [FileProvider] for the update flow.
 * Lives entirely on the Activity side (UpdateActivity) - same reasoning as
 * UpdateChecker: keep anything that touches the filesystem/package installer
 * far away from the IME service.
 */
object UpdateDownloader {

    private const val AUTHORITY_SUFFIX = ".fileprovider"

    private fun targetFile(context: Context, version: String): File {
        val dir = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
            ?: context.filesDir
        return File(dir, "ola-keyboard-update-$version.apk")
    }

    /** Enqueues the APK download and returns the DownloadManager request id. */
    fun startDownload(context: Context, apkUrl: String, version: String): Long {
        val file = targetFile(context, version)
        if (file.exists()) file.delete()

        val request = DownloadManager.Request(Uri.parse(apkUrl))
            .setTitle("Ola Keyboard update")
            .setDescription("Downloading version $version")
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setDestinationUri(Uri.fromFile(file))
            .setAllowedOverMetered(true)
            .setAllowedOverRoaming(true)

        val manager = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        return manager.enqueue(request)
    }

    /** Returns 0-100 progress for an in-flight download, or null once it's no longer queryable. */
    fun queryProgress(context: Context, downloadId: Long): Int? {
        val manager = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        val cursor = manager.query(DownloadManager.Query().setFilterById(downloadId)) ?: return null
        cursor.use {
            if (!it.moveToFirst()) return null
            val totalIdx = it.getColumnIndex(DownloadManager.COLUMN_TOTAL_SIZE_BYTES)
            val downloadedIdx = it.getColumnIndex(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR)
            val total = it.getLong(totalIdx)
            val downloaded = it.getLong(downloadedIdx)
            if (total <= 0) return 0
            return ((downloaded * 100L) / total).toInt().coerceIn(0, 100)
        }
    }

    /** True once the given download has finished successfully. */
    fun isDownloadSuccessful(context: Context, downloadId: Long): Boolean {
        val manager = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        val cursor = manager.query(DownloadManager.Query().setFilterById(downloadId)) ?: return false
        cursor.use {
            if (!it.moveToFirst()) return false
            val statusIdx = it.getColumnIndex(DownloadManager.COLUMN_STATUS)
            return it.getInt(statusIdx) == DownloadManager.STATUS_SUCCESSFUL
        }
    }

    /** Whether the user still needs to grant "install unknown apps" for us (API 26+). */
    fun needsInstallPermission(context: Context): Boolean {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            !context.packageManager.canRequestPackageInstalls()
    }

    /** Intent to send the user to the "install unknown apps" system settings screen for this app. */
    fun installPermissionIntent(context: Context): Intent {
        return Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).apply {
            data = Uri.parse("package:${context.packageName}")
        }
    }

    /** Launches the system package installer for the already-downloaded APK. */
    fun installApk(context: Context, version: String) {
        val file = targetFile(context, version)
        val uri = FileProvider.getUriForFile(
            context,
            context.packageName + AUTHORITY_SUFFIX,
            file
        )
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }
}
