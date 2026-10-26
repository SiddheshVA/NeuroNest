package com.neuronest.backend.voice;

import com.neuronest.backend.config.ApiValidationException;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SpeechTextTest {

    @Test
    void mapsTheTwoOfferedVoicesToSarvamSpeakers() {
        assertThat(SpeechText.speakerFor("aarti")).isEqualTo("aarti_hi_customer");
        assertThat(SpeechText.speakerFor("anand")).isEqualTo("anand_pa_customer");
    }

    @Test
    void rejectsAnyOtherVoice() {
        for (String voice : new String[] {null, "", "Aarti", "shubh", "aarti_hi_customer", "anand "}) {
            assertThatThrownBy(() -> SpeechText.speakerFor(voice)).isInstanceOf(ApiValidationException.class);
        }
    }

    @Test
    void usesASupportedLanguageCodeAsGiven() {
        assertThat(SpeechText.languageFor("mr-IN", "Hello")).isEqualTo("mr-IN");
        assertThat(SpeechText.languageFor("en-IN", "नमस्ते")).isEqualTo("en-IN");
        assertThat(SpeechText.languageFor("hi-IN", "Hello")).isEqualTo("hi-IN");
    }

    @Test
    void fallsBackToHindiForDevanagariAndEnglishOtherwise() {
        assertThat(SpeechText.languageFor(null, "मुझे नींद नहीं आ रही")).isEqualTo("hi-IN");
        assertThat(SpeechText.languageFor("ta-IN", "Take a slow breath, आराम से")).isEqualTo("hi-IN");
        assertThat(SpeechText.languageFor(null, "Take a slow breath.")).isEqualTo("en-IN");
        assertThat(SpeechText.languageFor("fr-FR", "Take a slow breath.")).isEqualTo("en-IN");
    }

    @Test
    void stripsMarkdownEmojiAndUrls() {
        String reply = """
                ## Try this 🌿
                **Breathe in** for _four_ counts 😊.
                - Read [this guide](https://example.com/guide) or visit www.example.org today.
                1. Call `14416` ✨
                """;

        String cleaned = SpeechText.clean(reply);

        assertThat(cleaned).isEqualTo("""
                Try this
                Breathe in for four counts .
                Read this guide or visit today.
                Call 14416""");
        assertThat(cleaned).doesNotContain("*", "#", "`", "http", "www", "🌿", "😊", "✨");
    }

    @Test
    void keepsDevanagariAndRupeeSignWhenCleaning() {
        assertThat(SpeechText.clean("**शांत रहें।** फ़ीस ₹600 है।")).isEqualTo("शांत रहें। फ़ीस ₹600 है।");
    }

    @Test
    void chunksAtSentenceBoundariesUnderTheLimit() {
        String sentence = "This is one calm sentence that is about sixty characters. ";
        String text = sentence.repeat(100).strip();

        List<String> chunks = SpeechText.chunk(text, SpeechText.MAX_CHUNK_CHARS);

        assertThat(chunks).hasSizeGreaterThan(1);
        assertThat(chunks).allSatisfy(chunk -> {
            assertThat(chunk.length()).isLessThanOrEqualTo(SpeechText.MAX_CHUNK_CHARS);
            assertThat(chunk).endsWith(".");
        });
        assertThat(String.join(" ", chunks)).isEqualTo(text);
    }

    @Test
    void chunksDevanagariAtTheDanda() {
        String text = "मैं यहाँ हूँ। ".repeat(400).strip();

        List<String> chunks = SpeechText.chunk(text, SpeechText.MAX_CHUNK_CHARS);

        assertThat(chunks).allSatisfy(chunk -> {
            assertThat(chunk.length()).isLessThanOrEqualTo(SpeechText.MAX_CHUNK_CHARS);
            assertThat(chunk).endsWith("।");
        });
        assertThat(String.join(" ", chunks)).isEqualTo(text);
    }

    @Test
    void splitsASingleOverlongSentenceAtSpaces() {
        String text = "word ".repeat(1200).strip();

        List<String> chunks = SpeechText.chunk(text, SpeechText.MAX_CHUNK_CHARS);

        assertThat(chunks).allSatisfy(chunk -> assertThat(chunk.length()).isLessThanOrEqualTo(SpeechText.MAX_CHUNK_CHARS));
        assertThat(String.join(" ", chunks)).isEqualTo(text);
    }

    @Test
    void shortTextIsOneChunk() {
        assertThat(SpeechText.chunk("Hi, I'm Neuro. I'm here to listen.", SpeechText.MAX_CHUNK_CHARS))
                .containsExactly("Hi, I'm Neuro. I'm here to listen.");
    }

    @Test
    void capsLongTextAtTheLastSentenceThatFits() {
        String text = "A short sentence here. ".repeat(300);

        String capped = SpeechText.capLength(text);

        assertThat(capped.length()).isLessThanOrEqualTo(SpeechText.MAX_TOTAL_CHARS);
        assertThat(capped).endsWith(".");
        assertThat(SpeechText.capLength("Short.")).isEqualTo("Short.");
    }
}
