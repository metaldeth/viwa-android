package com.viwa.android.data.remote.telemetry.mvp

import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class MvpTelemetryApiClientTest {
    private lateinit var server: MockWebServer
    private lateinit var client: MvpTelemetryApiClient
    private lateinit var json: Json

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        json =
            Json {
                ignoreUnknownKeys = true
                encodeDefaults = true
                explicitNulls = false
            }
        client =
            MvpTelemetryApiClient(
                httpClient = OkHttpClient(),
                json = json,
                enrollmentKeyProvider = { "test-enrollment-key" },
            )
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `reserve sends X-Enrollment-Key and parses response`() {
        // given
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody("""{"serialNumber":"VIWA-000007","reservationToken":"rt","expiresAt":"2026-07-17T00:00:00Z"}"""),
        )
        // when
        val result = kotlinx.coroutines.runBlocking {
            client.reserveSerial(server.url("/").toString().removeSuffix("/"), "inst-1")
        }
        // then
        val recorded = server.takeRequest()
        assertEquals("test-enrollment-key", recorded.getHeader("X-Enrollment-Key"))
        assertTrue(recorded.path!!.endsWith("/api/v1/machines/serials/reserve"))
        assertEquals("VIWA-000007", result.getOrThrow().serialNumber)
    }

    @Test
    fun `enroll idempotent second call succeeds`() {
        // given
        val body =
            """{"machineId":"m1","serialNumber":"VIWA-000001","wsProtocolUrl":"ws://localhost/ws"}"""
        server.enqueue(MockResponse().setResponseCode(200).setBody(body))
        server.enqueue(MockResponse().setResponseCode(200).setBody(body))
        val request =
            EnrollRequestDto(
                installationId = "inst",
                serialNumber = "VIWA-000001",
                credential = "mch_deadbeef",
                device = EnrollDeviceDto("A", "B", "7"),
                app = EnrollAppDto("1", 1),
            )
        // when
        val first = kotlinx.coroutines.runBlocking { client.enroll(server.url("/").toString().removeSuffix("/"), request) }
        val second = kotlinx.coroutines.runBlocking { client.enroll(server.url("/").toString().removeSuffix("/"), request) }
        // then
        assertTrue(first.isSuccess)
        assertTrue(second.isSuccess)
    }

    @Test
    fun `enroll conflict 409 SERIAL_ALREADY_BOUND throws typed exception`() {
        // given
        server.enqueue(
            MockResponse()
                .setResponseCode(409)
                .setBody("""{"code":"SERIAL_ALREADY_BOUND","message":"already bound"}"""),
        )
        val request =
            EnrollRequestDto(
                installationId = "inst",
                serialNumber = "VIWA-000001",
                credential = "mch_x",
                device = EnrollDeviceDto("A", "B", "7"),
                app = EnrollAppDto("1", 1),
            )
        // when
        val result = kotlinx.coroutines.runBlocking { client.enroll(server.url("/").toString().removeSuffix("/"), request) }
        // then
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is SerialAlreadyBoundException)
    }

    @Test
    fun `enroll conflict 409 nest SERIAL_ALREADY_BOUND throws typed exception`() {
        // given
        server.enqueue(
            MockResponse()
                .setResponseCode(409)
                .setBody(
                    """
                    {
                      "statusCode": 409,
                      "message": {
                        "code": "SERIAL_ALREADY_BOUND",
                        "message": "Serial is already bound to another installation"
                      },
                      "error": "Conflict"
                    }
                    """.trimIndent(),
                ),
        )
        val request =
            EnrollRequestDto(
                installationId = "inst",
                serialNumber = "VIWA-000001",
                credential = "mch_x",
                device = EnrollDeviceDto("A", "B", "7"),
                app = EnrollAppDto("1", 1),
            )
        // when
        val result = kotlinx.coroutines.runBlocking { client.enroll(server.url("/").toString().removeSuffix("/"), request) }
        // then
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is SerialAlreadyBoundException)
    }

    @Test
    fun `enroll conflict 409 INSTALLATION_ALREADY_ENROLLED surfaces normal error`() {
        // given
        server.enqueue(
            MockResponse()
                .setResponseCode(409)
                .setBody(
                    """
                    {
                      "statusCode": 409,
                      "message": {
                        "code": "INSTALLATION_ALREADY_ENROLLED",
                        "message": "Installation already enrolled with different serial"
                      },
                      "error": "Conflict"
                    }
                    """.trimIndent(),
                ),
        )
        val request =
            EnrollRequestDto(
                installationId = "inst",
                serialNumber = "VIWA-000001",
                credential = "mch_x",
                device = EnrollDeviceDto("A", "B", "7"),
                app = EnrollAppDto("1", 1),
            )
        // when
        val result = kotlinx.coroutines.runBlocking { client.enroll(server.url("/").toString().removeSuffix("/"), request) }
        // then
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() !is SerialAlreadyBoundException)
        assertTrue(result.exceptionOrNull()?.message?.contains("409") == true)
    }

    @Test
    fun `reserve fails locally when enrollment key is blank`() {
        // given
        val clientWithoutKey =
            MvpTelemetryApiClient(
                httpClient = OkHttpClient(),
                json = json,
                enrollmentKeyProvider = { "   " },
            )
        // when
        val result =
            kotlinx.coroutines.runBlocking {
                clientWithoutKey.reserveSerial(server.url("/").toString().removeSuffix("/"), "inst-1")
            }
        // then
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is MissingEnrollmentKeyException)
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `enroll fails locally when enrollment key is blank`() {
        // given
        val clientWithoutKey =
            MvpTelemetryApiClient(
                httpClient = OkHttpClient(),
                json = json,
                enrollmentKeyProvider = { "" },
            )
        val request =
            EnrollRequestDto(
                installationId = "inst",
                serialNumber = "VIWA-000001",
                credential = "mch_x",
                device = EnrollDeviceDto("A", "B", "7"),
                app = EnrollAppDto("1", 1),
            )
        // when
        val result =
            kotlinx.coroutines.runBlocking {
                clientWithoutKey.enroll(server.url("/").toString().removeSuffix("/"), request)
            }
        // then
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is MissingEnrollmentKeyException)
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `rebind sends rebind true in JSON body`() {
        // given
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody("""{"machineId":"m1","serialNumber":"VIWA-000001"}"""),
        )
        val request =
            EnrollRequestDto(
                installationId = "inst",
                serialNumber = "VIWA-000001",
                credential = "mch_x",
                rebind = true,
                device = EnrollDeviceDto("A", "B", "7"),
                app = EnrollAppDto("1", 1),
            )
        // when
        kotlinx.coroutines.runBlocking { client.enroll(server.url("/").toString().removeSuffix("/"), request) }
        // then
        val recorded = server.takeRequest()
        assertTrue(recorded.body.readUtf8().contains("\"rebind\":true"))
    }

    @Test
    fun `register posts exact body without enrollment header`() {
        server.enqueue(
            MockResponse()
                .setResponseCode(201)
                .setBody(
                    """
                    {
                      "id":"id-1",
                      "machineId":"m-4",
                      "serialNumber":"VIWA-000004",
                      "installationId":"inst-1",
                      "machineSecret":"sec_value",
                      "tokenEndpoint":"/api/v1/machines/token",
                      "wsUrl":"wss://tl.vitamin-water.ru/api/v1/machines/ws",
                      "protocolVersion":1,
                      "heartbeatIntervalSeconds":30
                    }
                    """.trimIndent(),
                ),
        )
        val request =
            RegisterRequestDto(
                registrationKey = "REG-0123456789AB",
                serialNumber = "VIWA-000004",
                installationId = "inst-1",
                device = EnrollDeviceDto("A", "B", "7"),
                app = EnrollAppDto("1", 1),
            )
        val result =
            kotlinx.coroutines.runBlocking {
                client.register(server.url("/").toString().removeSuffix("/"), request)
            }
        assertTrue(result.isSuccess)
        val recorded = server.takeRequest()
        assertEquals(null, recorded.getHeader("X-Enrollment-Key"))
        assertTrue(recorded.path!!.endsWith("/api/v1/machines/register"))
        val body = recorded.body.readUtf8()
        assertTrue(body.contains("\"registrationKey\":\"REG-0123456789AB\""))
        assertTrue(body.contains("\"serialNumber\":\"VIWA-000004\""))
        assertFalse(body.contains("sec_value"))
    }

    @Test
    fun `register conflict 403 REBIND_NOT_ALLOWED throws typed exception`() {
        server.enqueue(
            MockResponse()
                .setResponseCode(403)
                .setBody(
                    """
                    {
                      "statusCode": 403,
                      "message": {
                        "code": "REBIND_NOT_ALLOWED",
                        "message": "Rebind not allowed"
                      },
                      "error": "Forbidden"
                    }
                    """.trimIndent(),
                ),
        )
        val request =
            RegisterRequestDto(
                registrationKey = "REG-0123456789AB",
                serialNumber = "VIWA-000004",
                installationId = "inst-1",
                device = EnrollDeviceDto("A", "B", "7"),
                app = EnrollAppDto("1", 1),
            )
        val result =
            kotlinx.coroutines.runBlocking {
                client.register(server.url("/").toString().removeSuffix("/"), request)
            }
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is RebindNotAllowedException)
    }

    @Test
    fun `fetchToken parses jwt response`() {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody("""{"accessToken":"jwt_test","tokenType":"Bearer","expiresIn":3600}"""),
        )
        val result =
            kotlinx.coroutines.runBlocking {
                client.fetchToken(
                    server.url("/").toString().removeSuffix("/"),
                    "/api/v1/machines/token",
                    TokenRequestDto("VIWA-000004", "sec_test"),
                )
            }
        assertTrue(result.isSuccess)
        assertEquals("jwt_test", result.getOrThrow().accessToken)
        val recorded = server.takeRequest()
        assertEquals(null, recorded.getHeader("X-Enrollment-Key"))
    }
}
