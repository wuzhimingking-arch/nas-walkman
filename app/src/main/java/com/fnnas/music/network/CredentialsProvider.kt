package com.fnnas.music.network

import com.fnnas.music.data.db.NasDao
import com.fnnas.music.security.CredentialCipher
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
                baseUrl = server.baseUrl,
                username = server.username,
                password = credentialCipher.decrypt(server.encryptedPassword),
                musicRootPath = server.musicRootPath,
            )
        }
    }
}
