package com.neuronest.backend.chat.llm;

/**
 * A provider call that failed. Messages carry the provider name, failure kind and
 * HTTP status only — never prompt or reply content, so they are safe to log.
 */
public class LlmProviderException extends RuntimeException {

    public enum Kind {
        /** HTTP 429. */
        RATE_LIMITED,
        /** HTTP 5xx. */
        SERVER_ERROR,
        /** Other HTTP 4xx (bad key, bad request, unknown model) or provider not configured. */
        CLIENT_ERROR,
        /** No response, or no data, within the configured time. */
        TIMEOUT,
        /** Connection refused/reset, DNS failure, stream dropped. */
        CONNECTION,
        /** Unparseable, blocked or empty response. */
        BAD_RESPONSE
    }

    private final String provider;
    private final Kind kind;
    private final Integer status;
    private final boolean tokensEmitted;
    private final String detail;

    /**
     * @param detail optional content-free reason for logs, e.g. "blocked: SAFETY" or "empty reply"
     */
    public LlmProviderException(String provider, Kind kind, Integer status, boolean tokensEmitted,
                                Throwable cause, String detail) {
        super(provider + ": " + kind + (status != null ? " (HTTP " + status + ")" : "")
                + (detail != null ? " [" + detail + "]" : "")
                + (tokensEmitted ? " after streaming had started" : ""), cause);
        this.provider = provider;
        this.kind = kind;
        this.status = status;
        this.tokensEmitted = tokensEmitted;
        this.detail = detail;
    }

    public LlmProviderException(String provider, Kind kind, Integer status, boolean tokensEmitted, Throwable cause) {
        this(provider, kind, status, tokensEmitted, cause, null);
    }

    public LlmProviderException(String provider, Kind kind, Integer status) {
        this(provider, kind, status, false, null, null);
    }

    /** Unusable response (blocked, empty, malformed), with a content-free reason. */
    public static LlmProviderException badResponse(String provider, String detail) {
        return new LlmProviderException(provider, Kind.BAD_RESPONSE, null, false, null, detail);
    }

    public static LlmProviderException forStatus(String provider, int status) {
        return forStatus(provider, status, null);
    }

    /**
     * @param errorBody the provider's error response, kept (shortened) for logs. Provider error
     *        bodies describe the request problem (bad parameter, quota, unknown model); they
     *        don't echo message content or the API key.
     */
    public static LlmProviderException forStatus(String provider, int status, String errorBody) {
        Kind kind = status == 429 ? Kind.RATE_LIMITED : status >= 500 ? Kind.SERVER_ERROR : Kind.CLIENT_ERROR;
        return new LlmProviderException(provider, kind, status, false, null, shorten(errorBody));
    }

    private static final int MAX_DETAIL_CHARS = 300;

    static String shorten(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        String flat = text.replaceAll("\\s+", " ").trim();
        return flat.length() <= MAX_DETAIL_CHARS ? flat : flat.substring(0, MAX_DETAIL_CHARS) + "...";
    }

    /** Copy of this failure recording whether any text had already reached the user. */
    public LlmProviderException withTokensEmitted(boolean emitted) {
        return emitted == tokensEmitted ? this
                : new LlmProviderException(provider, kind, status, emitted, getCause(), detail);
    }

    public String provider() { return provider; }
    public Kind kind() { return kind; }
    public Integer status() { return status; }

    /** True once part of the reply was streamed: switching provider would then mix two replies. */
    public boolean tokensEmitted() { return tokensEmitted; }

    /** Transient failures worth retrying on the same provider (Gemini's own retry loop). */
    public boolean isTransient() {
        return kind == Kind.RATE_LIMITED || kind == Kind.SERVER_ERROR
                || kind == Kind.TIMEOUT || kind == Kind.CONNECTION;
    }
}
