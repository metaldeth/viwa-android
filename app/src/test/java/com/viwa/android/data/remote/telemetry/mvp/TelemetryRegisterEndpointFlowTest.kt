package com.viwa.android.data.remote.telemetry.mvp



import com.viwa.android.domain.model.TelemetryConfig

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue

import org.junit.Test



/** Validates scan → register endpoint trust/persist preconditions without ServiceViewModel wiring. */

class TelemetryRegisterEndpointFlowTest {

    private val validKey = RegistrationKeyUtilsTest.VALID_KEY

    private val trustedApiUrl = TelemetryConfig.DEFAULT_API_URL



    @Test

    fun `trusted qr scan yields normalized apiUrl for register`() {

        val json =

            """

            {

              "type":"VIWA_TELEMETRY_REGISTRATION",

              "version":1,

              "registrationKey":"$validKey",

              "serialNumber":"VIWA-000004",

              "apiUrl":"https://tl.vitamin-water.ru:443"

            }

            """.trimIndent()

        val parsed = TelemetryRegistrationQrParser.parse(json)

        assertTrue(parsed is TelemetryRegistrationQrParseResult.Success)

        val scan = (parsed as TelemetryRegistrationQrParseResult.Success).scan

        val trusted =

            TelemetryUrlValidator.validateTrustedCandidate(scan.apiUrl!!, trustedApiUrl) as

                TelemetryUrlValidator.Result.Valid

        assertEquals(trustedApiUrl, trusted.normalizedOrigin)
        assertNull(RegistrationKeyUtils.validationMessage(scan.registrationKey))

    }



    @Test

    fun `malicious qr apiUrl never passes trust gate`() {

        val json =

            """

            {"type":"VIWA_TELEMETRY_REGISTRATION","version":1,"registrationKey":"$validKey","apiUrl":"https://evil.example.com"}

            """.trimIndent()

        val parsed = TelemetryRegistrationQrParser.parse(json)

        assertTrue(parsed is TelemetryRegistrationQrParseResult.Success)

        val scan = (parsed as TelemetryRegistrationQrParseResult.Success).scan

        val trusted = TelemetryUrlValidator.validateTrustedCandidate(scan.apiUrl!!, trustedApiUrl)

        assertTrue(trusted is TelemetryUrlValidator.Result.Invalid)

    }



    @Test

    fun `invalid http apiUrl rejected before trust check`() {

        val result = TelemetryUrlValidator.validateStrict("http://tl.vitamin-water.ru")

        assertTrue(result is TelemetryUrlValidator.Result.Invalid)

    }

}

