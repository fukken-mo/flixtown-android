package com.nimertv.app.data

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.os.Build
import com.nimertv.app.BuildConfig
import kotlinx.coroutines.flow.MutableStateFlow
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.security.MessageDigest

enum class UpdateState { NONE, OPTIONAL, MANDATORY }

/** Pure update decisions; unit tested. */
object UpdateLogic {
    /**
     * Decided only from version codes published by the panel, never from the APK file
     * name or its modification date.
     */
    fun state(update: UpdateInfo?, installedVersionCode: Int, packageName: String): UpdateState {
        if (update == null) return UpdateState.NONE
        if (update.packageName.isNotEmpty() && update.packageName != packageName) return UpdateState.NONE
        if (update.versionCode <= installedVersionCode) return UpdateState.NONE
        if (!update.apkUrl.startsWith("https://")) return UpdateState.NONE
        val mandatory = update.mandatory || installedVersionCode < update.minSupportedVersionCode
        return if (mandatory) UpdateState.MANDATORY else UpdateState.OPTIONAL
    }

    /** Minimum interval between update checks triggered by app resume. */
    const val RESUME_CHECK_INTERVAL_MS = 15L * 60 * 1000

    fun shouldCheck(now: Long, lastCheck: Long) = now - lastCheck >= RESUME_CHECK_INTERVAL_MS || now < lastCheck

    /** Download + installer session copy + headroom. */
    fun requiredBytes(apkSize: Long) = apkSize * 2 + 24L * 1024 * 1024

    /**
     * Validates an APK's identity against the installed app and panel metadata.
     * Returns null when acceptable, otherwise the failure.
     */
    fun verifyIdentity(
        expectedPackage: String,
        installedVersionCode: Long,
        installedSigner: String?,
        update: UpdateInfo,
        archivePackage: String?,
        archiveVersionCode: Long,
        archiveSigner: String?,
    ): UpdateFailure? {
        if (archivePackage == null) return UpdateFailure(FailureKind.INTEGRITY, "The downloaded file is not a valid Android package.")
        if (archivePackage != expectedPackage) return UpdateFailure(FailureKind.INTEGRITY, "The downloaded file belongs to a different app.")
        if (archiveVersionCode <= installedVersionCode || archiveVersionCode < update.versionCode) {
            // The panel announces a version that the published file does not contain. Installing
            // it could never satisfy the panel, so this must not lock the user out.
            return UpdateFailure(
                FailureKind.NOT_PUBLISHED,
                "The update file on the server (build $archiveVersionCode) does not match the announced version ${update.versionCode}.",
            )
        }
        if (archiveSigner != null && installedSigner != null && archiveSigner != installedSigner) {
            return UpdateFailure(FailureKind.INTEGRITY, "The update is signed with a different key and was rejected.")
        }
        if (archiveSigner != null && update.signerSha256.isNotEmpty() && archiveSigner != update.signerSha256) {
            return UpdateFailure(FailureKind.INTEGRITY, "The update's signature does not match the one published by Nimer TV.")
        }
        return null
    }
}

enum class FailureKind { OFFLINE, STORAGE, INTEGRITY, NOT_PUBLISHED, INSTALL }

data class UpdateFailure(val kind: FailureKind, val message: String)

class UpdateException(val failure: UpdateFailure) : Exception(failure.message)

/** Result of the system installer, delivered by [InstallReceiver]. */
sealed class InstallStatus {
    data object Idle : InstallStatus()
    data object AwaitingConfirmation : InstallStatus()
    data object Cancelled : InstallStatus()
    data class Failed(val message: String) : InstallStatus()
}

object InstallEvents {
    val status = MutableStateFlow<InstallStatus>(InstallStatus.Idle)
}

/**
 * Downloads the published APK over HTTPS, verifies checksum, package, version and signing
 * certificate, then hands it to Android's PackageInstaller. Android always asks the user to
 * confirm; ordinary apps cannot install silently.
 */
class Updater(private val context: Context) {
    private val dir = File(context.cacheDir, "updates")

    /** Blocking. Returns the verified APK. */
    @Throws(UpdateException::class)
    fun download(update: UpdateInfo, onProgress: (Int) -> Unit): File {
        if (!update.apkUrl.startsWith("https://")) fail(FailureKind.INTEGRITY, "The update address is not secure.")
        if (update.sha256.length != 64) {
            // Integrity cannot be proven without the panel's checksum; never install such a file.
            fail(FailureKind.NOT_PUBLISHED, "The update was published without a checksum and cannot be verified.")
        }
        dir.mkdirs()
        val target = File(dir, "NimerTV-${update.versionCode}.apk")
        dir.listFiles()?.forEach { if (it != target) it.delete() }

        // A verified file from an earlier attempt (e.g. the user cancelled the installer) is reused.
        if (target.exists() && checksumMatches(target, update)) {
            verifyArchive(target, update)
            onProgress(100)
            return target
        }
        target.delete()

        val part = File(dir, target.name + ".part")
        try {
            Http.panel.newCall(Request.Builder().url(update.apkUrl).header("Cache-Control", "no-cache").build()).execute().use { resp ->
                if (!resp.isSuccessful) fail(FailureKind.OFFLINE, "The update server answered with error ${resp.code}.")
                val body = resp.body ?: fail(FailureKind.OFFLINE, "The update server sent an empty file.")
                val size = if (update.size > 0) update.size else body.contentLength()
                if (size > 0 && dir.usableSpace < UpdateLogic.requiredBytes(size)) {
                    val mb = UpdateLogic.requiredBytes(size) / (1024 * 1024)
                    fail(FailureKind.STORAGE, "Not enough free storage. About $mb MB is needed for this update.")
                }
                val md = MessageDigest.getInstance("SHA-256")
                var done = 0L
                var last = -1
                body.byteStream().use { input ->
                    part.outputStream().use { out ->
                        val buf = ByteArray(64 * 1024)
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            md.update(buf, 0, n)
                            out.write(buf, 0, n)
                            done += n
                            if (size > 0) {
                                val p = (done * 100 / size).toInt().coerceIn(0, 100)
                                if (p != last) {
                                    last = p
                                    onProgress(p)
                                }
                            }
                        }
                    }
                }
                val digest = md.digest().toHex()
                if (update.sha256.length == 64 && digest != update.sha256) {
                    part.delete()
                    fail(FailureKind.INTEGRITY, "The download was corrupted (checksum mismatch). Please try again.")
                }
            }
        } catch (e: UpdateException) {
            part.delete()
            throw e
        } catch (e: IOException) {
            part.delete()
            if (dir.usableSpace < 8L * 1024 * 1024) fail(FailureKind.STORAGE, "The device ran out of storage during the download.")
            fail(FailureKind.OFFLINE, "The download was interrupted. Check the internet connection and try again.")
        }
        if (!part.renameTo(target)) fail(FailureKind.STORAGE, "The update could not be saved.")
        try {
            verifyArchive(target, update)
        } catch (e: UpdateException) {
            target.delete()
            throw e
        }
        return target
    }

    private fun checksumMatches(file: File, update: UpdateInfo): Boolean {
        if (update.sha256.length != 64) return false
        val md = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                md.update(buf, 0, n)
            }
        }
        return md.digest().toHex() == update.sha256
    }

    @Suppress("DEPRECATION")
    private fun verifyArchive(apk: File, update: UpdateInfo) {
        val pm = context.packageManager
        val flags = if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES
        val archive = pm.getPackageArchiveInfo(apk.absolutePath, flags)
        val installed = pm.getPackageInfo(context.packageName, flags)
        val failure = UpdateLogic.verifyIdentity(
            expectedPackage = context.packageName,
            installedVersionCode = BuildConfig.VERSION_CODE.toLong(),
            installedSigner = signerSha256(installed),
            update = update,
            archivePackage = archive?.packageName,
            archiveVersionCode = archive?.let { if (Build.VERSION.SDK_INT >= 28) it.longVersionCode else it.versionCode.toLong() } ?: 0,
            // Older Android versions may not expose an archive's signers; the system
            // installer still refuses an update signed with a different key.
            archiveSigner = archive?.let(::signerSha256),
        )
        if (failure != null) throw UpdateException(failure)
    }

    @Suppress("DEPRECATION")
    private fun signerSha256(info: PackageInfo): String? {
        val signatures = if (Build.VERSION.SDK_INT >= 28) info.signingInfo?.apkContentsSigners else info.signatures
        val first = signatures?.firstOrNull() ?: return null
        return sha256Hex(first.toByteArray())
    }

    /** Streams the verified APK into an installer session; Android then shows its confirmation. */
    @Throws(UpdateException::class)
    fun install(apk: File) {
        try {
            val installer = context.packageManager.packageInstaller
            val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
            params.setAppPackageName(context.packageName)
            params.setSize(apk.length())
            val id = installer.createSession(params)
            installer.openSession(id).use { session ->
                session.openWrite("NimerTV.apk", 0, apk.length()).use { out ->
                    apk.inputStream().use { it.copyTo(out, 64 * 1024) }
                    session.fsync(out)
                }
                val intent = Intent(context, InstallReceiver::class.java)
                val flags = PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
                InstallEvents.status.value = InstallStatus.AwaitingConfirmation
                session.commit(PendingIntent.getBroadcast(context, id, intent, flags).intentSender)
            }
        } catch (e: Exception) {
            InstallEvents.status.value = InstallStatus.Idle
            val storage = e is IOException && dir.usableSpace < apk.length()
            fail(
                if (storage) FailureKind.STORAGE else FailureKind.INSTALL,
                if (storage) "Not enough free storage to install the update." else "The installer could not be started: ${e.message}",
            )
        }
    }

    private fun fail(kind: FailureKind, message: String): Nothing = throw UpdateException(UpdateFailure(kind, message))
}

/** Receives PackageInstaller results and opens Android's own confirmation screen. */
class InstallReceiver : BroadcastReceiver() {
    @Suppress("DEPRECATION")
    override fun onReceive(context: Context, intent: Intent) {
        when (val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                val confirm: Intent? = if (Build.VERSION.SDK_INT >= 33) {
                    intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
                } else {
                    intent.getParcelableExtra(Intent.EXTRA_INTENT)
                }
                if (confirm != null) {
                    confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    try {
                        context.startActivity(confirm)
                    } catch (e: Exception) {
                        InstallEvents.status.value = InstallStatus.Failed("Android could not open the installer on this device.")
                    }
                }
            }
            PackageInstaller.STATUS_SUCCESS -> InstallEvents.status.value = InstallStatus.Idle
            PackageInstaller.STATUS_FAILURE_ABORTED -> InstallEvents.status.value = InstallStatus.Cancelled
            else -> InstallEvents.status.value = InstallStatus.Failed(
                when (status) {
                    PackageInstaller.STATUS_FAILURE_STORAGE -> "Not enough free storage to install the update."
                    PackageInstaller.STATUS_FAILURE_CONFLICT, PackageInstaller.STATUS_FAILURE_INCOMPATIBLE ->
                        "This update is not compatible with the installed app."
                    PackageInstaller.STATUS_FAILURE_BLOCKED -> "Installation was blocked by the device."
                    PackageInstaller.STATUS_FAILURE_INVALID -> "Android rejected the update file."
                    else -> intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: "The update was not installed."
                },
            )
        }
    }
}
