package com.latenighthack.ktbuf.rpc

import okhttp3.OkHttpClient
import okio.Buffer
import okio.GzipSource
import okio.buffer
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.lang.reflect.Proxy
import java.security.cert.CertificateFactory
import java.security.cert.Certificate
import java.util.zip.GZIPOutputStream
import javax.net.ssl.SSLSession
import kotlin.test.*

class DependencySecurityRegressionTests {
    @Test fun defaultHostnameVerifierRejectsUnicodeCaseFoldingToAnotherHost() {
        val certificate = CertificateFactory.getInstance("X.509").generateCertificate("""-----BEGIN CERTIFICATE-----
MIICtzCCAZ+gAwIBAgIJAME4qgGXzP2PMA0GCSqGSIb3DQEBCwUAMBAxDjAMBgNV
BAMMBWsuY29tMB4XDTI2MTAwODIzMzk1N1oXDTM2MTAwNTIzMzk1N1owEDEOMAwG
A1UEAwwFay5jb20wggEiMA0GCSqGSIb3DQEBAQUAA4IBDwAwggEKAoIBAQDGpP2e
rgqqCV1G0glIeNriuP9xWUUy/kzo+bpX0yL5vwPgoFgpaS04PcmmgFRIkXhVYDGO
sAVKZ+4YwjI5M8liytwQE9D8CJppCgPO1acbJpb4dkZY50maSM4cIPtKNcdBoEfw
82E/znJmlJ0v40Pwvdvnnl+RrY1Yuec5OT2Tuy0bhsROWP4jG0wDAMHnE0tkfEZN
nyvtvvD6U7M6NqZiK2Msb35hHaASMXJNlHkEQE+7CQmayYpTI3PJYD31hwEGuRlD
/qda/aFll1P1n3SMYgukEi0iyt+5fAWntMDa3UgUo1z2qXNDw/BKfXOo6tzDqxI+
U9ZQwNUMKxW93PJRAgMBAAGjFDASMBAGA1UdEQQJMAeCBWsuY29tMA0GCSqGSIb3
DQEBCwUAA4IBAQBcbtgaJ/3RBUag2S8nWMI8UXc6hek8egeweeb6wy0yPQHGkf0r
ptS74ePjTYEv8/KRu/oHs9NPFrNDlr2iDJ07cuqL1Z8n70PVWlb4nBGmMOfwAkyu
BmVbgVHnA/ntmAzFoODccRBVbpKe+bqJBViYxoJOdiA8LSxWCfexlgJWooYIUfMM
utZjZUU1de1VjZbtIaEJ4XjM1wpwuuj6zF/C6FqgHDkG72j59NolEjIilU7A68Vc
nGl0LYdRjWFrLok+hMBhBMylW5vPRgR7gMOqyYdJd+bcTVIwWikSdMh6x3J9I/ib
9VPDBJR2BJEo6xkoqKJ/YaO+owJI14fsC9Wb
-----END CERTIFICATE-----
""".byteInputStream())
        val session = Proxy.newProxyInstance(SSLSession::class.java.classLoader, arrayOf(SSLSession::class.java)) { _, method, _ ->
            if (method.name == "getPeerCertificates") arrayOf<Certificate>(certificate)
            else throw UnsupportedOperationException(method.name)
        } as SSLSession
        val verifier = OkHttpClient().hostnameVerifier
        assertTrue(verifier.verify("k.com", session))
        assertFalse(verifier.verify("K.com", session), "Unicode Kelvin sign must not authenticate as ASCII k")
    }

    @Test fun gzipExtraLengthIsUnsignedAndTruncationIsAnIoFailure() {
        val compressed = ByteArrayOutputStream().also { output -> GZIPOutputStream(output).use { it.write("payload".toByteArray()) } }.toByteArray()
        val header = compressed.copyOfRange(0, 10).also { it[3] = 4 }
        val largeExtra = header + byteArrayOf(0, -128) + ByteArray(32768) + compressed.copyOfRange(10, compressed.size)
        assertEquals("payload", GzipSource(Buffer().write(largeExtra)).buffer().use { it.readUtf8() })
        assertFailsWith<IOException> { GzipSource(Buffer().write(header + byteArrayOf(-1, -1))).buffer().use { it.readUtf8() } }
    }
}
