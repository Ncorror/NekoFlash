package io.github.ncorror.nekoflash.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.weight
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.ncorror.nekoflash.core.model.TargetMode

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NekoFlashApp() {
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("NekoFlash")
                        Text(
                            text = "Professional Android host toolkit",
                            style = MaterialTheme.typography.labelMedium,
                        )
                    }
                },
            )
        },
    ) { innerPadding ->
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {
            if (maxWidth >= 840.dp) {
                Row(modifier = Modifier.fillMaxSize()) {
                    ProjectNavigation(
                        modifier = Modifier
                            .width(240.dp)
                            .fillMaxSize(),
                    )
                    Workspace(modifier = Modifier.weight(1f))
                }
            } else {
                Workspace(modifier = Modifier.fillMaxSize())
            }
        }
    }
}

@Composable
private fun ProjectNavigation(modifier: Modifier = Modifier) {
    Surface(modifier = modifier) {
        Column(
            modifier = Modifier.padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text("Workspace", style = MaterialTheme.typography.titleMedium)
            Text("Device")
            Text("Terminal")
            Text("Operations")
            Text("Diagnostics")
        }
    }
}

@Composable
private fun Workspace(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        TargetBar()

        Text(
            text = "Clean foundation",
            style = MaterialTheme.typography.headlineMedium,
        )
        Text(
            text = "Phase 1 establishes the application shell and core contracts. " +
                "USB ownership begins in Phase 2; ADB, Fastboot and Recovery are built " +
                "as protocol engines rather than screen-specific implementations.",
            style = MaterialTheme.typography.bodyLarge,
        )

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text("Current build baseline", style = MaterialTheme.typography.titleMedium)
                Text("compileSdk 37 · targetSdk 36 · minSdk 26")
                Text("No novice/expert permission profiles. No artificial capability gates.")
            }
        }
    }
}

@Composable
private fun TargetBar() {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        tonalElevation = 2.dp,
        shape = MaterialTheme.shapes.medium,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 18.dp, vertical = 14.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column {
                Text("Target", style = MaterialTheme.typography.labelMedium)
                Text("No device attached", style = MaterialTheme.typography.titleMedium)
            }
            Spacer(modifier = Modifier.width(24.dp))
            Column {
                Text("Mode", style = MaterialTheme.typography.labelMedium)
                Text(TargetMode.UNKNOWN.name, style = MaterialTheme.typography.titleMedium)
            }
        }
    }
}
