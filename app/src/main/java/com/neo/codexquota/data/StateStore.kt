package com.neo.codexquota.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.*
import java.io.IOException

private val Context.quotaDataStore by preferencesDataStore(name = "codex_quota")

class StateStore(context: Context) {
    private val store = context.applicationContext.quotaDataStore
    private val stateKey = stringPreferencesKey("state_v1")
    val states = store.data.map { preferences -> StateCodec.decode(preferences[stateKey]) }
        .catch { error ->
            if (error is IOException) emit(AppState(globalError = "本地数据无法读取，请重新配置")) else throw error
        }

    suspend fun update(transform: (AppState) -> AppState) {
        store.edit { preferences ->
            preferences[stateKey] = StateCodec.encode(transform(StateCodec.decode(preferences[stateKey])))
        }
    }
}

internal object StateCodec {
    fun encode(state: AppState): String = buildJsonObject {
        put("server", state.server)
        put("encryptedKey", state.encryptedKey)
        state.connectionDraft?.let { draft ->
            putJsonObject("connectionDraft") {
                put("server", draft.server)
                put("encryptedKey", draft.encryptedKey)
            }
        }
        put("defaultAccountId", state.defaultAccountId?.let(::JsonPrimitive) ?: JsonNull)
        put("refreshIntervalMinutes", state.refreshIntervalMinutes?.let(::JsonPrimitive) ?: JsonNull)
        putJsonObject("aliases") { state.aliases.forEach { (id, name) -> put(id, name) } }
        put("lastAttempt", state.lastAttempt)
        state.globalError?.let { put("globalError", it) }
        putJsonArray("accounts") {
            state.accounts.forEach { a -> addJsonObject {
                put("id", a.id); put("name", a.name); put("authIndex", a.authIndex)
                put("accountId", a.accountId); put("email", a.email); put("plan", a.plan); put("disabled", a.disabled)
            } }
        }
        putJsonArray("snapshots") {
            state.snapshots.values.forEach { s -> addJsonObject {
                put("accountId", s.accountId); put("plan", s.plan); put("fetchedAt", s.fetchedAt)
                s.error?.let { put("error", it) }
                putJsonArray("windows") { s.windows.forEach { w -> addJsonObject {
                    put("label", w.label)
                    w.remainingPercent?.let { put("remainingPercent", it) }
                    w.resetAtMillis?.let { put("resetAtMillis", it) }
                    w.durationSeconds?.let { put("durationSeconds", it) }
                } } }
            } }
        }
    }.toString()

    fun decode(raw: String?): AppState {
        if (raw.isNullOrBlank()) return AppState()
        return runCatching {
            val root = quotaJson.parseToJsonElement(raw).jsonObject
            val accounts = (root["accounts"] as? JsonArray).orEmpty().map { item ->
                val a = item.jsonObject
                QuotaAccount(a.text("id"), a.text("name"), a.text("authIndex"), a.text("accountId"),
                    a.text("email"), a.text("plan"), a.bool("disabled"))
            }
            // 仅旧版本首次读取时迁移多选配置；显式保存的空选择不能再次自动换号。
            val legacySelected = (root["selectedIds"] as? JsonArray).orEmpty().map { it.jsonPrimitive.content }.toSet()
            val defaultAccountId = if ("defaultAccountId" in root) {
                (root["defaultAccountId"] as? JsonPrimitive)?.contentOrNull?.ifBlank { null }
            } else {
                accounts.firstOrNull { !it.disabled && it.id in legacySelected }?.id
                    ?: accounts.firstOrNull { !it.disabled }?.id
            }
            val interval = when {
                "refreshIntervalMinutes" !in root -> 30
                root["refreshIntervalMinutes"] == JsonNull -> null
                else -> (root["refreshIntervalMinutes"] as? JsonPrimitive)?.intOrNull?.takeIf { it >= 5 } ?: 30
            }
            AppState(
                server = root.text("server"), encryptedKey = root.text("encryptedKey"),
                defaultAccountId = defaultAccountId, refreshIntervalMinutes = interval,
                connectionDraft = root.obj("connectionDraft")?.let {
                    ConnectionDraft(it.text("server"), it.text("encryptedKey"))
                },
                aliases = root.obj("aliases")?.mapValues { it.value.jsonPrimitive.content }.orEmpty(),
                lastAttempt = root.number("lastAttempt")?.toLong() ?: 0,
                globalError = root.text("globalError").ifBlank { null },
                accounts = accounts,
                snapshots = (root["snapshots"] as? JsonArray).orEmpty().map { item ->
                    val s = item.jsonObject
                    QuotaSnapshot(
                        accountId = s.text("accountId"), plan = s.text("plan"),
                        fetchedAt = s.number("fetchedAt")?.toLong() ?: 0,
                        error = s.text("error").ifBlank { null },
                        windows = (s["windows"] as? JsonArray).orEmpty().map { window ->
                            val w = window.jsonObject
                            QuotaWindow(w.text("label"), w.number("remainingPercent"),
                                w.number("resetAtMillis")?.toLong(), w.number("durationSeconds")?.toLong())
                        },
                    )
                }.associateBy { it.accountId },
            )
        }.getOrElse { AppState(globalError = "本地数据格式无法读取，请重新配置") }
    }
}
