package com.neuronest.backend.voice;

import com.neuronest.backend.chat.llm.GeminiProvider;
import com.neuronest.backend.chat.llm.LlmProviderException;
import com.neuronest.backend.config.ApiValidationException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Gemini and Sarvam are mocked: no network calls. */
class VoiceServiceTest {

    private static final byte[] WEBM = {0x1A, 0x45, (byte) 0xDF, (byte) 0xA3, 0, 0, 0, 0};

    private GeminiProvider gemini;
    private SarvamTtsClient sarvam;
    private VoiceService service;

    @BeforeEach
    void setUp() {
        gemini = mock(GeminiProvider.class);
        sarvam = mock(SarvamTtsClient.class);
        when(gemini.isConfigured()).thenReturn(true);
        service = new VoiceService(gemini, sarvam, JsonMapper.builder().build(), Duration.ofSeconds(5));
    }

    private static MockMultipartFile recording(String contentType, byte[] content) {
        return new MockMultipartFile("audio", "voice", contentType, content);
    }

    @Test
    void transcribesAWebmRecording() {
        when(gemini.transcribeAudio(anyString(), any(), eq("audio/webm"), any(), any()))
                .thenReturn("{\"text\":\"मुझे नींद नहीं आ रही\",\"language\":\"hi\"}");

        VoiceService.Transcription result = service.transcribe(recording("audio/webm;codecs=opus", WEBM));

        assertThat(result.text()).isEqualTo("मुझे नींद नहीं आ रही");
        assertThat(result.language()).isEqualTo("hi");
    }

    @Test
    void emptyTranscriptIsAValidResult() {
        when(gemini.transcribeAudio(anyString(), any(), anyString(), any(), any()))
                .thenReturn("{\"text\":\"  \",\"language\":\"en\"}");

        VoiceService.Transcription result = service.transcribe(recording("audio/webm", WEBM));

        assertThat(result.text()).isEmpty();
        assertThat(result.language()).isEqualTo("unknown");
    }

    @Test
    void unknownLanguageBecomesUnknown() {
        assertThat(service.parse("{\"text\":\"hola\",\"language\":\"es\"}").language()).isEqualTo("unknown");
        assertThat(service.parse("{\"text\":\"hello\"}").language()).isEqualTo("unknown");
    }

    @Test
    void rejectsADisallowedDeclaredType() {
        assertThatThrownBy(() -> service.transcribe(recording("video/mp4", WEBM)))
                .isInstanceOf(VoiceExceptions.UnsupportedAudioException.class);
        verify(gemini, never()).transcribeAudio(anyString(), any(), anyString(), any(), any());
    }

    @Test
    void rejectsContentThatIsNotAudio() {
        byte[] pdf = "%PDF-1.7 not audio".getBytes(StandardCharsets.US_ASCII);
        assertThatThrownBy(() -> service.transcribe(recording("audio/webm", pdf)))
                .isInstanceOf(VoiceExceptions.UnsupportedAudioException.class);
    }

    @Test
    void rejectsAnOversizedRecording() {
        byte[] big = Arrays.copyOf(WEBM, (int) VoiceService.MAX_AUDIO_BYTES + 1);
        assertThatThrownBy(() -> service.transcribe(recording("audio/webm", big)))
                .isInstanceOf(VoiceExceptions.AudioTooLargeException.class);
    }

    @Test
    void rejectsAMissingRecording() {
        assertThatThrownBy(() -> service.transcribe(null)).isInstanceOf(ApiValidationException.class);
        assertThatThrownBy(() -> service.transcribe(recording("audio/webm", new byte[0])))
                .isInstanceOf(ApiValidationException.class);
    }

    @Test
    void mapsGeminiFailuresToFriendlyStatuses() {
        when(gemini.transcribeAudio(anyString(), any(), anyString(), any(), any()))
                .thenThrow(LlmProviderException.forStatus("gemini", 429))
                .thenThrow(new LlmProviderException("gemini", LlmProviderException.Kind.TIMEOUT, null))
                .thenThrow(LlmProviderException.forStatus("gemini", 500));

        assertThat(statusOf(() -> service.transcribe(recording("audio/webm", WEBM)))).isEqualTo(429);
        assertThat(statusOf(() -> service.transcribe(recording("audio/webm", WEBM)))).isEqualTo(504);
        assertThat(statusOf(() -> service.transcribe(recording("audio/webm", WEBM)))).isEqualTo(503);
    }

    @Test
    void recognisesTheAcceptedContainersByTheirBytes() {
        assertThat(VoiceService.audioType(WEBM)).contains("audio/webm");
        assertThat(VoiceService.audioType("OggS\0\0".getBytes(StandardCharsets.ISO_8859_1))).contains("audio/ogg");
        assertThat(VoiceService.audioType("RIFF\0\0\0\0WAVEfmt ".getBytes(StandardCharsets.ISO_8859_1))).contains("audio/wav");
        assertThat(VoiceService.audioType("\0\0\0\u0018ftypM4A ".getBytes(StandardCharsets.ISO_8859_1))).contains("audio/m4a");
        assertThat(VoiceService.audioType("ID3\u0004\0".getBytes(StandardCharsets.ISO_8859_1))).contains("audio/mpeg");
        assertThat(VoiceService.audioType(new byte[] {(byte) 0xFF, (byte) 0xFB, 0})).contains("audio/mpeg");
        assertThat(VoiceService.audioType(new byte[0])).isEmpty();
    }

    @Test
    void speakRejectsAnUnknownVoiceBeforeCallingSarvam() {
        assertThatThrownBy(() -> service.speak(new VoiceService.SpeakRequest("Hello", "alexa", null)))
                .isInstanceOf(ApiValidationException.class);
        verify(sarvam, never()).speak(anyString(), anyString(), anyString());
    }

    @Test
    void speakSendsCleanedTextInDetectedLanguageAndJoinsChunks() {
        when(sarvam.speak(anyString(), anyString(), anyString())).thenReturn(new byte[] {1, 2}, new byte[] {3});
        String longReply = "**Take a slow breath** 🌿. ".repeat(150);

        byte[] audio = service.speak(new VoiceService.SpeakRequest(longReply, "anand", null));

        assertThat(audio).containsExactly(1, 2, 3);
        verify(sarvam, times(2)).speak(anyString(), eq("en-IN"), eq("anand_pa_customer"));
    }

    @Test
    void speakPassesASupportedLanguageCodeThrough() {
        when(sarvam.speak(anyString(), anyString(), anyString())).thenReturn(new byte[] {9});

        service.speak(new VoiceService.SpeakRequest("काळजी करू नका.", "aarti", "mr-IN"));

        verify(sarvam).speak("काळजी करू नका.", "mr-IN", "aarti_hi_customer");
    }

    @Test
    void speakRejectsTextWithNothingToSay() {
        assertThatThrownBy(() -> service.speak(new VoiceService.SpeakRequest("🌿 ✨", "aarti", null)))
                .isInstanceOf(ApiValidationException.class);
        assertThatThrownBy(() -> service.speak(new VoiceService.SpeakRequest(" ", "aarti", null)))
                .isInstanceOf(ApiValidationException.class);
    }

    private static int statusOf(Runnable call) {
        try {
            call.run();
        } catch (VoiceExceptions.TranscriptionUnavailableException e) {
            return e.status();
        }
        throw new AssertionError("expected TranscriptionUnavailableException");
    }
}
