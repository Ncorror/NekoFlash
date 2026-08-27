package io.github.ncorror.nekoflash.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.ncorror.nekoflash.R
import io.github.ncorror.nekoflash.core.model.TargetMode

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NekoFlashApp() {
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(stringResource(R.string.app_name))
                        Text(
                            text = stringResource(R.string.app_tagline),
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
            Text(stringResource(R.string.nav_workspace), style = MaterialTheme.typography.titleMedium)
            Text(stringResource(R.string.nav_device))
            Text(stringResource(R.string.nav_terminal))
            Text(stringResource(R.string.nav_operations))
            Text(stringResource(R.string.nav_diagnostics))
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
            text = stringResource(R.string.foundation_title),
            style = MaterialTheme.typography.headlineMedium,
        )
        Text(
            text = stringResource(R.string.foundation_description),
            style = MaterialTheme.typography.bodyLarge,
        )

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    stringResource(R.string.build_baseline_title),
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(stringResource(R.string.build_baseline_values))
                Text(stringResource(R.string.professional_capability_policy))
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
                Text(stringResource(R.string.target_label), style = MaterialTheme.typography.labelMedium)
                Text(
                    stringResource(R.string.target_none_attached),
                    style = MaterialTheme.typography.titleMedium,
                )
            }
            Spacer(modifier = Modifier.width(24.dp))
            Column {
                Text(stringResource(R.string.mode_label), style = MaterialTheme.typography.labelMedium)
                Text(
                    localizedTargetMode(TargetMode.UNKNOWN),
                    style = MaterialTheme.typography.titleMedium,
                )
            }
        }
    }
}

@Composable
private fun localizedTargetMode(mode: TargetMode): String = stringResource(
    when (mode) {
        TargetMode.ADB -> R.string.target_mode_adb
        TargetMode.RECOVERY -> R.string.target_mode_recovery
        TargetMode.SIDELOAD -> R.string.target_mode_sideload
        TargetMode.BOOTLOADER_FASTBOOT -> R.string.target_mode_bootloader_fastboot
        TargetMode.FASTBOOTD -> R.string.target_mode_fastbootd
        TargetMode.UNKNOWN -> R.string.target_mode_unknown
    },
)
