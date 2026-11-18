package com.neo.codexquota.data

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class ProxyApiTest {
    private lateinit var server: MockWebServer
    private val api = ProxyApi()
    private val account = QuotaAccount("a::01", "a", "01", "chatgpt-account")

    @Before fun setup() { server = MockWebServer().apply { start() } }
    @After fun shutdown() { server.shutdown() }
    private fun base(): String = server.url("/").toString().trimEnd('/')

    @Test fun `使用 v8 凭据接口和管理鉴权`() = runTest {
        server.enqueue(MockResponse().setBody("""{"files":[]}"""))
        assertTrue(api.accounts(base(), "management-secret").isEmpty())
        val request = server.takeRequest()
        assertEquals("/v8/management/credentials", request.path)
        assertEquals("Bearer management-secret", request.getHeader("Authorization"))
    }

    @Test fun `代查只发送令牌占位符且使用正确账号`() = runTest {
        val inner = """{"rate_limit":{"primary_window":{"used_percent":20,"limit_window_seconds":18000}}}"""
        server.enqueue(MockResponse().setBody(buildJsonObject { put("status_code", 200); put("body", inner) }.toString()))
        val snapshot = api.quota(base(), "management-secret", account)
        assertEquals(80.0, snapshot.windows.single().remainingPercent!!, 0.0)
        val request = server.takeRequest()
        assertEquals("/v8/management/requests/api-call", request.path)
        val body = quotaJson.parseToJsonElement(request.body.readUtf8()).jsonObject
        assertEquals("01", body.text("auth_index"))
        assertEquals("GET", body.text("method"))
        assertEquals(ProxyApi.USAGE_URL, body.text("url"))
        assertEquals("Bearer \$TOKEN\$", body.obj("header")!!.text("Authorization"))
        assertEquals("chatgpt-account", body.obj("header")!!.text("Chatgpt-Account-Id"))
    }

    @Test fun `外层成功但上游授权失败仍判定为失败`() = runTest {
        server.enqueue(MockResponse().setBody("""{"status_code":401,"body":"secret upstream error"}"""))
        try { api.quota(base(), "key", account); fail("应抛出授权错误") }
        catch (error: ApiException) {
            assertEquals(401, error.status)
            assertTrue(error.message!!.contains("账号授权"))
            assertFalse(error.message!!.contains("secret"))
        }
    }

    @Test fun `管理密钥失败单独提示`() = runTest {
        server.enqueue(MockResponse().setResponseCode(401).setBody("secret"))
        try { api.accounts(base(), "key"); fail("应抛出密钥错误") }
        catch (error: ApiException) { assertTrue(error.message!!.contains("管理密钥")) }
    }

    @Test fun `兼容上游 body 为对象`() = runTest {
        server.enqueue(MockResponse().setBody("""{"status_code":200,"body":{
          "rate_limit":{"secondary_window":{"used_percent":0,"limit_window_seconds":604800}}
        }}"""))
        assertEquals(100.0, api.quota(base(), "key", account).windows.single().remainingPercent!!, 0.0)
    }

    @Test fun `管理重定向不能携带密钥继续请求`() = runTest {
        server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", "https://example.com/other"))
        try { api.accounts(base(), "key"); fail("应拒绝重定向") }
        catch (error: ApiException) { assertTrue(error.message!!.contains("重定向")) }
        assertEquals(1, server.requestCount)
    }

    @Test fun `规范化后台地址并保留反向代理前缀`() {
        assertEquals("https://example.com/prefix", ProxyApi.normalizeServer("example.com/prefix/management.html"))
        assertEquals("https://example.com", ProxyApi.normalizeServer("https://example.com/v8/management/"))
        assertEquals("http://192.168.1.2:8317", ProxyApi.normalizeServer("http://192.168.1.2:8317/"))
    }

    @Test(expected = ApiException::class)
    fun `服务地址不能夹带密码`() { ProxyApi.normalizeServer("https://name:password@example.com") }
}
