@file:OptIn(androidx.tv.material3.ExperimentalTvMaterial3Api::class)

package com.nuvio.tv.ui.screens.account

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.tv.material3.*
import com.nuvio.tv.R
import com.nuvio.tv.domain.model.AuthState
import com.nuvio.tv.ui.theme.NuvioColors
import com.nuvio.tv.ui.theme.NuvioTheme

@Composable
fun AuthSignInScreen(
    onBackPress: () -> Unit = {},
    onSuccess: () -> Unit = {},
    viewModel: AccountViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }

    BackHandler { onBackPress() }
    LaunchedEffect(uiState.authState) {
        if (uiState.authState is AuthState.FullAccount) onSuccess()
    }

    Box(Modifier.fillMaxSize().background(Color(0xFF0D101E)), contentAlignment = Alignment.Center) {
        Column(
            modifier = Modifier.fillMaxWidth(0.5f).widthIn(max = 640.dp)
                .border(1.dp, NuvioColors.Border.copy(alpha = 0.75f), RoundedCornerShape(24.dp))
                .background(Color(0xFF12162A), RoundedCornerShape(24.dp))
                .padding(horizontal = 32.dp, vertical = 36.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Image(
                painter = painterResource(R.drawable.app_logo_wordmark),
                contentDescription = stringResource(R.string.cd_nuvio),
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxWidth(0.72f).height(56.dp)
            )
            Spacer(Modifier.height(26.dp))
            Text(stringResource(R.string.auth_signin_title), style = MaterialTheme.typography.headlineSmall,
                color = NuvioTheme.colors.TextPrimary, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(10.dp))
            Text(stringResource(R.string.auth_signin_panel_subtitle), style = MaterialTheme.typography.bodyMedium,
                color = NuvioTheme.colors.TextSecondary, textAlign = TextAlign.Center)
            Spacer(Modifier.height(18.dp))
            if (uiState.isPortalLoading || uiState.isLoading) {
                Text(stringResource(R.string.auth_signin_loading_services), style = MaterialTheme.typography.bodySmall,
                    color = NuvioColors.TextSecondary, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(18.dp))
            }
            InputField(username, { username = it }, stringResource(R.string.auth_signin_username_placeholder),
                KeyboardType.Text, imeAction = ImeAction.Next)
            Spacer(Modifier.height(12.dp))
            InputField(password, { password = it }, stringResource(R.string.auth_signin_password_placeholder),
                KeyboardType.Password, isPassword = true, imeAction = ImeAction.Done,
                onImeAction = { if (username.isNotBlank() && password.isNotBlank() && !uiState.isLoading) viewModel.signInToPanel(username, password) })
            uiState.error?.takeIf { it.isNotBlank() }?.let {
                Spacer(Modifier.height(12.dp))
                Text(it, style = MaterialTheme.typography.bodySmall, color = Color(0xFFFF8A80), textAlign = TextAlign.Center)
            }
            Spacer(Modifier.height(22.dp))
            Button(
                onClick = { viewModel.signInToPanel(username, password) },
                enabled = username.isNotBlank() && password.isNotBlank() && !uiState.isLoading,
                colors = ButtonDefaults.colors(containerColor = NuvioTheme.colors.Secondary,
                    focusedContainerColor = NuvioTheme.colors.SecondaryVariant,
                    contentColor = NuvioTheme.colors.OnSecondary,
                    focusedContentColor = NuvioTheme.colors.OnSecondaryVariant),
                shape = ButtonDefaults.shape(RoundedCornerShape(50)), modifier = Modifier.fillMaxWidth()
            ) {
                Text(if (uiState.isLoading) stringResource(R.string.auth_signin_signing_in) else stringResource(R.string.auth_signin_panel_btn),
                    modifier = Modifier.padding(vertical = 4.dp), fontWeight = FontWeight.Medium)
            }
        }
    }
}
