package com.neuronest.backend.chat.llm;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;

/** Timeouts shared by every provider (see the llm.* entries in application.properties). */
@Component
public class LlmSettings {

    private final Duration connectTimeout;
    private final Duration jsonTimeout;
    private final Duration firstTokenTimeout;
    private final Duration idleTimeout;

    public LlmSettings(@Value("${llm.connect-timeout:PT5S}") Duration connectTimeout,
                       @Value("${llm.json-timeout:PT20S}") Duration jsonTimeout,
                       @Value("${llm.first-token-timeout:PT20S}") Duration firstTokenTimeout,
                       @Value("${llm.idle-timeout:PT30S}") Duration idleTimeout) {
        this.connectTimeout = connectTimeout;
        this.jsonTimeout = jsonTimeout;
        this.firstTokenTimeout = firstTokenTimeout;
        this.idleTimeout = idleTimeout;
    }

    /** TCP/TLS connect. */
    public Duration connectTimeout() { return connectTimeout; }

    /** Whole non-streamed JSON call (safety check), per attempt. */
    public Duration jsonTimeout() { return jsonTimeout; }

    /** From sending a streamed request until the first text chunk arrives. */
    public Duration firstTokenTimeout() { return firstTokenTimeout; }

    /** Longest gap between chunks once streaming has started. */
    public Duration idleTimeout() { return idleTimeout; }
}
