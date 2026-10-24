package com.neuronest.backend.chat;

import com.neuronest.backend.safety.CrisisResources;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class ChatControllerStreamTest {

    private final ChatService chatService = mock(ChatService.class);
    private final ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
    private MockMvc mvc;
    private final UUID sessionId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        executor.initialize();
        mvc = MockMvcBuilders
                .standaloneSetup(new ChatController(chatService, executor, Duration.ofSeconds(10)))
                .build();
        when(chatService.prepareTurn(any(), anyString(), any()))
                .thenReturn(new ChatService.PreparedTurn(UUID.randomUUID(), List.of(), null, false, "hi"));
    }

    @AfterEach
    void tearDown() {
        executor.shutdown();
    }

    private String streamBody() throws Exception {
        MvcResult started = mvc.perform(post("/api/sessions/{id}/chat/stream", sessionId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .accept(MediaType.TEXT_EVENT_STREAM, MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"hi\"}"))
                .andExpect(request().asyncStarted())
                .andExpect(header().string("X-Accel-Buffering", "no"))
                .andReturn();
        return mvc.perform(asyncDispatch(started))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
    }

    @Test
    void streamsMetaThenTokensThenDoneAsSse() throws Exception {
        UUID messageId = UUID.randomUUID();
        doAnswer(invocation -> {
            ChatTurnListener listener = invocation.getArgument(1);
            listener.onMeta(new SafetyAssessment(true, "self-harm intent", 1, "seek_help", null,
                    CrisisResources.INDIA_RESOURCES));
            listener.onToken("Main ");
            listener.onToken("yahin hoon.\nBatao?");
            listener.onDone(messageId);
            return null;
        }).when(chatService).runTurn(any(), any());

        String body = streamBody();

        int meta = body.indexOf("event:meta");
        int token = body.indexOf("event:token");
        int done = body.indexOf("event:done");
        assertThat(meta).isGreaterThanOrEqualTo(0).isLessThan(token);
        assertThat(token).isLessThan(done);
        assertThat(body).contains("\"riskFlag\":true", "KIRAN Mental Health Helpline", "Tele-MANAS");
        // Newlines inside a token stay inside the JSON string, not split into SSE lines.
        assertThat(body).contains("{\"text\":\"yahin hoon.\\nBatao?\"}");
        assertThat(body).contains("{\"messageId\":\"" + messageId + "\"}");
    }

    @Test
    void metaCarriesTheProfessionalType() throws Exception {
        doAnswer(invocation -> {
            ChatTurnListener listener = invocation.getArgument(1);
            listener.onMeta(new SafetyAssessment(false, null, 4, "find_professional", null, null)
                    .withProfessionalType("PSYCHIATRIST"));
            listener.onDone(UUID.randomUUID());
            return null;
        }).when(chatService).runTurn(any(), any());

        assertThat(streamBody()).contains("\"recommendedAction\":\"find_professional\"",
                "\"professionalType\":\"PSYCHIATRIST\"");
    }

    @Test
    void turnErrorsBecomeAnErrorEventWithCrisisResources() throws Exception {
        doAnswer(invocation -> {
            ChatTurnListener listener = invocation.getArgument(1);
            listener.onError(ChatTurnError.SAFETY_CHECK_FAILED, CrisisResources.INDIA_RESOURCES);
            return null;
        }).when(chatService).runTurn(any(), any());

        String body = streamBody();

        assertThat(body).contains("event:error", "\"code\":\"SAFETY_CHECK_FAILED\"", "Tele-MANAS");
        assertThat(body).doesNotContain("event:meta", "event:token");
    }

    @Test
    void unexpectedFailuresStillEndTheStreamWithAnErrorEvent() throws Exception {
        doThrow(new IllegalStateException("database down")).when(chatService).runTurn(any(), any());

        String body = streamBody();

        assertThat(body).contains("event:error", "\"code\":\"REPLY_FAILED\"");
        assertThat(body).doesNotContain("database down");
    }
}
