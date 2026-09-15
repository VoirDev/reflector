package dev.voir.reflector.sync.protocol.blob

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * HTTP method a blob ticket has to be used with.
 *
 * Part of the ticket rather than assumed from its direction, because the host chooses how its
 * storage is addressed: a presigned S3 upload is a `PUT`, a browser-style form upload is a `POST`,
 * and a download is a `GET`. The client performs exactly what it was handed and interprets none of
 * it.
 */
@Serializable
public enum class BlobTicketMethod {
    /** Read the object. */
    @SerialName("GET")
    GET,

    /** Write the object whole, which is what a presigned upload usually is. */
    @SerialName("PUT")
    PUT,

    /** Write the object through a form-style endpoint. */
    @SerialName("POST")
    POST,
}
