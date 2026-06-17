package com.realmanishrai.zero_bezel

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.realmanishrai.zero_bezel.client.ClientScreen
import com.realmanishrai.zero_bezel.host.HostScreen
import com.realmanishrai.zero_bezel.ui.theme.ZerobezelTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            ZerobezelTheme {
                ScreenExtenderApp()
            }
        }
    }
}

@Composable
private fun ScreenExtenderApp() {
    val navController = rememberNavController()

    NavHost(
        navController = navController,
        startDestination = AppRoute.Entry.name
    ) {
        composable(AppRoute.Entry.name) {
            EntryScreen(
                onHostClick = { navController.navigate(AppRoute.Host.name) },
                onClientClick = { navController.navigate(AppRoute.Client.name) }
            )
        }
        composable(AppRoute.Host.name) {
            HostScreen(onBack = { navController.popBackStack() })
        }
        composable(AppRoute.Client.name) {
            ClientScreen(onBack = { navController.popBackStack() })
        }
    }
}

private enum class AppRoute {
    Entry,
    Host,
    Client
}

@Composable
private fun EntryScreen(
    onHostClick: () -> Unit,
    onClientClick: () -> Unit
) {
    Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text(
                text = "ScreenExtender",
                style = MaterialTheme.typography.headlineLarge,
                fontWeight = FontWeight.Bold
            )

            Text(
                text = "Milestone 1: Network Handshake",
                modifier = Modifier.padding(top = 8.dp, bottom = 40.dp),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Button(
                onClick = onHostClick,
                modifier = Modifier
                    .fillMaxWidth()
                    .sizeIn(minHeight = 64.dp),
                contentPadding = PaddingValues(18.dp)
            ) {
                Text("Be the Host", style = MaterialTheme.typography.titleMedium)
            }

            Button(
                onClick = onClientClick,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 16.dp)
                    .sizeIn(minHeight = 64.dp),
                contentPadding = PaddingValues(18.dp)
            ) {
                Text("Be the Client", style = MaterialTheme.typography.titleMedium)
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun EntryScreenPreview() {
    ZerobezelTheme {
        EntryScreen(
            onHostClick = {},
            onClientClick = {}
        )
    }
}
