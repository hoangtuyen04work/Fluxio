package com.example.fluxio.data.remote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.POST

class TransferContractTest {
    @Test
    fun exposesAllMultipartControlPlaneRoutes() {
        val methods = TransferApi::class.java.declaredMethods.associateBy { it.name }
        assertEquals("uploads", methods.getValue("createUpload").getAnnotation(POST::class.java).value)
        assertEquals("uploads/{uploadId}/part-urls", methods.getValue("signParts").getAnnotation(POST::class.java).value)
        assertEquals("uploads/{uploadId}/parts", methods.getValue("listParts").getAnnotation(GET::class.java).value)
        assertEquals("uploads/{uploadId}/complete", methods.getValue("completeUpload").getAnnotation(POST::class.java).value)
        assertEquals("uploads/{uploadId}", methods.getValue("abortUpload").getAnnotation(DELETE::class.java).value)
        assertEquals("files/{fileId}/download-ticket", methods.getValue("createDownloadTicket").getAnnotation(POST::class.java).value)
        assertNotNull(methods.getValue("createUpload").parameterAnnotations.flatten()
            .firstOrNull { it is retrofit2.http.Header && it.value == "Idempotency-Key" })
    }
}
