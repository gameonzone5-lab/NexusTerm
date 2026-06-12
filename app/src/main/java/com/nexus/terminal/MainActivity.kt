package com.nexus.terminal

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.nexus.terminal.ui.theme.NexusTerminalTheme
import com.google.android.gms.ads.MobileAds
import com.google.android.gms.ads.AdRequest
import androidx.compose.ui.viewinterop.AndroidView
import com.google.android.gms.ads.AdSize
import com.google.android.gms.ads.AdView

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // Initialize Mobile Ads SDK
        MobileAds.initialize(this) {}

        setContent {
            NexusTerminalTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    MainScreen()
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen() {
    var selectedTab by remember { mutableStateOf(0) }
    val tabs = listOf("Terminal", "Editor", "Tools", "Settings")

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("NexusTerminal Pro") },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    titleContentColor = MaterialTheme.colorScheme.primary,
                )
            )
        },
        bottomBar = {
            Column {
                // Banner Ad at the bottom
                BannerAd()
                
                NavigationBar {
                    tabs.forEachIndexed { index, title ->
                        NavigationBarItem(
                            selected = selectedTab == index,
                            onClick = { selectedTab = index },
                            label = { Text(title) },
                            icon = { /* Add Icons here */ }
                        )
                    }
                }
            }
        }
    ) { innerPadding ->
        Column(modifier = Modifier.padding(innerPadding)) {
            when (selectedTab) {
                0 -> TerminalView()
                1 -> EditorView()
                2 -> ToolsView()
                3 -> SettingsView()
            }
        }
    }
}

@Composable
fun TerminalView() {
    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Text("Terminal Emulator", style = MaterialTheme.typography.headlineSmall)
        Spacer(modifier = Modifier.height(8.dp))
        // Placeholder for Terminal View
        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            Text("Terminal Engine Initializing...\n$ nexus_terminal > _", 
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
fun EditorView() {
    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Text("Code Editor", style = MaterialTheme.typography.headlineSmall)
        Spacer(modifier = Modifier.height(8.dp))
        Text("Built-in editor with syntax highlighting.")
    }
}

@Composable
fun ToolsView() {
    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Text("Quick Install Tools", style = MaterialTheme.typography.headlineSmall)
        Spacer(modifier = Modifier.height(8.dp))
        Button(onClick = { /* Install Python */ }) { Text("Install Python") }
        Button(onClick = { /* Install Node.js */ }) { Text("Install Node.js") }
        Button(onClick = { /* Install Git */ }) { Text("Install Git") }
    }
}

@Composable
fun SettingsView() {
    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Text("Settings", style = MaterialTheme.typography.headlineSmall)
        // Add settings like themes, font size, etc.
    }
}

@Composable
fun BannerAd() {
    AndroidView(
        modifier = Modifier.fillMaxWidth(),
        factory = { context ->
            AdView(context).apply {
                setAdSize(AdSize.BANNER)
                adUnitId = "ca-app-pub-3940256099942544/6300978111" // Test ID
                loadAd(AdRequest.Builder().build())
            }
        }
    )
}
