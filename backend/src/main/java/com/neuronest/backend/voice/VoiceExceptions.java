package com.neuronest.backend.voice;

/** Voice failures, each with the short message shown to the user (never content or provider details). */
public final class VoiceExceptions {

    private VoiceExceptions() {}

    /** 503: speech could not be produced (no key, Sarvam error or timeout); the browser voice takes over. */
    public static class SpeechUnavailableException extends RuntimeException {
        public SpeechUnavailableException() {
            super("Neuro's voice is unavailable right now.");
        }
    }

    /** 503 / 429 / 504: the recording could not be transcribed. */
    public static class TranscriptionUnavailableException extends RuntimeException {
        private final int status;

        public TranscriptionUnavailableException(int status, String message) {
            super(message);
            this.status = status;
        }

        public int status() {
            return status;
        }
    }

    /** 413: the recording is over the size limit. */
    public static class AudioTooLargeException extends RuntimeException {
        public AudioTooLargeException() {
            super("That recording is too long. Please try a shorter message.");
        }
    }

    /** 415: not one of the accepted audio formats. */
    public static class UnsupportedAudioException extends RuntimeException {
        public UnsupportedAudioException() {
            super("That recording format isn't supported. Please try again or type your message.");
        }
    }
}
