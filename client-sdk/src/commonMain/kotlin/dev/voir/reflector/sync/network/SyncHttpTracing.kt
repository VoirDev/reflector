package dev.voir.reflector.sync.network

/**
 * How much of each HTTP exchange the transport describes to the log.
 *
 * A small enum of this library's own rather than the HTTP client's, for two reasons. The client's
 * tracing is an implementation detail that does not belong in the signature of
 * [syncHttpClient], and — more to the point — its scale runs all the way to logging request and
 * response bodies. Those bodies are the application's business data, on its way to the server, and
 * a diagnostic channel is the wrong place for it to reappear. There is no entry here for them, so
 * the rule is enforced by the type rather than by remembering it.
 */
public enum class SyncHttpTracing {
    /** No tracing. The plugin is not installed at all, so an exchange costs nothing extra. */
    NONE,

    /** Method, address, status and timing — enough to see which call failed and how slowly. */
    BASIC,

    /**
     * The above, and the headers.
     *
     * `Authorization` is replaced before it is written: a log with a bearer token in it is a
     * credential leak, and debug logs travel further than the people who enabled them expect.
     */
    HEADERS,
}
