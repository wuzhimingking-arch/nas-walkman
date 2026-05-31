package tech.peakedge.naswalkman.network

import tech.peakedge.naswalkman.data.db.NasDao
import tech.peakedge.naswalkman.security.CredentialCipher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking

data class NasCredentials(
    val serverId: Long,
    val baseUrl: String,
    val username: String,
    val password: String,
    val musicRootPath: String,
)

interface CredentialsProvider {
    fun currentCredentials(): NasCredentials?
}

class FixedCredentialsProvider(private val credentials: NasCredentials) : CredentialsProvider {
    override fun currentCredentials(): NasCredentials = credentials
}

class DatabaseCredentialsProvider(
    private val nasDao: NasDao,
    private val credentialCipher: CredentialCipher,
) : CredentialsProvider {
    override fun currentCredentials(): NasCredentials? = runBlocking(Dispatchers.IO) {
        nasDao.getActiveNas()?.let { server ->
            NasCredentials(
                serverId = server.id,
                baseUrl = server.resolvedBaseUrl.ifBlank { server.baseUrl },
                username = server.username,
                password = credentialCipher.decrypt(server.encryptedPassword),
                musicRootPath = server.selectedMusicRemotePath.ifBlank { server.musicRootPath },
            )
        }
    }
}
