package io.github.ncorror.nekoflash.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.ncorror.nekoflash.R
import io.github.ncorror.nekoflash.fastboot.FastbootConsoleState
import io.github.ncorror.nekoflash.protocol.fastboot.FastbootReply

/** Консоль Fastboot: произвольная команда, переменная по имени и весь список. */
data class FastbootConsolePanel(
    val state: FastbootConsoleState = FastbootConsoleState.Idle,
    val onCommand: (String) -> Unit = {},
    val onVariable: (String) -> Unit = {},
    val onAllVariables: () -> Unit = {},
)

/**
 * Секция произвольного обмена по Fastboot.
 *
 * Поля свободные и списка команд нет: что бывает у Fastboot, знает устройство,
 * и запирать его набором значило бы решать за оператора, чего просить
 * (`01` §3). Отказ устройства показывается как ответ устройства, а не как наша
 * ошибка.
 */
@Composable
fun FastbootConsoleSection(
    console: FastbootConsolePanel,
    modifier: Modifier = Modifier,
) {
    var command by remember { mutableStateOf("") }
    var variable by remember { mutableStateOf("") }

    Column(
        modifier = modifier.fillMaxWidth().padding(top = 8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            text = stringResource(R.string.fastboot_console_title),
            style = MaterialTheme.typography.titleSmall,
        )

        OutlinedTextField(
            value = command,
            onValueChange = { command = it },
            label = { Text(stringResource(R.string.fastboot_command_label)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Button(onClick = { console.onCommand(command) }) {
            Text(stringResource(R.string.fastboot_command_send))
        }

        OutlinedTextField(
            value = variable,
            onValueChange = { variable = it },
            label = { Text(stringResource(R.string.fastboot_variable_label)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { console.onVariable(variable) }) {
                Text(stringResource(R.string.fastboot_variable_read))
            }
            Button(onClick = console.onAllVariables) {
                Text(stringResource(R.string.fastboot_variable_read_all))
            }
        }

        FastbootConsoleOutcome(console.state)

        Text(
            text = stringResource(R.string.fastboot_console_note),
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

@Composable
private fun FastbootConsoleOutcome(state: FastbootConsoleState) {
    when (state) {
        is FastbootConsoleState.Idle -> Unit

        is FastbootConsoleState.Running -> Text(
            text = stringResource(R.string.fastboot_console_running, state.command),
            style = MaterialTheme.typography.bodySmall,
        )

        is FastbootConsoleState.Answered -> FastbootAnswer(state)

        is FastbootConsoleState.NotAnswered -> {
            Text(
                text = stringResource(R.string.fastboot_console_no_answer, state.command, state.detail),
                style = MaterialTheme.typography.bodyMedium,
            )
            FastbootLaneLine(state.lane.name)
        }

        is FastbootConsoleState.Variables -> FastbootVariableList(state)
    }
}

@Composable
private fun FastbootAnswer(state: FastbootConsoleState.Answered) {
    // Отказ и согласие различаются словом устройства, а не местом на экране:
    // прятать `FAIL` в другую секцию значило бы подменять ответ оценкой.
    Text(
        text = stringResource(
            if (state.reply == FastbootReply.FAIL) {
                R.string.fastboot_console_fail
            } else {
                R.string.fastboot_console_okay
            },
            state.command,
            state.payload.ifBlank { "—" },
        ),
        style = MaterialTheme.typography.bodyMedium,
    )

    state.info.forEach { line ->
        Text(
            text = stringResource(R.string.fastboot_console_info, line),
            style = MaterialTheme.typography.bodySmall,
        )
    }
    FastbootLaneLine(state.lane.name)
}

@Composable
private fun FastbootVariableList(state: FastbootConsoleState.Variables) {
    val snapshot = state.snapshot
    Text(
        text = stringResource(
            if (snapshot.complete) R.string.fastboot_variables_all else R.string.fastboot_variables_partial,
            snapshot.variables.size,
        ),
        style = MaterialTheme.typography.bodyMedium,
    )

    snapshot.variables.forEach { (name, value) ->
        Text(
            text = stringResource(R.string.fastboot_variable_entry, name, value),
            style = MaterialTheme.typography.bodySmall,
        )
    }

    // Расхождение под одним именем и неразобранная строка показываются, а не
    // прячутся: это наблюдения об устройстве, и по ним видно, что разбор неполон.
    snapshot.duplicates.filter { it.conflicting }.forEach { duplicate ->
        Text(
            text = stringResource(
                R.string.fastboot_variables_conflict,
                duplicate.name,
                duplicate.values.joinToString(", "),
            ),
            style = MaterialTheme.typography.bodySmall,
        )
    }
    if (snapshot.ignored.isNotEmpty()) {
        Text(
            text = stringResource(R.string.fastboot_variables_ignored, snapshot.ignored.size),
            style = MaterialTheme.typography.bodySmall,
        )
    }
    FastbootLaneLine(state.lane.name)
}

/**
 * Состояние полосы.
 *
 * Показывается всегда: у Fastboot полоса одна, и потеря рамки липкая. Скрыв её,
 * мы дали бы оператору нажимать кнопку, которая заведомо не сработает.
 */
@Composable
private fun FastbootLaneLine(lane: String) {
    Text(
        text = stringResource(R.string.fastboot_console_lane, lane),
        style = MaterialTheme.typography.bodySmall,
    )
}
