package io.github.dovecoteescapee.byedpi.security

import android.content.Context
import android.content.pm.PackageManager
import android.content.pm.Signature
import android.os.Build
import android.os.SystemClock
import android.util.Log
import io.github.dovecoteescapee.byedpi.utility.getPreferences
import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

object TamperGuard {
    private const val TAG = "TamperGuard"

    // Official debug/release keystore SHA-256 fingerprint (androiddebugkey)
    const val OFFICIAL_CERT_SHA256 = "7D70DBDC42000A7153B73607C5CAC284735C52206D00AA9FDA9650C2279B7502"
    const val OFFICIAL_PACKAGE_NAME = "com.byedpifork.russia"

    private const val PREF_TAMPER_ACK_TOKEN = "security_tamper_ack_token_v135"
    private const val HMAC_SECRET_SALT = "BYEDPI_RUSSIA_TAMPER_SALT_2026_SECURE_GUARD_V135"

    // Runtime state
    @Volatile
    private var isSessionUnlocked: Boolean = false

    @Volatile
    private var activeChallengeToken: String? = null

    @Volatile
    private var challengeStartTime: Long = 0L

    /**
     * Retrieves the SHA-256 fingerprint of the current APK signing certificate.
     */
    fun getCertificateSha256(context: Context): String {
        return try {
            val pm = context.packageManager
            val packageName = context.packageName
            val signatures: Array<Signature>? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                val packageInfo = pm.getPackageInfo(packageName, PackageManager.GET_SIGNING_CERTIFICATES)
                packageInfo.signingInfo?.apkContentsSigners
            } else {
                @Suppress("DEPRECATION")
                val packageInfo = pm.getPackageInfo(packageName, PackageManager.GET_SIGNATURES)
                @Suppress("DEPRECATION")
                packageInfo.signatures
            }

            if (signatures.isNullOrEmpty()) {
                Log.e(TAG, "No signatures found for package: $packageName")
                return ""
            }

            val md = MessageDigest.getInstance("SHA-256")
            val digest = md.digest(signatures[0].toByteArray())
            digest.joinToString("") { "%02X".format(it) }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get certificate fingerprint", e)
            ""
        }
    }

    /**
     * Checks if the app is officially signed and has the expected package name.
     */
    fun isOfficialApp(context: Context): Boolean {
        val currentCert = getCertificateSha256(context)
        val currentPkg = context.packageName

        val isCertOfficial = currentCert.equals(OFFICIAL_CERT_SHA256, ignoreCase = true)
        val isPkgOfficial = currentPkg == OFFICIAL_PACKAGE_NAME

        return isCertOfficial && isPkgOfficial
    }

    /**
     * Returns true if the application has been modified or re-signed.
     */
    fun isModified(context: Context): Boolean {
        return !isOfficialApp(context)
    }

    /**
     * Starts a banner display challenge.
     * Records the monotonic hardware clock timestamp.
     */
    @Synchronized
    fun startBannerChallenge(): String {
        challengeStartTime = SystemClock.elapsedRealtime()
        val token = "CHALLENGE_${challengeStartTime}_${(100000..999999).random()}"
        activeChallengeToken = token
        return token
    }

    /**
     * Validates that the banner was displayed and the required 7-second countdown completed.
     * If valid, persists an HMAC token and unlocks the session.
     */
    @Synchronized
    fun acknowledgeBanner(context: Context, challengeToken: String): Boolean {
        val now = SystemClock.elapsedRealtime()
        val elapsed = now - challengeStartTime

        // Strict 7-second (>= 6900 ms) real hardware clock enforcement
        if (challengeToken != activeChallengeToken || elapsed < 6900L) {
            Log.e(TAG, "Anti-tamper timer challenge violation: elapsed=$elapsed ms, expected >= 7000 ms")
            isSessionUnlocked = false
            return false
        }

        val hmacToken = generateAckHmac(context)
        context.getPreferences().edit()
            .putString(PREF_TAMPER_ACK_TOKEN, hmacToken)
            .apply()

        isSessionUnlocked = true
        activeChallengeToken = null
        Log.i(TAG, "Modified build warning acknowledged successfully. Execution unlocked.")
        return true
    }

    /**
     * Verifies if execution of VPN / proxy services is permitted.
     * Core defense against modders cutting out the banner.
     */
    fun verifyExecutionPermitted(context: Context): Boolean {
        // Fast path: Official app check
        val currentCert = getCertificateSha256(context)
        if (currentCert.equals(OFFICIAL_CERT_SHA256, ignoreCase = true) &&
            context.packageName == OFFICIAL_PACKAGE_NAME
        ) {
            return true
        }

        // For modified builds: Must be unlocked either in memory or via valid HMAC token
        if (isSessionUnlocked) {
            return true
        }

        if (hasValidPersistentAck(context)) {
            isSessionUnlocked = true
            return true
        }

        Log.w(TAG, "Execution denied: modified build detected without banner acknowledgment.")
        return false
    }

    /**
     * Validates the persistent HMAC acknowledgment token.
     */
    fun hasValidPersistentAck(context: Context): Boolean {
        val savedToken = context.getPreferences().getString(PREF_TAMPER_ACK_TOKEN, null) ?: return false
        val expectedToken = generateAckHmac(context)
        return savedToken.isNotEmpty() && savedToken == expectedToken
    }

    /**
     * Generates a cryptographic HMAC of package identity, install time, and cert fingerprint.
     */
    private fun generateAckHmac(context: Context): String {
        return try {
            val cert = getCertificateSha256(context)
            val pkg = context.packageName
            val firstInstallTime = try {
                context.packageManager.getPackageInfo(pkg, 0).firstInstallTime
            } catch (e: Exception) {
                0L
            }

            val rawData = "$pkg|$firstInstallTime|$cert|$HMAC_SECRET_SALT"
            val hmac = Mac.getInstance("HmacSHA256")
            val keySpec = SecretKeySpec(HMAC_SECRET_SALT.toByteArray(), "HmacSHA256")
            hmac.init(keySpec)
            val result = hmac.doFinal(rawData.toByteArray())
            result.joinToString("") { "%02X".format(it) }
        } catch (e: Exception) {
            Log.e(TAG, "HMAC generation failed", e)
            ""
        }
    }
}
