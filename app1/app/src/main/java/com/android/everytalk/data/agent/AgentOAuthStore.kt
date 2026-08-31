package com.android.everytalk.data.agent

import com.android.everytalk.data.computer.ComputerCredentialStore
import com.android.everytalk.data.database.daos.AgentDao
import com.android.everytalk.data.database.entities.AgentOAuthStateEntity
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.UUID

interface OAuthVerifierStore {
    suspend fun save(reference: String, verifier: CharArray)
    suspend fun load(reference: String): CharArray?
    suspend fun delete(reference: String)
}

class ComputerOAuthVerifierStore(private val credentials: ComputerCredentialStore) : OAuthVerifierStore {
    override suspend fun save(reference: String, verifier: CharArray) = credentials.saveOAuthVerifier(reference, verifier)
    override suspend fun load(reference: String): CharArray? = credentials.loadOAuthVerifier(reference)
    override suspend fun delete(reference: String) = credentials.deleteOAuthVerifier(reference)
}

data class OAuthAuthorizationRequest(
    val state: String,
    val codeChallenge: String,
    val codeChallengeMethod: String = "S256",
)

data class ClaimedOAuthCallback(
    val metadata: AgentOAuthStateEntity,
    /** 只交给可信 token exchange Adapter，使用后必须清零。 */
    val codeVerifier: CharArray,
)

/** OAuth state、PKCE verifier 和 callback 单次消费的可信本地实现。 */
class AgentOAuthStore(
    private val dao: AgentDao,
    private val verifierStore: OAuthVerifierStore,
    private val random: SecureRandom = SecureRandom(),
) {
    suspend fun create(
        runId: String,
        runGeneration: Long,
        capability: String,
        targetBinding: String,
        clientId: String,
        redirectUri: String,
        verifierGeneration: Long,
        ttlMillis: Long,
        now: Long = System.currentTimeMillis(),
    ): OAuthAuthorizationRequest {
        require(ttlMillis in 1..OAUTH_MAX_TTL_MILLIS) { "OAuth TTL 无效" }
        val state = randomUrlToken(32)
        val verifierText = randomUrlToken(48)
        val verifier = verifierText.toCharArray()
        val reference = UUID.randomUUID().toString()
        val challenge = base64Url(sha256(verifierText.toByteArray(Charsets.US_ASCII)))
        try {
            verifierStore.save(reference, verifier)
            check(
                dao.insertOAuthStateIfRunActive(
                    stateHash = hex(sha256(state.toByteArray(Charsets.US_ASCII))),
                    runId = runId,
                    runGeneration = runGeneration,
                    capability = capability,
                    targetBinding = targetBinding,
                    clientId = clientId,
                    redirectUri = redirectUri,
                    verifierReference = reference,
                    verifierGeneration = verifierGeneration,
                    issuedAt = now,
                    expiresAt = now + ttlMillis,
                ) != -1L,
            ) { "OAuth state 创建时 AgentRun 已终止或 generation 已变化" }
            return OAuthAuthorizationRequest(state, challenge)
        } catch (error: Throwable) {
            runCatching { verifierStore.delete(reference) }
            throw error
        } finally {
            verifier.fill('\u0000')
        }
    }

    suspend fun claimCallback(
        state: String,
        expectedCapability: String,
        expectedTargetBinding: String,
        clientId: String,
        redirectUri: String,
        verifierGeneration: Long,
        now: Long = System.currentTimeMillis(),
    ): ClaimedOAuthCallback? {
        val stateHash = hex(sha256(state.toByteArray(Charsets.US_ASCII)))
        val metadata = dao.getOAuthState(stateHash) ?: return null
        if (metadata.capability != expectedCapability || metadata.targetBinding != expectedTargetBinding) return null
        val attemptId = UUID.randomUUID().toString()
        if (dao.claimOAuthCallback(stateHash, metadata.runGeneration, clientId, redirectUri, verifierGeneration, attemptId, now) != 1) return null
        val verifier = verifierStore.load(metadata.verifierReference)
        if (verifier == null) {
            verifierStore.delete(metadata.verifierReference)
            dao.deleteConsumedOAuthState(stateHash)
            return null
        }
        return ClaimedOAuthCallback(metadata.copy(consumed = true, callbackAttemptId = attemptId), verifier)
    }

    suspend fun cleanup(callback: ClaimedOAuthCallback) {
        callback.codeVerifier.fill('\u0000')
        verifierStore.delete(callback.metadata.verifierReference)
        dao.deleteConsumedOAuthState(callback.metadata.stateHash)
    }

    private fun randomUrlToken(bytes: Int): String = ByteArray(bytes).also(random::nextBytes).let(::base64Url)
    private fun sha256(value: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(value)
    private fun base64Url(value: ByteArray): String = Base64.getUrlEncoder().withoutPadding().encodeToString(value)
    private fun hex(value: ByteArray): String = value.joinToString("") { "%02x".format(it) }

    private companion object {
        const val OAUTH_MAX_TTL_MILLIS = 15 * 60 * 1000L
    }
}
