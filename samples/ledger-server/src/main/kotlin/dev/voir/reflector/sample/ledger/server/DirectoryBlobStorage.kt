package dev.voir.reflector.sample.ledger.server

import dev.voir.reflector.sync.protocol.CollectionId
import dev.voir.reflector.sync.protocol.ScopeId
import dev.voir.reflector.sync.protocol.blob.BlobDescriptor
import dev.voir.reflector.sync.protocol.blob.BlobTicket
import dev.voir.reflector.sync.protocol.blob.BlobTicketMethod
import dev.voir.reflector.sync.server.BlobStorage
import dev.voir.reflector.sync.server.BlobStorageKey
import dev.voir.reflector.sync.server.BlobVerification
import dev.voir.reflector.sync.server.StoredBlob
import java.io.File
import java.net.URLEncoder
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.io.encoding.Base64
import kotlin.time.Clock
import kotlin.time.Duration

/**
 * Object storage for the reference host: a directory, and URLs signed with an HMAC.
 *
 * This exists so that the sample runs with no cloud account and no credentials, and so that the
 * module's side of files can be read without S3 in the way. The shape is exactly what a bucket has —
 * a key chosen by the host, a signed URL with an expiry, a `HEAD` that reports what is stored — and
 * swapping this class for the AWS SDK changes nothing else in this host and nothing at all in the
 * module.
 *
 * The signature is not decoration. A ticket is handed to a device and is the only thing standing
 * between that device and every other user's files, so the endpoints this presigns verify it before
 * touching a byte. A real deployment gets the same property from its provider's presigning.
 *
 * @property directory Where objects are stored; created on first use.
 * @property baseUrl Root the signed URLs point back at, which is this host itself.
 * @property secret Key the signatures are made with.
 * @property ticketLife How long a signature is honoured.
 * @property clock Source of expiry times.
 */
class DirectoryBlobStorage(
    private val directory: File,
    private val baseUrl: String,
    private val secret: ByteArray,
    private val ticketLife: Duration,
    private val clock: Clock = Clock.System,
) : BlobStorage {
    /** Keys the module has handed over as no longer referenced, in order. */
    val released: MutableList<BlobStorageKey> = mutableListOf()

    init {
        directory.mkdirs()
    }

    override fun keyFor(
        scope: ScopeId,
        collection: CollectionId,
        blob: BlobDescriptor,
    ): BlobStorageKey = BlobStorageKey("${scope.value}/${collection.value}/${blob.blobId.value}")

    override fun createUpload(
        scope: ScopeId,
        collection: CollectionId,
        blob: StoredBlob,
    ): BlobTicket = ticket(BlobTicketMethod.PUT, blob.storageKey)

    override fun createDownload(
        scope: ScopeId,
        collection: CollectionId,
        blob: StoredBlob,
    ): BlobTicket = ticket(BlobTicketMethod.GET, blob.storageKey)

    override fun verify(
        scope: ScopeId,
        collection: CollectionId,
        blob: StoredBlob,
    ): BlobVerification {
        val file = fileFor(blob.storageKey)
        // What a HEAD against a bucket answers, and all the module asks for: the host observes, and
        // the module decides whether it agrees with what the client declared.
        return if (file.isFile) BlobVerification.Stored(file.length()) else BlobVerification.Absent
    }

    override fun onReleased(
        scope: ScopeId,
        collection: CollectionId,
        blobs: List<StoredBlob>,
    ) {
        released += blobs.map { it.storageKey }
        // This host's disposal policy, stated in one line: delete at once. A host with a retention
        // rule, a legal hold or a versioned bucket would do something else here, and the module
        // neither knows nor asks — it has already forgotten these files.
        blobs.forEach { fileFor(it.storageKey).delete() }
    }

    /**
     * Resolves a key to a file, refusing anything that could escape the directory.
     *
     * The key comes back from the module having travelled through a URL, so it is treated as input
     * rather than as something this class wrote: a key containing `..` would otherwise read and
     * write wherever the process can reach.
     *
     * @param key Key to resolve.
     * @return File the object is stored in.
     */
    fun fileFor(key: BlobStorageKey): File {
        val resolved = File(directory, key.value).canonicalFile
        require(resolved.path.startsWith(directory.canonicalFile.path + File.separator)) {
            "the storage key escapes the storage directory"
        }
        return resolved
    }

    /**
     * Checks a signature presented by a device, and that it has not expired.
     *
     * @param key Object being addressed.
     * @param method Method being used.
     * @param expiresAt Expiry the signature was made over.
     * @param signature Signature presented.
     * @return Whether the request may proceed.
     */
    fun isValid(
        key: BlobStorageKey,
        method: String,
        expiresAt: Long,
        signature: String,
    ): Boolean = expiresAt > clock.now().toEpochMilliseconds() && sign(key, method, expiresAt) == signature

    private fun ticket(
        method: BlobTicketMethod,
        key: BlobStorageKey,
    ): BlobTicket {
        val expiresAt = clock.now() + ticketLife
        val signature = sign(key, method.name, expiresAt.toEpochMilliseconds())
        val encoded = URLEncoder.encode(key.value, Charsets.UTF_8)
        return BlobTicket(
            method = method,
            url = "$baseUrl/files?key=$encoded&expires=${expiresAt.toEpochMilliseconds()}&sig=$signature",
            headers = emptyMap(),
            expiresAt = expiresAt,
        )
    }

    private fun sign(
        key: BlobStorageKey,
        method: String,
        expiresAt: Long,
    ): String {
        val mac = Mac.getInstance(ALGORITHM)
        mac.init(SecretKeySpec(secret, ALGORITHM))
        return Base64.UrlSafe.encode(mac.doFinal("$method\n${key.value}\n$expiresAt".encodeToByteArray()))
    }

    private companion object {
        const val ALGORITHM = "HmacSHA256"
    }
}
