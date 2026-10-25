package com.neuronest.backend.pro;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class ProPhotoServiceTest {

    @Test
    void recognisesJpegPngAndWebpByTheirBytes() {
        assertThat(ProPhotoService.imageType(new byte[] {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, 0})).contains("image/jpeg");
        assertThat(ProPhotoService.imageType(new byte[] {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A})).contains("image/png");
        assertThat(ProPhotoService.imageType("RIFF\0\0\0\0WEBPVP8 ".getBytes(StandardCharsets.ISO_8859_1))).contains("image/webp");
    }

    @Test
    void rejectsOtherFiles() {
        assertThat(ProPhotoService.imageType("%PDF-1.7".getBytes(StandardCharsets.US_ASCII))).isEmpty();
        assertThat(ProPhotoService.imageType("GIF89a".getBytes(StandardCharsets.US_ASCII))).isEmpty();
        assertThat(ProPhotoService.imageType("RIFF\0\0\0\0WAVE".getBytes(StandardCharsets.ISO_8859_1))).isEmpty();
        assertThat(ProPhotoService.imageType(new byte[0])).isEmpty();
    }
}
