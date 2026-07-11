package com.howdoisay.hdis.data

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.howdoisay.hdis.domain.ProviderCredentials

class SecureCredentialStore(context: Context) {
    private val preferences = EncryptedSharedPreferences.create(
        context,
        "hdis.secure.credentials",
        MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
    )

    fun read(): ProviderCredentials = ProviderCredentials(
        asrAppId = preferences.getString(ASR_APP_ID, "").orEmpty(),
        asrAccessToken = preferences.getString(ASR_ACCESS_TOKEN, "").orEmpty(),
        asrResourceId = preferences.getString(ASR_RESOURCE_ID, DEFAULT_ASR_RESOURCE).orEmpty(),
        arkApiKey = preferences.getString(ARK_API_KEY, "").orEmpty(),
        arkEndpointId = preferences.getString(ARK_ENDPOINT_ID, ProviderCredentials.DEFAULT_ARK_MODEL).orEmpty()
    )

    fun save(credentials: ProviderCredentials) {
        preferences.edit()
            .putString(ASR_APP_ID, credentials.asrAppId.trim())
            .putString(ASR_ACCESS_TOKEN, credentials.asrAccessToken.trim())
            .putString(ASR_RESOURCE_ID, credentials.asrResourceId.trim())
            .putString(ARK_API_KEY, credentials.arkApiKey.trim())
            .putString(ARK_ENDPOINT_ID, credentials.arkEndpointId.trim())
            .apply()
    }

    companion object {
        const val DEFAULT_ASR_RESOURCE = "volc.bigasr.auc_turbo"
        private const val ASR_APP_ID = "asr_app_id"
        private const val ASR_ACCESS_TOKEN = "asr_access_token"
        private const val ASR_RESOURCE_ID = "asr_resource_id"
        private const val ARK_API_KEY = "ark_api_key"
        private const val ARK_ENDPOINT_ID = "ark_endpoint_id"
    }
}
