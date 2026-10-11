package com.neuronest.backend.chat;

import com.neuronest.backend.chat.llm.LlmMessage;
import com.neuronest.backend.chat.llm.LlmRouter;
import com.neuronest.backend.chat.llm.LlmUnavailableException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;
import tools.jackson.databind.json.JsonMapper;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ConversationSummaryServiceTest {

    private final LlmRouter router = mock(LlmRouter.class);
    private final ChatConversationRepository conversations = mock(ChatConversationRepository.class);
    private final ChatMessageRepository messages = mock(ChatMessageRepository.class);
    private final List<Runnable> scheduled = new ArrayList<>();
    private final UUID conversationId = UUID.randomUUID();
    private ConversationSummaryService service;
    private ChatConversation conversation;

    @BeforeEach
    void setUp() {
        service = new ConversationSummaryService(router, JsonMapper.builder().build(), conversations, messages,
                scheduled::add, 8, 8);
        conversation = new ChatConversation();
        ReflectionTestUtils.setField(conversation, "id", conversationId);
        when(conversations.findById(conversationId)).thenReturn(Optional.of(conversation));
    }

    @SuppressWarnings("unchecked")
    private void givenSummaryReply(String json) {
        when(router.completeJson(eq("summary"), anyString(), anyList(), any())).thenAnswer(invocation -> {
            Function<String, Object> parser = invocation.getArgument(3);
            return new LlmRouter.Served<>(parser.apply(json), "groq");
        });
    }

    private void givenMessages(int count) {
        List<ChatMessage> list = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            ChatMessage m = new ChatMessage();
            m.setRole(i % 2 == 0 ? "user" : "assistant");
            m.setContent("message " + i);
            list.add(m);
        }
        when(messages.findByConversationIdOrderByCreatedAtAsc(conversationId)).thenReturn(list);
    }

    @Test
    void waitsUntilEnoughMessagesHaveLeftTheWindow() {
        service.maybeUpdate(conversationId, 15, 0); // 7 outside the window
        assertThat(scheduled).isEmpty();

        service.maybeUpdate(conversationId, 16, 0); // 8 outside the window
        assertThat(scheduled).hasSize(1);
    }

    @Test
    void foldsOnlyTheMessagesThatLeftTheWindowIntoTheSummary() {
        givenMessages(16);
        conversation.setSummary("Earlier: exam stress.");
        givenSummaryReply("{\"summary\": \"The user has exam stress and talked about their parents.\"}");

        service.maybeUpdate(conversationId, 16, 0);
        scheduled.forEach(Runnable::run);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<LlmMessage>> input = ArgumentCaptor.forClass(List.class);
        verify(router).completeJson(eq("summary"), anyString(), input.capture(), any());
        String sent = input.getValue().getFirst().content();
        assertThat(sent).contains("Earlier: exam stress.", "message 0", "message 7").doesNotContain("message 8");
        assertThat(conversation.getSummary()).isEqualTo("The user has exam stress and talked about their parents.");
        assertThat(conversation.getSummarizedMessageCount()).isEqualTo(8);
        verify(conversations).save(conversation);
    }

    @Test
    void doesNotScheduleTwiceForTheSameConversation() {
        service.maybeUpdate(conversationId, 16, 0);
        service.maybeUpdate(conversationId, 17, 0);
        assertThat(scheduled).hasSize(1);
    }

    @Test
    void failureKeepsTheOldSummaryAndAllowsALaterRetry() {
        givenMessages(16);
        conversation.setSummary("Old summary.");
        when(router.completeJson(eq("summary"), anyString(), anyList(), any()))
                .thenThrow(new LlmUnavailableException("summary", null));

        service.maybeUpdate(conversationId, 16, 0);
        scheduled.forEach(Runnable::run);

        assertThat(conversation.getSummary()).isEqualTo("Old summary.");
        verify(conversations, never()).save(any());
        service.maybeUpdate(conversationId, 16, 0);
        assertThat(scheduled).hasSize(2);
    }
}
