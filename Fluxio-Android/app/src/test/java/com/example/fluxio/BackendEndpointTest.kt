package com.example.fluxio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class BackendEndpointTest {
    @Test
    fun normalizesConfiguredBaseUrl() {
        assertEquals("http://10.0.2.2:8080/", BackendEndpoint.parse("http://10.0.2.2:8080").toString())
    }

    @Test
    fun rejectsUrlContainingCredentialsOrQuery() {
        assertThrows(IllegalArgumentException::class.java) {
            BackendEndpoint.parse("https://user:secret@example.com/api?debug=true")
        }
    }
}
