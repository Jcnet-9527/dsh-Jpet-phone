package io.github.dshpet.mobile.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import io.github.dshpet.mobile.PetOptions
import io.github.dshpet.mobile.R

/**
 * The optional AI chat. Nothing here talks to the network: the fields only fill
 * in the settings the chat module reads when the user asks for a reply, so a
 * switched-off module means an app that still makes no request at all.
 */
@Composable
fun ChatSection(options: PetOptions, onChanged: (PetOptions) -> Unit, onOpenChat: () -> Unit) {
    SettingSwitch(R.string.dsh, options.dsh) { onChanged(options.copy(dsh = it)) }
    if (options.dsh) {
        Text(stringResource(R.string.dsh_hint))
        var dshUrl by remember(options.dshBaseUrl) { mutableStateOf(options.dshBaseUrl) }
        var dshToken by remember(options.dshToken) { mutableStateOf(options.dshToken) }
        Field(R.string.dsh_endpoint, dshUrl, R.string.dsh_endpoint_hint,
            KeyboardOptions(imeAction = ImeAction.Next)) {
            dshUrl = it
            onChanged(options.copy(dshBaseUrl = it))
        }
        Field(R.string.dsh_token, dshToken, R.string.dsh_token_hint,
            KeyboardOptions(imeAction = ImeAction.Next), secret = true) {
            dshToken = it
            onChanged(options.copy(dshToken = it))
        }
        HorizontalDivider()
    }
    SettingSwitch(R.string.chat, options.chat) { onChanged(options.copy(chat = it)) }
    if (!options.chat) return
    Text(stringResource(R.string.chat_hint))

    var endpoint by remember(options.chatBaseUrl) { mutableStateOf(options.chatBaseUrl) }
    var key by remember(options.chatApiKey) { mutableStateOf(options.chatApiKey) }
    var model by remember(options.chatModel) { mutableStateOf(options.chatModel) }
    var persona by remember(options.chatSystem) { mutableStateOf(options.chatSystem) }

    Field(R.string.chat_endpoint, endpoint, R.string.chat_endpoint_hint,
        KeyboardOptions(imeAction = ImeAction.Next)) {
        endpoint = it
        onChanged(options.copy(chatBaseUrl = it))
    }
    Field(R.string.chat_key, key, R.string.chat_key_hint,
        KeyboardOptions(imeAction = ImeAction.Next), secret = true) {
        key = it
        onChanged(options.copy(chatApiKey = it))
    }
    Field(R.string.chat_model, model, R.string.chat_model_hint,
        KeyboardOptions(imeAction = ImeAction.Next)) {
        model = it
        onChanged(options.copy(chatModel = it))
    }
    Field(R.string.chat_persona, persona, R.string.chat_persona_hint,
        KeyboardOptions(imeAction = ImeAction.Done), maxLines = 4) {
        persona = it
        onChanged(options.copy(chatSystem = it))
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        TextButton(onClick = {
            persona = PetOptions.DEFAULT_CHAT_SYSTEM
            onChanged(options.copy(chatSystem = PetOptions.DEFAULT_CHAT_SYSTEM))
        }) { Text(stringResource(R.string.chat_persona_default)) }
        TextButton(onClick = onOpenChat) { Text(stringResource(R.string.chat_open)) }
    }
    Text(if (options.chatSystem == PetOptions.DEFAULT_CHAT_SYSTEM)
        stringResource(R.string.chat_persona_default) else stringResource(R.string.chat_persona_custom),
        color = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant)
    HorizontalDivider()

    var memory by remember(options.chatMemory) { mutableStateOf(options.chatMemory.toFloat()) }
    SettingSlider(label = R.string.chat_memory,
        valueText = stringResource(R.string.chat_memory_value, memory.toInt()),
        value = memory, range = 0f..20f,
        changed = { memory = it },
        onFinished = { onChanged(options.copy(chatMemory = memory.toInt())) })
    Text(stringResource(R.string.chat_memory_hint))

    SettingSwitch(R.string.chat_balance_switch, options.chatBalance) {
        onChanged(options.copy(chatBalance = it))
    }
    Text(stringResource(R.string.chat_balance_hint))
}

@Composable
private fun Field(label: Int, value: String, hint: Int,
                  keyboardOptions: KeyboardOptions, maxLines: Int = 1, secret: Boolean = false,
                  onChanged: (String) -> Unit) {
    var draft by remember(value) { mutableStateOf(value) }
    val commit = { onChanged(draft) }
    Text(stringResource(label))
    OutlinedTextField(
        value = draft,
        onValueChange = { draft = it },
        modifier = Modifier.fillMaxWidth().onFocusChanged {
            if (!it.isFocused && draft != value) commit()
        },
        maxLines = maxLines,
        keyboardOptions = keyboardOptions,
        visualTransformation = if (secret) PasswordVisualTransformation()
        else VisualTransformation.None,
        placeholder = { Text(stringResource(hint)) })
}
