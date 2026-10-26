package com.neuronest.backend.voice;

import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.MultipartException;

import java.util.Map;

// Error mapping for the voice endpoints only, in the app's usual {"error": "..."} shape. Plain
// validation errors (400) go through the global ApiExceptionHandler.
@RestControllerAdvice(basePackageClasses = VoiceExceptionHandler.class)
@Order(Ordered.HIGHEST_PRECEDENCE)
public class VoiceExceptionHandler {

    @ExceptionHandler(VoiceExceptions.SpeechUnavailableException.class)
    public ResponseEntity<?> handleSpeechUnavailable(VoiceExceptions.SpeechUnavailableException ex) {
        return error(HttpStatus.SERVICE_UNAVAILABLE, ex.getMessage());
    }

    @ExceptionHandler(VoiceExceptions.TranscriptionUnavailableException.class)
    public ResponseEntity<?> handleTranscriptionUnavailable(VoiceExceptions.TranscriptionUnavailableException ex) {
        return error(HttpStatus.valueOf(ex.status()), ex.getMessage());
    }

    @ExceptionHandler({VoiceExceptions.AudioTooLargeException.class, MaxUploadSizeExceededException.class})
    public ResponseEntity<?> handleTooLarge(Exception ex) {
        return error(HttpStatus.CONTENT_TOO_LARGE, new VoiceExceptions.AudioTooLargeException().getMessage());
    }

    @ExceptionHandler(VoiceExceptions.UnsupportedAudioException.class)
    public ResponseEntity<?> handleUnsupported(VoiceExceptions.UnsupportedAudioException ex) {
        return error(HttpStatus.UNSUPPORTED_MEDIA_TYPE, ex.getMessage());
    }

    @ExceptionHandler(MultipartException.class)
    public ResponseEntity<?> handleMultipart(MultipartException ex) {
        return error(HttpStatus.BAD_REQUEST, "The recording could not be read. Please try again.");
    }

    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    public ResponseEntity<?> handleMediaType(HttpMediaTypeNotSupportedException ex) {
        return error(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "This request has the wrong content type.");
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<?> handleUnreadable(HttpMessageNotReadableException ex) {
        return error(HttpStatus.BAD_REQUEST, "Request body is missing or is not valid JSON.");
    }

    private static ResponseEntity<?> error(HttpStatus status, String message) {
        return ResponseEntity.status(status).body(Map.of("error", message));
    }
}
