package app.smsbridge.data.transfer

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import app.smsbridge.data.backup.toHex
import java.io.InputStream
import java.net.Socket
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.MessageDigest
import java.security.Principal
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import java.security.spec.ECGenParameterSpec
import java.util.Date
import java.util.UUID
import javax.net.ssl.KeyManager
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLEngine
import javax.net.ssl.SSLServerSocket
import javax.net.ssl.SSLSocket
import javax.net.ssl.X509ExtendedKeyManager
import javax.net.ssl.X509TrustManager
import javax.security.auth.x500.X500Principal

/** The receiver's TLS identity for one receiving session. */
interface TlsIdentity {
    val certificate: X509Certificate
    val privateKey: PrivateKey

    /** Removes the key (Android Keystore entries are deleted). */
    fun destroy()
}

fun X509Certificate.sha256Fingerprint(): String = MessageDigest.getInstance("SHA-256").digest(encoded).toHex()

/**
 * A fresh EC P-256 key generated inside Android Keystore (never leaves secure storage) with a
 * self-signed certificate valid for two days. One per "Receive" session, deleted afterwards.
 */
class KeystoreTlsIdentity private constructor(private val alias: String) : TlsIdentity {
    private val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
    override val certificate: X509Certificate = keyStore.getCertificate(alias) as X509Certificate
    override val privateKey: PrivateKey = keyStore.getKey(alias, null) as PrivateKey

    override fun destroy() {
        runCatching { keyStore.deleteEntry(alias) }
    }

    companion object {
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"

        fun create(): KeystoreTlsIdentity {
            val alias = "smsbridge-transfer-${UUID.randomUUID()}"
            val now = System.currentTimeMillis()
            KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, ANDROID_KEYSTORE).apply {
                initialize(
                    KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_SIGN)
                        .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
                        .setDigests(KeyProperties.DIGEST_NONE, KeyProperties.DIGEST_SHA256, KeyProperties.DIGEST_SHA384, KeyProperties.DIGEST_SHA512)
                        .setCertificateSubject(X500Principal("CN=SMS Bridge transfer"))
                        .setCertificateSerialNumber(java.math.BigInteger.valueOf(now))
                        .setCertificateNotBefore(Date(now - DAY))
                        .setCertificateNotAfter(Date(now + DAY))
                        .build(),
                )
            }.generateKeyPair()
            return KeystoreTlsIdentity(alias)
        }

        private const val DAY = 24L * 60 * 60 * 1000
    }
}

/** Identity loaded from a PKCS#12 file. Used by tests (JVM has no Android Keystore). */
class Pkcs12TlsIdentity(input: InputStream, password: CharArray) : TlsIdentity {
    private val keyStore = KeyStore.getInstance("PKCS12").apply { load(input, password) }
    private val alias = keyStore.aliases().nextElement()
    override val certificate = keyStore.getCertificate(alias) as X509Certificate
    override val privateKey = keyStore.getKey(alias, password) as PrivateKey
    override fun destroy() = Unit
}

/** Serves exactly one identity; works with keys that cannot be exported (Android Keystore). */
private class SingleKeyManager(private val identity: TlsIdentity) : X509ExtendedKeyManager() {
    override fun getServerAliases(keyType: String?, issuers: Array<out Principal>?) = arrayOf(ALIAS)
    override fun chooseServerAlias(keyType: String?, issuers: Array<out Principal>?, socket: Socket?) = ALIAS
    override fun chooseEngineServerAlias(keyType: String?, issuers: Array<out Principal>?, engine: SSLEngine?) = ALIAS
    override fun getCertificateChain(alias: String?) = arrayOf(identity.certificate)
    override fun getPrivateKey(alias: String?) = identity.privateKey
    override fun getClientAliases(keyType: String?, issuers: Array<out Principal>?) = null
    override fun chooseClientAlias(keyType: Array<out String>?, issuers: Array<out Principal>?, socket: Socket?) = null

    private companion object {
        const val ALIAS = "transfer"
    }
}

/**
 * Trusts exactly one certificate, identified by the SHA-256 fingerprint from the QR code.
 * No certificate authorities and no host names are involved; anything else is refused.
 *
 * Lint's CustomX509TrustManager warning targets trust managers that weaken validation. This one is
 * strictly narrower than CA validation (a single pinned leaf, compared in constant time) and is
 * covered by `TransferProtocolTest` and `wrongCertificateIsRefusedBeforeAnythingIsSent`.
 */
@android.annotation.SuppressLint("CustomX509TrustManager")
class PinnedTrustManager(private val expectedFingerprint: String) : X509TrustManager {
    override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
        val leaf = chain?.firstOrNull() ?: throw CertificateException("No certificate presented")
        val actual = leaf.sha256Fingerprint().toByteArray()
        if (!MessageDigest.isEqual(actual, expectedFingerprint.toByteArray())) {
            throw CertificateException("Certificate does not match the scanned QR code")
        }
    }

    override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) =
        throw CertificateException("Client certificates are not used")

    override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
}

object TransferTls {
    private val PREFERRED = arrayOf("TLSv1.3", "TLSv1.2")

    fun serverSocket(identity: TlsIdentity, port: Int = 0): SSLServerSocket {
        val context = SSLContext.getInstance("TLS").apply {
            init(arrayOf<KeyManager>(SingleKeyManager(identity)), null, SecureRandom())
        }
        return (context.serverSocketFactory.createServerSocket(port) as SSLServerSocket).apply {
            enabledProtocols = PREFERRED.filter { it in supportedProtocols }.toTypedArray()
            needClientAuth = false
        }
    }

    fun clientSocket(link: PairingLink, connectTimeoutMs: Int): SSLSocket {
        val context = SSLContext.getInstance("TLS").apply {
            init(null, arrayOf(PinnedTrustManager(link.fingerprint)), SecureRandom())
        }
        val socket = context.socketFactory.createSocket() as SSLSocket
        socket.enabledProtocols = PREFERRED.filter { it in socket.supportedProtocols }.toTypedArray()
        socket.connect(java.net.InetSocketAddress(link.host, link.port), connectTimeoutMs)
        socket.startHandshake()
        return socket
    }
}
