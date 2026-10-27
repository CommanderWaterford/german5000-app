package com.vocab5000.core.billing

import java.security.KeyFactory
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import java.util.Base64

enum class VerificationSource { SIGNATURE, BILLING_RESPONSE }

sealed interface VerificationResult {
    data class Verified(val source: VerificationSource) : VerificationResult
    data object Invalid : VerificationResult
}

/**
 * Client-side purchase verification (spec §12.2). Without a server this offers weaker
 * fraud protection; that limitation is accepted and documented. If a Play licensing key
 * is configured, the purchase signature is checked; otherwise the Billing response is
 * trusted. No service-account credentials are ever shipped in the app.
 */
class PurchaseVerifier(private val base64PublicKey: String) {
    fun verify(record: PurchaseRecord): VerificationResult {
        if (base64PublicKey.isBlank()) return VerificationResult.Verified(VerificationSource.BILLING_RESPONSE)
        return try {
            val key = KeyFactory.getInstance("RSA").generatePublic(X509EncodedKeySpec(Base64.getDecoder().decode(base64PublicKey)))
            val verifier = Signature.getInstance("SHA1withRSA")
            verifier.initVerify(key)
            verifier.update(record.originalJson.toByteArray(Charsets.UTF_8))
            if (verifier.verify(Base64.getDecoder().decode(record.signature))) {
                VerificationResult.Verified(VerificationSource.SIGNATURE)
            } else {
                VerificationResult.Invalid
            }
        } catch (e: Exception) {
            VerificationResult.Invalid
        }
    }
}
