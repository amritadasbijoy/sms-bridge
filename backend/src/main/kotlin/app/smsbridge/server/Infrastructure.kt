package app.smsbridge.server

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import java.security.MessageDigest
import java.security.SecureRandom
import java.sql.Connection
import java.sql.ResultSet
import java.time.OffsetDateTime
import java.util.Base64
import java.util.UUID
import javax.sql.DataSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.bouncycastle.crypto.generators.Argon2BytesGenerator
import org.bouncycastle.crypto.params.Argon2Parameters
import org.flywaydb.core.Flyway

/** All configuration comes from environment variables (see infrastructure/.env.example). */
data class Config(
    val port: Int,
    val dbUrl: String,
    val dbUser: String,
    val dbPassword: String,
    /** Secure cookies require HTTPS. Only disable for local development over plain HTTP. */
    val cookieSecure: Boolean,
    /** The first account can always be created; more only when this is true. */
    val allowRegistration: Boolean,
    /** Honour X-Forwarded-For/Proto only when running behind our own reverse proxy. */
    val trustProxy: Boolean,
) {
    companion object {
        fun fromEnv(env: Map<String, String> = System.getenv()) = Config(
            port = env["SMSBRIDGE_PORT"]?.toInt() ?: 8080,
            dbUrl = env["SMSBRIDGE_DB_URL"] ?: error("SMSBRIDGE_DB_URL is required"),
            dbUser = env["SMSBRIDGE_DB_USER"] ?: error("SMSBRIDGE_DB_USER is required"),
            dbPassword = env["SMSBRIDGE_DB_PASSWORD"] ?: error("SMSBRIDGE_DB_PASSWORD is required"),
            cookieSecure = env["SMSBRIDGE_COOKIE_SECURE"]?.toBooleanStrict() ?: true,
            allowRegistration = env["SMSBRIDGE_ALLOW_REGISTRATION"]?.toBooleanStrict() ?: false,
            trustProxy = env["SMSBRIDGE_TRUST_PROXY"]?.toBooleanStrict() ?: false,
        )
    }
}

/** JDBC with explicit transactions and bound parameters only (no string-built SQL with values). */
class Db(private val dataSource: DataSource) {
    suspend fun <T> tx(block: Connection.() -> T): T = withContext(Dispatchers.IO) {
        dataSource.connection.use { c ->
            c.autoCommit = false
            try {
                c.block().also { c.commit() }
            } catch (e: Throwable) {
                c.rollback()
                throw e
            }
        }
    }

    companion object {
        fun connect(config: Config): Pair<Db, HikariDataSource> {
            val ds = HikariDataSource(
                HikariConfig().apply {
                    jdbcUrl = config.dbUrl
                    username = config.dbUser
                    password = config.dbPassword
                    maximumPoolSize = 10
                    poolName = "smsbridge"
                },
            )
            Flyway.configure().dataSource(ds).locations("classpath:db/migration").load().migrate()
            return Db(ds) to ds
        }
    }
}

private fun Connection.prepare(sql: String, args: Array<out Any?>) = prepareStatement(sql).apply {
    args.forEachIndexed { i, arg ->
        when (arg) {
            null -> setObject(i + 1, null)
            is ByteArray -> setBytes(i + 1, arg)
            else -> setObject(i + 1, arg)
        }
    }
}

fun Connection.update(sql: String, vararg args: Any?): Int = prepare(sql, args).use { it.executeUpdate() }

fun <T> Connection.query(sql: String, vararg args: Any?, map: (ResultSet) -> T): List<T> =
    prepare(sql, args).use { st -> st.executeQuery().use { rs -> buildList { while (rs.next()) add(map(rs)) } } }

fun ResultSet.uuid(column: String): UUID = getObject(column, UUID::class.java)
fun ResultSet.time(column: String): OffsetDateTime? = getObject(column, OffsetDateTime::class.java)

/** Argon2id with OWASP's recommended minimum (19 MiB, 2 iterations, 1 lane), PHC string format. */
object Passwords {
    private const val MEMORY_KIB = 19 * 1024
    private const val ITERATIONS = 2
    private const val PARALLELISM = 1
    private const val HASH_BYTES = 32
    const val MIN_LENGTH = 12
    const val MAX_LENGTH = 256
    private val random = SecureRandom()
    private val b64 = Base64.getEncoder().withoutPadding()

    fun hash(password: CharArray): String {
        val salt = ByteArray(16).also(random::nextBytes)
        val out = derive(password, salt, MEMORY_KIB, ITERATIONS, PARALLELISM)
        return "\$argon2id\$v=19\$m=$MEMORY_KIB,t=$ITERATIONS,p=$PARALLELISM\$${b64.encodeToString(salt)}\$${b64.encodeToString(out)}"
    }

    fun verify(password: CharArray, encoded: String): Boolean {
        val parts = encoded.split('$')
        if (parts.size != 6 || parts[1] != "argon2id") return false
        val params = parts[3].split(',').associate { it.substringBefore('=') to it.substringAfter('=').toInt() }
        val salt = Base64.getDecoder().decode(parts[4])
        val expected = Base64.getDecoder().decode(parts[5])
        val actual = derive(password, salt, params.getValue("m"), params.getValue("t"), params.getValue("p"), expected.size)
        return MessageDigest.isEqual(actual, expected)
    }

    private fun derive(password: CharArray, salt: ByteArray, memory: Int, iterations: Int, parallelism: Int, size: Int = HASH_BYTES): ByteArray {
        val generator = Argon2BytesGenerator()
        generator.init(
            Argon2Parameters.Builder(Argon2Parameters.ARGON2_id)
                .withVersion(Argon2Parameters.ARGON2_VERSION_13)
                .withMemoryAsKB(memory)
                .withIterations(iterations)
                .withParallelism(parallelism)
                .withSalt(salt)
                .build(),
        )
        val out = ByteArray(size)
        val bytes = String(password).toByteArray(Charsets.UTF_8)
        try {
            generator.generateBytes(bytes, out)
        } finally {
            bytes.fill(0)
        }
        return out
    }
}

/** Random secrets handed to clients; only their SHA-256 is stored. */
object Secrets {
    private val random = SecureRandom()
    private const val CROCKFORD = "0123456789ABCDEFGHJKMNPQRSTVWXYZ"

    fun token(): String = Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32).also(random::nextBytes))

    /** 10 Crockford base32 characters (50 bits), shown as XXXXX-XXXXX. Short-lived and single-use. */
    fun pairingCode(): String = (1..10).map { CROCKFORD[random.nextInt(32)] }.joinToString("").let { "${it.take(5)}-${it.drop(5)}" }

    fun normalizePairingCode(input: String): String =
        input.uppercase().filter { it.isLetterOrDigit() }.replace('O', '0').replace('I', '1').replace('L', '1')

    fun sha256(value: String): ByteArray = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
}
