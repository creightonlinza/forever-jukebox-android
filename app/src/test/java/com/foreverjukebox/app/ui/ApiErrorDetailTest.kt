package com.foreverjukebox.app.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ApiErrorDetailTest {

    @Test
    fun stringDetailBecomesMessage() {
        val detail = parseApiErrorDetail("""{"detail":"Unsupported file type"}""")

        assertEquals("Unsupported file type", detail.message)
        assertNull(detail.errorCode)
    }

    @Test
    fun objectDetailCarriesMessageAndCode() {
        val detail = parseApiErrorDetail(
            """{"detail":{"error_code":"track_too_long","message":"Error: too long"}}"""
        )

        assertEquals("Error: too long", detail.message)
        assertEquals("track_too_long", detail.errorCode)
    }

    @Test
    fun pydanticListDetailUsesFirstMsg() {
        val detail = parseApiErrorDetail(
            """{"detail":[{"loc":["body","file"],"msg":"Field required","type":"missing"}]}"""
        )

        assertEquals("Field required", detail.message)
        assertNull(detail.errorCode)
    }

    @Test
    fun malformedOrBlankBodiesYieldEmptyDetail() {
        assertNull(parseApiErrorDetail(null).message)
        assertNull(parseApiErrorDetail("").message)
        assertNull(parseApiErrorDetail("not json").message)
        assertNull(parseApiErrorDetail("""{"other":"thing"}""").message)
        assertNull(parseApiErrorDetail("""["detail"]""").message)
    }
}
