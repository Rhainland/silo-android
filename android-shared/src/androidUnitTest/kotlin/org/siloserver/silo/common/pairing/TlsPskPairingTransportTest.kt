package org.siloserver.silo.common.pairing

import java.net.ServerSocket
import java.net.Socket
import java.security.SecureRandom
import java.util.Collections
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlinx.coroutines.runBlocking
import org.bouncycastle.tls.BasicTlsPSKIdentity
import org.bouncycastle.tls.CipherSuite
import org.bouncycastle.tls.PSKTlsClient
import org.bouncycastle.tls.ProtocolVersion
import org.bouncycastle.tls.TlsClientProtocol
import org.bouncycastle.tls.crypto.impl.bc.BcTlsCrypto

class TlsPskPairingTransportTest {
    /**
     * BouncyCastle zeroes the PSK buffer after a TLS 1.2 handshake. iPhones and
     * iPads pair over TLS 1.2, so a TV that handed it a shared key array
     * refused every phone after the first one until the app restarted.
     */
    @Test
    fun tls12PhoneCannotWipeTheTvsPairingKey() {
        val listener = ServerSocket(0)
        val servers = Collections.synchronizedList(mutableListOf<TlsPskPairingTransport>())
        val worker = thread(isDaemon = true, name = "pairing-tls-test-server") {
            repeat(3) { runCatching { servers += TlsPskPairingTransport.accept(listener.accept()) } }
        }
        try {
            repeat(2) {
                val socket = Socket("127.0.0.1", listener.localPort).apply { soTimeout = 10_000 }
                val protocol = TlsClientProtocol(socket.getInputStream(), socket.getOutputStream())
                // Offers only TLS 1.2, like Apple's Network.framework, so a
                // completed handshake is a TLS 1.2 one.
                protocol.connect(Tls12PskClient(BcTlsCrypto(SecureRandom())))
                protocol.close()
                socket.close()
            }
            // An Android phone (TLS 1.3) still gets through afterwards.
            runBlocking { TlsPskPairingClientTransport.connect("127.0.0.1", listener.localPort) }.close()
        } finally {
            // The last server-side handshake can still be finishing.
            worker.join(5_000)
            listener.close()
            synchronized(servers) { servers.toList() }.forEach { it.close() }
        }
    }

    private class Tls12PskClient(crypto: BcTlsCrypto) : PSKTlsClient(
        crypto,
        BasicTlsPSKIdentity("silo-pairing".toByteArray(), "silo-companion-pairing-v1".toByteArray()),
    ) {
        override fun getSupportedCipherSuites(): IntArray = intArrayOf(CipherSuite.TLS_PSK_WITH_AES_128_GCM_SHA256)

        override fun getSupportedVersions(): Array<ProtocolVersion> = arrayOf(ProtocolVersion.TLSv12)
    }
}
