package com.neuronest.backend.voice;

import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * Voice for the AI companion. Open to guests and signed-in users alike, like the chat endpoints
 * (the main security chain): voice is only another way to type or hear a chat message.
 */
@RestController
@RequestMapping("/api/voice")
public class VoiceController {

    private final VoiceService voiceService;

    public VoiceController(VoiceService voiceService) {
        this.voiceService = voiceService;
    }

    /** One recording (multipart field "audio") in, {@code {text, language}} out. Empty text is valid. */
    @PostMapping(path = "/transcribe", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<VoiceService.Transcription> transcribe(@RequestParam(name = "audio", required = false)
                                                                 MultipartFile audio) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(voiceService.transcribe(audio));
    }

    /** {@code {text, voice: "aarti"|"anand", languageCode?}} in, MP3 audio out. */
    @PostMapping("/speak")
    public ResponseEntity<byte[]> speak(@RequestBody(required = false) VoiceService.SpeakRequest request) {
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType("audio/mpeg"))
                .cacheControl(CacheControl.noStore())
                .body(voiceService.speak(request));
    }
}
