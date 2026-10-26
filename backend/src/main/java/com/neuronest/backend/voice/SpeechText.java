package com.neuronest.backend.voice;

import com.neuronest.backend.config.ApiValidationException;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Turns a chat reply into text Sarvam can speak: the voice whitelist, the language to speak in,
 * markdown/emoji/URL removal, and chunks under Sarvam's per-request character limit.
 */
final class SpeechText {

    /** Sarvam bulbul:v4-flash accepts at most this many characters per request. */
    static final int MAX_CHUNK_CHARS = 2500;
    /** Longer replies are cut at a sentence boundary before this many characters. */
    static final int MAX_TOTAL_CHARS = 4000;

    // The only voices offered; anything else is rejected rather than passed on to Sarvam.
    private static final Map<String, String> SPEAKERS = Map.of(
            "aarti", "aarti_hi_customer",
            "anand", "anand_pa_customer");
    private static final Set<String> LANGUAGE_CODES = Set.of("hi-IN", "mr-IN", "en-IN");

    private static final Pattern DEVANAGARI = Pattern.compile("[\\u0900-\\u097F]");
    private static final Pattern MARKDOWN_LINK = Pattern.compile("\\[([^\\]]*)]\\([^)]*\\)");
    private static final Pattern URL = Pattern.compile("(?i)\\b(?:https?://|www\\.)\\S+");
    private static final Pattern MARKDOWN_SYMBOLS = Pattern.compile("[*_#`~>|]+");
    private static final Pattern LIST_MARKER = Pattern.compile("(?m)^\\s*(?:[-+]|\\d+[.)])\\s+");
    private static final Pattern EMOJI = Pattern.compile(
            "[\\x{1F000}-\\x{1FAFF}\\x{2600}-\\x{27BF}\\x{2300}-\\x{23FF}\\x{2B00}-\\x{2BFF}"
                    + "\\x{1F1E6}-\\x{1F1FF}\\x{FE0F}\\x{200D}\\x{20E3}]");
    private static final Pattern SPACES = Pattern.compile("[ \\t]+");
    private static final Pattern BLANK_LINES = Pattern.compile("\\s*\\n\\s*");
    // A sentence ends at . ! ? or the Devanagari danda, followed by whitespace.
    private static final Pattern SENTENCE_END = Pattern.compile("(?<=[.!?\\u0964\\u0965])\\s+");

    private SpeechText() {}

    /** Sarvam speaker id for "aarti" / "anand"; anything else is a 400. */
    static String speakerFor(String voice) {
        String speaker = voice == null ? null : SPEAKERS.get(voice);
        if (speaker == null) {
            throw new ApiValidationException("voice must be \"aarti\" or \"anand\".");
        }
        return speaker;
    }

    /** The requested language when supported; otherwise Hindi for Devanagari text, else Indian English. */
    static String languageFor(String languageCode, String text) {
        if (languageCode != null && LANGUAGE_CODES.contains(languageCode)) {
            return languageCode;
        }
        return text != null && DEVANAGARI.matcher(text).find() ? "hi-IN" : "en-IN";
    }

    /** Removes what should not be read aloud: links, URLs, markdown symbols, list markers and emoji. */
    static String clean(String text) {
        if (text == null) {
            return "";
        }
        String out = MARKDOWN_LINK.matcher(text).replaceAll("$1");
        out = URL.matcher(out).replaceAll("");
        out = LIST_MARKER.matcher(out).replaceAll("");
        out = MARKDOWN_SYMBOLS.matcher(out).replaceAll("");
        out = EMOJI.matcher(out).replaceAll("");
        out = SPACES.matcher(out).replaceAll(" ");
        out = BLANK_LINES.matcher(out).replaceAll("\n");
        return out.strip();
    }

    /** Cuts text longer than {@link #MAX_TOTAL_CHARS} at the last sentence end that fits. */
    static String capLength(String text) {
        if (text.length() <= MAX_TOTAL_CHARS) {
            return text;
        }
        String head = text.substring(0, MAX_TOTAL_CHARS);
        int cut = lastSentenceEnd(head);
        return (cut > 0 ? head.substring(0, cut) : head).strip();
    }

    /**
     * Splits text into pieces of at most {@code maxChars}, at sentence ends where possible; a single
     * sentence longer than that is split at spaces, and a run without spaces at the limit.
     */
    static List<String> chunk(String text, int maxChars) {
        List<String> chunks = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        for (String sentence : SENTENCE_END.split(text.strip())) {
            for (String piece : splitLong(sentence, maxChars)) {
                if (!current.isEmpty() && current.length() + 1 + piece.length() > maxChars) {
                    chunks.add(current.toString());
                    current.setLength(0);
                }
                if (!current.isEmpty()) {
                    current.append(' ');
                }
                current.append(piece);
            }
        }
        if (!current.isEmpty()) {
            chunks.add(current.toString());
        }
        return chunks;
    }

    private static List<String> splitLong(String sentence, int maxChars) {
        List<String> pieces = new ArrayList<>();
        String rest = sentence.strip();
        while (rest.length() > maxChars) {
            int cut = rest.lastIndexOf(' ', maxChars);
            if (cut <= 0) {
                cut = maxChars;
            }
            pieces.add(rest.substring(0, cut).strip());
            rest = rest.substring(cut).strip();
        }
        if (!rest.isEmpty()) {
            pieces.add(rest);
        }
        return pieces;
    }

    private static int lastSentenceEnd(String text) {
        for (int i = text.length() - 1; i > 0; i--) {
            char c = text.charAt(i);
            if (c == '.' || c == '!' || c == '?' || c == '।' || c == '॥') {
                return i + 1;
            }
        }
        return -1;
    }
}
