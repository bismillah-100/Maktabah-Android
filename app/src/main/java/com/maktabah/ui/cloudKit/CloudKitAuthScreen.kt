package com.maktabah.ui.cloudKit

import android.annotation.SuppressLint
import android.content.Context
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.edit
import androidx.core.net.toUri
import com.maktabah.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

@OptIn(ExperimentalMaterial3Api::class)
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun CloudKitAuthScreen(
    containerId: String,
    environment: String = if (com.maktabah.BuildConfig.DEBUG) "development" else "production",
    apiToken: String,
    onTokenReceived: (String) -> Unit,
    onBack: () -> Unit
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val authUrl = "https://api.apple-cloudkit.com/database/1/$containerId/$environment/private/users/caller?ckAPIToken=$apiToken"
    var redirectUrl by remember { mutableStateOf<String?>(null) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(authUrl) {
        withContext(Dispatchers.IO) {
            try {
                val url = URL(authUrl)
                val connection = url.openConnection() as HttpURLConnection
                connection.requestMethod = "GET"
                
                val responseCode = connection.responseCode
                val inputStream = if (responseCode in 200..299) {
                    connection.inputStream
                } else {
                    connection.errorStream
                }
                
                val responseString = inputStream?.bufferedReader()?.use { it.readText() }
                if (responseString != null) {
                    val jsonObject = JSONObject(responseString)
                    if (jsonObject.has("redirectURL")) {
                        redirectUrl = jsonObject.getString("redirectURL")
                    } else if (responseCode in 200..299) {
                        errorMessage = context.getString(R.string.cloudkit_error_auth_redirect)
                    } else {
                        errorMessage = context.getString(R.string.cloudkit_error_prefix, responseString)
                    }
                } else {
                    errorMessage = context.getString(R.string.cloudkit_error_empty)
                }
            } catch (e: Exception) {
                errorMessage = e.message
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.cloudkit_auth_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.reader_action_back))
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
        ) {
            if (redirectUrl != null) {
                AndroidView(
                    modifier = Modifier.fillMaxSize(),
                    factory = { context ->
                        WebView(context).apply {
                            settings.javaScriptEnabled = true
                            settings.domStorageEnabled = true

                            webViewClient = object : WebViewClient() {
                                private fun handleUrl(url: String?): Boolean {
                                    if (url != null && url.contains("ckWebAuthToken=")) {
                                        val uri = url.toUri()
                                        val token = uri.getQueryParameter("ckWebAuthToken")
                                        if (token != null) {
                                            val prefs = context.getSharedPreferences("MaktabahPrefs", Context.MODE_PRIVATE)
                                            prefs.edit { putString("ckWebAuthToken", token) }

                                            onTokenReceived(token)
                                            return true
                                        }
                                    }
                                    return false
                                }

                                override fun shouldOverrideUrlLoading(
                                    view: WebView?,
                                    request: WebResourceRequest?
                                ): Boolean {
                                    return handleUrl(request?.url.toString())
                                }

                                override fun onPageStarted(
                                    view: WebView?,
                                    url: String?,
                                    favicon: android.graphics.Bitmap?
                                ) {
                                    handleUrl(url)
                                    super.onPageStarted(view, url, favicon)
                                }

                                override fun onPageFinished(view: WebView?, url: String?) {
                                    super.onPageFinished(view, url)
                                }
                            }
                            loadUrl(redirectUrl!!)
                        }
                    }
                )
            } else if (errorMessage != null) {
                Text(stringResource(R.string.cloudkit_error_load_failed, errorMessage!!), modifier = Modifier.padding(16.dp))
            } else {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator()
                }
            }
        }
    }
}
