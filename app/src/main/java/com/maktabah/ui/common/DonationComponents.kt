package com.maktabah.ui.common

import android.content.Context
import androidx.browser.customtabs.CustomTabsIntent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.core.content.edit
import androidx.core.net.toUri
import com.google.firebase.messaging.FirebaseMessaging
import com.google.firebase.Firebase
import com.google.firebase.messaging.messaging
import com.maktabah.R
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONObject
import java.io.IOException
import java.security.MessageDigest


fun registerFcmToken(context: Context, token: String? = null, email: String? = null) {
    val prefs = context.getSharedPreferences("main_prefs", Context.MODE_PRIVATE)

    // Prioritas: Email dari parameter (saat mau donasi) -> SharedPreferences -> CloudKit
    val finalEmail = email?.lowercase()?.trim()
        ?: email?.lowercase()?.trim()
        ?: prefs.getString("last_donation_email", null)?.lowercase()?.trim()


    if (finalEmail.isNullOrEmpty()) {
        android.util.Log.d("FcmRegister", "Pendaftaran FCM dilewati karena belum ada email.")
        return
    }

    val fcmTokenCallback = { fcmToken: String ->
        val client = OkHttpClient()
        val mediaType = "application/json; charset=utf-8".toMediaType()

        val json = JSONObject().apply {
            put("email", finalEmail)
            put("fcm_token", fcmToken)
        }.toString()

        val requestBody = json.toRequestBody(mediaType)
        val request = Request.Builder()
            .url("${com.maktabah.BuildConfig.CLOUDFLARE_WORKER_URL}/register")
            .post(requestBody)
            .build()

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                android.util.Log.e("FcmRegister", "Failed to register FCM token", e)
            }

            override fun onResponse(call: Call, response: Response) {
                android.util.Log.d(
                    "FcmRegister",
                    "Successfully registered FCM token: ${response.code}"
                )
                response.close()
            }
        })
    }

    if (token != null) {
        fcmTokenCallback(token)
    } else {
        try {
            Firebase.messaging.token.addOnCompleteListener { task ->
                if (task.isSuccessful && task.result != null) {
                    fcmTokenCallback(task.result)
                }
            }
        } catch (e: Exception) {
            android.util.Log.e("FcmRegister", "FirebaseMessaging not initialized yet", e)
        }
    }
}

fun sha256(input: String): String {
    val bytes = MessageDigest.getInstance("SHA-256").digest(input.toByteArray())
    return bytes.joinToString("") { "%02x".format(it) }
}

fun restoreDonationStatus(context: Context, email: String, onResult: (Boolean) -> Unit) {
    val cleanEmail = email.replace("[\u200E\u200F\u202A-\u202E\u2066-\u2069]".toRegex(), "").lowercase().trim()
    val hashedEmail = sha256(cleanEmail)
    val url = "${com.maktabah.BuildConfig.FIREBASE_RTDB_URL}/donations/$hashedEmail.json"

    val client = OkHttpClient()
    val request = Request.Builder().url(url).build()
    val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())

    client.newCall(request).enqueue(object : Callback {
        override fun onFailure(call: Call, e: IOException) {
            mainHandler.post { onResult(false) }
        }

        override fun onResponse(call: Call, response: Response) {
            val body = response.body.string()
            if (response.isSuccessful && body != "null") {
                try {
                    val json = JSONObject(body)
                    val hasDonated = json.optBoolean("has_donated", false)
                    if (hasDonated) {
                        val prefs = context.getSharedPreferences("main_prefs", Context.MODE_PRIVATE)
                        prefs.edit { putBoolean("has_donated", true) }
                        mainHandler.post { onResult(true) }
                        return
                    }
                } catch (e: Exception) {
                    android.util.Log.e("RestoreDonation", "Gagal melakukan parsing respon restore", e)
                }
            }
            mainHandler.post { onResult(false) }
        }
    })
}

@Composable
fun RestoreDonationDialog(onDismiss: () -> Unit, onRestoreSuccess: () -> Unit) {
    val context = LocalContext.current
    var email by remember { mutableStateOf("") }
    var isLoading by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
        AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text(stringResource(R.string.donation_restore_title)) },
            containerColor = MaterialTheme.colorScheme.surface,
            text = {
                Column {
                    Text(stringResource(R.string.donation_restore_desc))
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = email,
                        onValueChange = { email = it },
                        label = { Text(stringResource(R.string.donation_restore_email_label)) },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                        shape = RoundedCornerShape(30.dp)
                    )
                    if (errorMessage != null) {
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            errorMessage!!,
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (email.isBlank()) {
                            errorMessage = context.getString(R.string.donation_restore_empty_email)
                            return@Button
                        }
                        isLoading = true
                        errorMessage = null
                        restoreDonationStatus(context, email) { success ->
                            isLoading = false
                            if (success) {
                                android.widget.Toast.makeText(
                                    context,
                                    context.getString(R.string.donation_restore_success),
                                    android.widget.Toast.LENGTH_SHORT
                                ).show()
                                onRestoreSuccess()
                                onDismiss()
                            } else {
                                errorMessage =
                                    context.getString(R.string.donation_restore_not_found)
                            }
                        }
                    },
                    enabled = !isLoading
                ) {
                    if (isLoading) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(20.dp),
                            color = MaterialTheme.colorScheme.onPrimary
                        )
                    } else {
                        Text(stringResource(R.string.donation_restore_action))
                    }
                }
            },
            dismissButton = {
                TextButton(onClick = onDismiss, enabled = !isLoading) {
                    Text(stringResource(R.string.reader_ann_editor_cancel))
                }
            }
        )
    }


}

// Membuka tautan donasi murni menggunakan email tanpa UUID perangkat
fun openDonationLink(context: Context, email: String) {
    val prefs = context.getSharedPreferences("main_prefs", Context.MODE_PRIVATE)

    val cleanEmail = email.replace("[\u200E\u200F\u202A-\u202E\u2066-\u2069]".toRegex(), "").lowercase().trim()
    if (cleanEmail.isNotEmpty()) {
        prefs.edit { putString("last_donation_email", cleanEmail) }
        // Daftarkan email ini langsung ke Cloudflare KV bersama token FCM sebelum membuka browser
        registerFcmToken(context, email = cleanEmail)
    }

    val uriBuilder = "https://sociabuzz.com/ghoysmawahib/donate".toUri().buildUpon()
    if (cleanEmail.isNotEmpty()) {
        uriBuilder.appendQueryParameter("email", cleanEmail)
    }
    val finalUrl = uriBuilder.build().toString()

    try {
        val customTabsIntent = CustomTabsIntent.Builder()
            .setShowTitle(true)
            .build()
        customTabsIntent.launchUrl(context, finalUrl.toUri())
    } catch (_: Exception) {
        val intent = android.content.Intent(android.content.Intent.ACTION_VIEW, finalUrl.toUri())
        context.startActivity(intent)
    }
}

@Composable
fun EmailDonationDialog(onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("main_prefs", Context.MODE_PRIVATE) }
    var email by remember { mutableStateOf(prefs.getString("last_donation_email", "") ?: "") }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
        AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text(stringResource(R.string.donation_email_dialog_title)) },
            containerColor = MaterialTheme.colorScheme.surface,
            text = {
                Column {
                    Text(stringResource(R.string.donation_email_dialog_desc))
                    Spacer(modifier = Modifier.height(12.dp))
                    OutlinedTextField(
                        value = email,
                        onValueChange = {
                            email = it
                            errorMessage = null
                        },
                        label = { Text(stringResource(R.string.donation_restore_email_label)) },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        isError = errorMessage != null,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                        shape = RoundedCornerShape(30.dp)
                    )
                    if (errorMessage != null) {
                        Text(
                            errorMessage!!,
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(top = 4.dp)
                        )
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (email.isBlank() || !android.util.Patterns.EMAIL_ADDRESS.matcher(email).matches()) {
                            errorMessage = context.getString(R.string.donation_restore_empty_email)
                            return@Button
                        }
                        onConfirm(email)
                        onDismiss()
                    }
                ) {
                    Text(stringResource(R.string.donation_email_dialog_action))
                }
            },
            dismissButton = {
                TextButton(onClick = onDismiss) {
                    Text(stringResource(R.string.reader_ann_editor_cancel))
                }
            }
        )
    }
}

@Composable
fun DonationCard(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var showRestoreDialog by remember { mutableStateOf(false) }
    var showEmailDialog by remember { mutableStateOf(false) }

    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
        Card(
            modifier =
                modifier
                    .fillMaxWidth()
                    .padding(30.dp),
            shape = RoundedCornerShape(30.dp),
            colors =
                CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                ),
            elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
        ) {
            Column(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center,
                ) {
                    Icon(
                        imageVector = Icons.Default.Favorite,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(24.dp),
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = stringResource(R.string.donation_card_title),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                }
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = stringResource(R.string.donation_card_desc),
                    style = MaterialTheme.typography.bodyMedium,
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f),
                    modifier = Modifier.padding(horizontal = 8.dp),
                )
                Spacer(modifier = Modifier.height(12.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center
                ) {
                    Button(
                        onClick = { showEmailDialog = true },
                        colors =
                            ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.primary,
                                contentColor = MaterialTheme.colorScheme.onPrimary,
                            ),
                        shape = RoundedCornerShape(50),
                    ) {
                        Text(
                            text = stringResource(R.string.donation_card_button),
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    TextButton(
                        onClick = {
                            val ckPrefs =
                                context.getSharedPreferences("MaktabahPrefs", Context.MODE_PRIVATE)
                            val email = ckPrefs.getString("ckUserEmail", "") ?: ""
                            if (email.isNotEmpty()) {
                                restoreDonationStatus(context, email) { success ->
                                    if (success) {
                                        android.widget.Toast.makeText(
                                            context,
                                            context.getString(R.string.donation_restore_success),
                                            android.widget.Toast.LENGTH_SHORT
                                        ).show()
                                    } else {
                                        showRestoreDialog = true
                                    }
                                }
                            } else {
                                showRestoreDialog = true
                            }
                        }
                    ) {
                        Text(
                            text = stringResource(R.string.donation_restore_action),
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
            }
        }
    }

    if (showRestoreDialog) {
        RestoreDonationDialog(
            onDismiss = { showRestoreDialog = false },
            onRestoreSuccess = {}
        )
    }

    if (showEmailDialog) {
        EmailDonationDialog(
            onDismiss = { showEmailDialog = false },
            onConfirm = { email ->
                openDonationLink(context, email)
            }
        )
    }
}

@Composable
fun DonationIconButton(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var showEmailDialog by remember { mutableStateOf(false) }

    IconButton(
        onClick = { showEmailDialog = true },
        modifier = modifier,
    ) {
        Icon(
            imageVector = Icons.Default.Favorite,
            contentDescription = stringResource(R.string.donation_action_title),
            tint = MaterialTheme.colorScheme.primary,
        )
    }

    if (showEmailDialog) {
        EmailDonationDialog(
            onDismiss = { showEmailDialog = false },
            onConfirm = { email ->
                openDonationLink(context, email)
            }
        )
    }
}
