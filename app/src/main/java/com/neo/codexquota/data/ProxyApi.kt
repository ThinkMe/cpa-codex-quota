package com.neo.codexquota.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit

class ApiException(message: String, val status: Int? = null) : IOException(message)

class ProxyApi(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(45, TimeUnit.SECONDS)
        .callTimeout(60, TimeUnit.SECONDS)
        .followRedirects(false)
        .followSslRedirects(false)
        .build(),
) {
    companion object {
        const val USAGE_URL = "https://chatgpt.com/backend-api/wham/usage"

        fun normalizeServer(input: String): String {
            var base = input.trim().trimEnd('/')
            if (!base.contains("://")) base = "https://$base"
            base = base.removeSuffix("/management.html").removeSuffix("/v8/management").trimEnd('/')
            val url = base.toHttpUrlOrNull() ?: throw ApiException("请输入正确的 HTTP 或 HTTPS 服务地址")
            if (url.username.isNotEmpty() || url.password.isNotEmpty() || url.query != null || url.fragment != null) {
                throw ApiException("服务地址不能包含密码、查询参数或片段")
            }
            return url.toString().trimEnd('/')
        }
    }

    suspend fun accounts(server: String, key: String): List<QuotaAccount> = withContext(Dispatchers.IO) {
        val body = request(server, key, "/credentials")
        try { QuotaParser.accounts(body) } catch (e: IllegalArgumentException) {
            throw ApiException("账号列表格式不兼容，请确认服务为 CLIProxyAPI v8.0.69")
        } catch (e: IllegalStateException) {
            throw ApiException("账号列表格式不兼容，请确认服务为 CLIProxyAPI v8.0.69")
        }
    }

    suspend fun quota(server: String, key: String, account: QuotaAccount): QuotaSnapshot = withContext(Dispatchers.IO) {
        if (account.authIndex.isBlank()) throw ApiException("账号缺少 auth_index，无法查询额度")
        val payload = buildJsonObject {
            put("auth_index", account.authIndex)
            put("method", "GET")
            put("url", USAGE_URL)
            putJsonObject("header") {
                put("Authorization", "Bearer \$TOKEN\$")
                put("Content-Type", "application/json")
                put("User-Agent", "codex-tui/0.149.1")
                if (account.accountId.isNotBlank()) put("Chatgpt-Account-Id", account.accountId)
            }
        }
        val response = request(server, key, "/requests/api-call", payload.toString())
        val outer = runCatching { quotaJson.parseToJsonElement(response) as JsonObject }
            .getOrElse { throw ApiException("服务返回了无法识别的代查响应") }
        val status = outer.number("status_code")?.toInt() ?: throw ApiException("代查响应缺少上游状态码")
        if (status !in 200..299) throw ApiException(upstreamError(status), status)
        val upstreamBody = outer["body"]
        val body = if (upstreamBody is JsonPrimitive) upstreamBody.content else upstreamBody?.toString()
        if (body.isNullOrBlank()) throw ApiException("额度响应为空")
        try { QuotaParser.usage(account.id, body, System.currentTimeMillis()) }
        catch (e: IllegalArgumentException) { throw ApiException("额度数据格式无法识别") }
        catch (e: IllegalStateException) { throw ApiException("额度数据格式无法识别") }
    }

    private fun request(server: String, key: String, path: String, payload: String? = null): String {
        val builder = Request.Builder().url("${server.trimEnd('/')}/v8/management$path")
            .header("Authorization", "Bearer $key").header("Accept", "application/json")
        if (payload != null) builder.post(payload.toRequestBody("application/json; charset=utf-8".toMediaType()))
        client.newCall(builder.build()).execute().use { response ->
            if (!response.isSuccessful) {
                val message = when (response.code) {
                    401 -> "管理密钥无效，请重新填写"
                    403 -> "管理接口拒绝访问，请检查密钥和远程管理设置"
                    404 -> "未找到 v8 管理接口，请检查地址和服务版本"
                    429 -> "请求过于频繁，请稍后刷新"
                    in 300..399 -> "服务发生重定向，请填写最终服务地址"
                    else -> "管理服务请求失败（HTTP ${response.code}）"
                }
                throw ApiException(message, response.code)
            }
            return response.body?.string() ?: throw ApiException("管理服务返回空响应")
        }
    }

    private fun upstreamError(status: Int): String = when (status) {
        401 -> "账号授权已失效，请到管理后台重新授权"
        403 -> "上游拒绝额度查询，请在管理后台检查账号"
        429 -> "上游查询限流，请稍后刷新"
        else -> "上游额度查询失败（HTTP $status）"
    }
}
