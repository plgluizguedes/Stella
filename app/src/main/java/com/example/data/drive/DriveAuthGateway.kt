package com.example.data.drive

import android.content.Context
import android.util.Log
import com.google.android.gms.auth.GoogleAuthException
import com.google.android.gms.auth.GoogleAuthUtil
import com.google.android.gms.auth.UserRecoverableAuthException
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInAccount
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.common.api.Scope
import java.io.IOException

/**
 * Abstração que centraliza as operações de autenticação do Google Drive:
 * - Conta atualmente conectada
 * - Obtenção de token OAuth2
 * - Invalidação exata de token no 401
 * - Verificação de escopo/consentimento
 * - Encerramento de sessão (signOut)
 */
interface DriveAuthGateway {
    fun getConnectedAccount(): DriveAccountInfo?
    fun hasRequiredScope(): Boolean
    fun checkAccountHasScope(account: GoogleSignInAccount): Boolean = false

    @Throws(UserRecoverableAuthException::class, GoogleAuthException::class, IOException::class)
    fun getToken(): String

    fun invalidateToken(token: String)
    fun signOut(onComplete: () -> Unit = {})
}

class DefaultDriveAuthGateway(
    private val context: Context,
    private val tokenProvider: (() -> String)? = null,
    private val accountProvider: (() -> DriveAccountInfo?)? = null,
    private val onTokenInvalidated: ((String) -> Unit)? = null
) : DriveAuthGateway {

    override fun getConnectedAccount(): DriveAccountInfo? {
        if (accountProvider != null) {
            return accountProvider.invoke()
        }
        val account = try {
            GoogleSignIn.getLastSignedInAccount(context)
        } catch (e: Exception) {
            Log.w(TAG, "Erro ao obter conta Google conectada: ${e.message}")
            null
        } ?: return null

        val email = account.email ?: return null
        val hasScope = GoogleSignIn.hasPermissions(account, Scope(GoogleDriveSyncManager.SCOPE_DRIVE_FILE))
        return DriveAccountInfo(email = email, hasDriveScope = hasScope)
    }

    override fun hasRequiredScope(): Boolean {
        return getConnectedAccount()?.hasDriveScope == true
    }

    override fun checkAccountHasScope(account: GoogleSignInAccount): Boolean {
        return GoogleSignIn.hasPermissions(account, Scope(GoogleDriveSyncManager.SCOPE_DRIVE_FILE))
    }

    @Throws(UserRecoverableAuthException::class, GoogleAuthException::class, IOException::class)
    override fun getToken(): String {
        tokenProvider?.let { return it() }

        val account = GoogleSignIn.getLastSignedInAccount(context)
            ?: throw IllegalStateException("Nenhuma conta Google conectada. Faça login com o Google para acessar o Drive.")

        val googleAccount = account.account
            ?: throw IllegalStateException("Conta Google inválida ou inacessível no dispositivo.")

        // Preserva UserRecoverableAuthException, GoogleAuthException e IOException sem conversão indiscriminada
        return GoogleAuthUtil.getToken(context, googleAccount, "oauth2:${GoogleDriveSyncManager.SCOPE_DRIVE_FILE}")
            ?: throw IllegalStateException("Falha na obtenção do token de autenticação: token nulo retornado.")
    }

    override fun invalidateToken(token: String) {
        if (token.isBlank()) return
        onTokenInvalidated?.invoke(token)
        if (tokenProvider != null) {
            return
        }
        try {
            GoogleAuthUtil.clearToken(context, token)
            Log.d(TAG, "Token invalidado com sucesso no GoogleAuthUtil")
        } catch (e: Exception) {
            Log.w(TAG, "Falha ao invalidar token no GoogleAuthUtil: ${e.message}")
        }
    }

    override fun signOut(onComplete: () -> Unit) {
        try {
            val gso = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
                .requestEmail()
                .requestScopes(Scope(GoogleDriveSyncManager.SCOPE_DRIVE_FILE))
                .build()
            GoogleSignIn.getClient(context, gso).signOut().addOnCompleteListener {
                onComplete()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Erro ao realizar signOut: ${e.message}")
            onComplete()
        }
    }

    companion object {
        private const val TAG = "DriveAuthGateway"
    }
}
