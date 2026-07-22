package com.maktabah.ui.settings

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Alignment
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.maktabah.R

@Composable
fun SettingsDialog(
    onDismiss: () -> Unit,
    onNavigateToCloudKit: () -> Unit,
    onCheckForUpdates: () -> Unit,
    viewModel: SettingsViewModel = viewModel()
) {
    val context = LocalContext.current
    val hasToken by viewModel.hasToken.collectAsState()

    LaunchedEffect(Unit) {
        viewModel.initialize(context)
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.history_settings_title)) },
        containerColor = MaterialTheme.colorScheme.surface,
        text = {
            Column {
                Text(
                    text = stringResource(R.string.history_settings_cloudkit),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = stringResource(
                        R.string.history_settings_signed_in,
                        if (hasToken) (stringResource(R.string.history_settings_connected))
                        else stringResource(R.string.history_settings_not_connected)
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(modifier = Modifier.height(16.dp))
                Button(
                    onClick = {
                        onDismiss()
                        onNavigateToCloudKit()
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        if (hasToken) stringResource(R.string.history_settings_change_account)
                        else stringResource(R.string.history_settings_sign_in),
                    )
                }

                if (hasToken) {
                    Button(
                        onClick = {
                            viewModel.logoutCloudKit(context)
                        },
                        modifier = Modifier.fillMaxWidth(),
                        colors = androidx.compose.material3.ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.errorContainer,
                            contentColor = MaterialTheme.colorScheme.onErrorContainer
                        )
                    ) {
                        Text(stringResource(R.string.history_settings_sign_out))
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                }

                // Debug Options Section
                Spacer(modifier = Modifier.height(24.dp))
                Text(
                    text = stringResource(R.string.history_settings_debug_options),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(8.dp))
                Button(
                    onClick = {
                        viewModel.resetDonationStatus(context) {
                            onDismiss()
                            Toast.makeText(
                                context,
                                context.getString(R.string.history_settings_donation_reset_success),
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                    },
                    colors = androidx.compose.material3.ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                        contentColor = MaterialTheme.colorScheme.onError
                    ),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(stringResource(R.string.history_settings_reset_donation))
                }
            }
        },
        confirmButton = {
            Row(
                horizontalArrangement = Arrangement.spacedBy(2.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextButton(
                    onClick = onCheckForUpdates,
                    contentPadding = PaddingValues(4.dp),
                    modifier = Modifier.defaultMinSize(minWidth = 1.dp, minHeight = 1.dp)
                ) {
                    Text(stringResource(R.string.history_settings_check_update))
                }
                TextButton(
                    onClick = onDismiss,
                    contentPadding = PaddingValues(4.dp),
                    modifier = Modifier.defaultMinSize(minWidth = 1.dp, minHeight = 1.dp)
                ) {
                    Text(stringResource(R.string.reader_tabs_close))
                }
            }
        },
    )
}
