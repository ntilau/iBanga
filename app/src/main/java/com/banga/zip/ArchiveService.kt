package com.banga.zip

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File

/**
 * Foreground service that runs 7z archive / extract operations in the
 * background and publishes progress as a system notification.
 *
 * Communication
 * -------------
 * Start a new operation via [start] — internally calls
 * [Context.startForegroundService].  Cancel via [cancel].
 * The UI observes progress through [ArchiveProgress.state] and does not
 * interact with the service directly once started.
 *
 * Lifecycle
 * ---------
 * The service runs as a foreground service (notification always visible)
 * while the operation is in-flight.  On completion or cancellation it
 * stops itself and removes the foreground notification.  A final
 * result notification is posted briefly so the user sees the outcome
 * even if the app is closed.
 */
class ArchiveService : Service() {

    private lateinit var notificationManager: NotificationManager
    private var operationJob: Job? = null

    /** Scope tied to this service instance for the I/O-heavy operation. */
    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // ---------------------------------------------------------------
    // Companion: public API
    // ---------------------------------------------------------------

    companion object {
        private const val CHANNEL_ID = "archive_progress"
        private const val FOREGROUND_NOTIFY_ID = 1001
        private const val RESULT_NOTIFY_ID = 1002
        private const val CANCEL_REQUEST_CODE = 2001
        private const val OPEN_REQUEST_CODE = 2002

        const val ACTION_START = "com.banga.zip.action.START"
        const val ACTION_CANCEL = "com.banga.zip.action.CANCEL"
        const val EXTRA_SOURCE = "extra_source"
        const val EXTRA_DEST = "extra_dest"
        const val EXTRA_PASSWORD = "extra_password"
        const val EXTRA_IS_ARCHIVE = "extra_is_archive"

        /** Start a new archive or extract operation in the foreground. */
        fun start(
            context: Context,
            sourcePath: String,
            destPath: String,
            password: String?,
            isArchive: Boolean
        ) {
            val intent = Intent(context, ArchiveService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_SOURCE, sourcePath)
                putExtra(EXTRA_DEST, destPath)
                putExtra(EXTRA_PASSWORD, password)
                putExtra(EXTRA_IS_ARCHIVE, isArchive)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        /** Request cancellation of the currently-running operation. */
        fun cancel(context: Context) {
            context.startService(Intent(context, ArchiveService::class.java).apply {
                action = ACTION_CANCEL
            })
        }
    }

    // ---------------------------------------------------------------
    // Service lifecycle
    // ---------------------------------------------------------------

    override fun onCreate() {
        super.onCreate()
        notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> handleStart(intent)
            ACTION_CANCEL -> handleCancel()
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        operationJob?.cancel()
        operationJob = null
        serviceScope.cancel()
        super.onDestroy()
    }

    // ---------------------------------------------------------------
    // Action handlers
    // ---------------------------------------------------------------

    private fun handleStart(intent: Intent) {
        // Ignore if an operation is already running.
        if (operationJob?.isActive == true) return

        val sourcePath = intent.getStringExtra(EXTRA_SOURCE) ?: return
        val destPath = intent.getStringExtra(EXTRA_DEST) ?: return
        val password = intent.getStringExtra(EXTRA_PASSWORD)
        val isArchive = intent.getBooleanExtra(EXTRA_IS_ARCHIVE, true)

        ArchiveProgress.reset()
        val modeLabel = if (isArchive) "Archiving" else "Extracting"
        ArchiveProgress.update {
            it.copy(isRunning = true, mode = modeLabel)
        }

        startForeground(
            FOREGROUND_NOTIFY_ID,
            buildForegroundNotification(modeLabel, 0, 0, "Starting…")
        )

        operationJob = serviceScope.launch {
            try {
                val helper = ArchiveHelper()
                if (isArchive) {
                    helper.archiveFolder(
                        sourceFolder = sourcePath,
                        archivePath = destPath,
                        password = password,
                        onProgress = { cur, tot, name ->
                            publishProgress(modeLabel, cur, tot, name)
                        },
                        isActive = { isActive }
                    )
                } else {
                    helper.extractArchive(
                        archivePath = sourcePath,
                        destinationFolder = destPath,
                        password = password,
                        onProgress = { cur, tot, name ->
                            publishProgress(modeLabel, cur, tot, name)
                        },
                        isActive = { isActive }
                    )
                }

                // Success
                val resultText = "$modeLabel completed successfully!"
                ArchiveProgress.update {
                    it.copy(isRunning = false, result = resultText, isError = false)
                }
                postResultNotification("✅ $resultText")

            } catch (_: CancellationException) {
                // User cancelled – clean up partial output.
                if (isArchive) File(destPath).delete()
                ArchiveProgress.update {
                    it.copy(isRunning = false, result = "Operation cancelled", isError = true)
                }
                postResultNotification("⏹ Operation cancelled")

            } catch (e: Exception) {
                val msg = when {
                    e.message?.contains("password", ignoreCase = true) == true ->
                        "Wrong password or corrupted archive"
                    e is IllegalArgumentException ->
                        e.message ?: "Invalid input"
                    else ->
                        "Error: ${e.message ?: "Unknown error"}"
                }
                ArchiveProgress.update {
                    it.copy(isRunning = false, result = msg, isError = true)
                }
                postResultNotification("❌ $msg")

            } finally {
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
    }

    private fun handleCancel() {
        ArchiveProgress.update { it.copy(isCancelling = true) }
        operationJob?.cancel()
    }

    // ---------------------------------------------------------------
    // Progress
    // ---------------------------------------------------------------

    private fun publishProgress(
        modeLabel: String,
        current: Int,
        total: Int,
        fileName: String
    ) {
        ArchiveProgress.update {
            it.copy(current = current, total = total, fileName = fileName)
        }
        val notification = buildForegroundNotification(modeLabel, current, total, fileName)
        notificationManager.notify(FOREGROUND_NOTIFY_ID, notification)
    }

    // ---------------------------------------------------------------
    // Notifications
    // ---------------------------------------------------------------

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.notif_channel_name),
                NotificationManager.IMPORTANCE_LOW   // silent, no heads-up
            ).apply {
                description = getString(R.string.notif_channel_desc)
                setShowBadge(false)
            }
            notificationManager.createNotificationChannel(channel)
        }
    }

    /** Build the ongoing foreground notification with a progress bar. */
    private fun buildForegroundNotification(
        title: String,
        current: Int,
        total: Int,
        fileName: String
    ): Notification {
        val openIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val openPendingIntent = PendingIntent.getActivity(
            this, OPEN_REQUEST_CODE, openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val cancelIntent = Intent(this, ArchiveService::class.java).apply {
            action = ACTION_CANCEL
        }
        val cancelPendingIntent = PendingIntent.getService(
            this, CANCEL_REQUEST_CODE, cancelIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val progressMax = if (total > 0) total else 0
        val isIndeterminate = total <= 0

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification_archive)
            .setContentTitle(title)
            .setContentText(fileName.ifEmpty { getString(R.string.starting) })
            .setContentIntent(openPendingIntent)
            .setOngoing(true)
            .setSilent(true)
            .setProgress(progressMax, current, isIndeterminate)
            .addAction(
                android.R.drawable.ic_menu_close_clear_cancel,
                getString(R.string.cancel),
                cancelPendingIntent
            )
            .build()
    }

    /**
     * Post a one-shot result notification (non-foreground) so the user
     * sees the outcome even if the app is closed.  Auto-cancels when
     * tapped.
     */
    private fun postResultNotification(text: String) {
        // Permission check – system silently drops on 13+ if POST_NOTIFICATIONS
        // was denied, so we just attempt to post.

        val openIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val openPendingIntent = PendingIntent.getActivity(
            this, OPEN_REQUEST_CODE, openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification_archive)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(text)
            .setContentIntent(openPendingIntent)
            .setAutoCancel(true)
            .setOngoing(false)
            .setSilent(true)
            .setProgress(0, 0, false)
            .build()

        notificationManager.notify(RESULT_NOTIFY_ID, notification)
    }
}
