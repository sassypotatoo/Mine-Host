package com.example.ui.screens.profile

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AccountCircle
import androidx.compose.material.icons.outlined.Login
import androidx.compose.material.icons.outlined.Logout
import androidx.compose.material.icons.outlined.Security
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.example.MainViewModel
import com.example.auth.AuthState
import com.example.ui.components.*
import com.example.ui.theme.*

@Composable
fun AccountScreen(viewModel: MainViewModel, onBack: () -> Unit) {
    val context = LocalContext.current
    val authState by viewModel.authState.collectAsState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(MineHostBackgroundTop, MineHostBackgroundBottom)))
            .padding(horizontal = 16.dp),
    ) {
        MineHostBrandHeader(showBack = true, onBack = onBack, compact = true)
        Column(
            modifier = Modifier.weight(1f).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            MineHostPageTitle("Account", "Real Supabase and Google authentication status.")
            GlassCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    PastelIcon(Icons.Outlined.AccountCircle, MineHostBlue, BlueSoft, size = 58.dp)
                    when (val state = authState) {
                        is AuthState.NotConfigured -> {
                            Text("Authentication not configured", style = MaterialTheme.typography.titleLarge)
                            Text("Missing: ${state.missing.joinToString()}", color = MineHostTextSecondary)
                        }
                        AuthState.SignedOut -> {
                            Text("Signed out", style = MaterialTheme.typography.titleLarge)
                            Text("Sign in with Google through the configured Supabase project.", color = MineHostTextSecondary)
                            MineHostButton(
                                text = "Sign in with Google",
                                icon = Icons.Outlined.Login,
                                modifier = Modifier.fillMaxWidth(),
                                onClick = {
                                    viewModel.createGoogleLoginIntent().fold(
                                        onSuccess = { context.startActivity(it) },
                                        onFailure = { viewModel.showMessage(it.message ?: "Unable to start Google sign-in") },
                                    )
                                },
                            )
                        }
                        is AuthState.Authorizing -> {
                            Text("Waiting for Google sign-in…", style = MaterialTheme.typography.titleLarge)
                            Text("Return to MineHost after completing the browser flow.", color = MineHostTextSecondary)
                        }
                        is AuthState.SignedIn -> {
                            Text(state.session.user.displayName ?: state.session.user.email ?: "Signed-in user", style = MaterialTheme.typography.titleLarge)
                            state.session.user.email?.let { Text(it, color = MineHostTextSecondary) }
                            MineHostButton(
                                text = "Sign out",
                                icon = Icons.Outlined.Logout,
                                modifier = Modifier.fillMaxWidth(),
                                outlined = true,
                                onClick = viewModel::signOut,
                            )
                        }
                        is AuthState.Error -> {
                            Text("Authentication error", style = MaterialTheme.typography.titleLarge, color = MineHostRed)
                            Text(state.message, color = MineHostTextSecondary)
                            MineHostButton(
                                text = "Try Google sign-in again",
                                icon = Icons.Outlined.Security,
                                modifier = Modifier.fillMaxWidth(),
                                onClick = {
                                    viewModel.createGoogleLoginIntent().fold(
                                        onSuccess = { context.startActivity(it) },
                                        onFailure = { viewModel.showMessage(it.message ?: "Unable to restart sign-in") },
                                    )
                                },
                            )
                        }
                    }
                }
            }
            Spacer(Modifier.height(20.dp))
        }
    }
}
