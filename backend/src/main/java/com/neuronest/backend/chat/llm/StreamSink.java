package com.neuronest.backend.chat.llm;

import java.util.function.Consumer;

/**
 * Receives streamed text and tells the router whether switching provider is still safe.
 * A sink may buffer text it hasn't shown yet (e.g. a hidden header); the router can fall
 * back to another provider as long as {@link #committed()} is false, after calling
 * {@link #reset()} to drop that provider's partial output.
 */
public interface StreamSink {

    void accept(String text);

    /** True once anything derived from this stream reached the user. */
    boolean committed();

    /** Discards buffered, uncommitted output before another provider is tried. */
    void reset();

    /** A sink that shows every chunk immediately: committed after the first one. */
    static StreamSink immediate(Consumer<String> onToken) {
        return new StreamSink() {
            private boolean committed;

            @Override
            public void accept(String text) {
                committed = true;
                onToken.accept(text);
            }

            @Override
            public boolean committed() {
                return committed;
            }

            @Override
            public void reset() {
            }
        };
    }
}
