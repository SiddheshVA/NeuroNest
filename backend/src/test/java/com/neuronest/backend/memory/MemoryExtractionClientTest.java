package com.neuronest.backend.memory;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static com.neuronest.backend.memory.MemoryTestSupport.properties;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MemoryExtractionClientTest {

    final MemoryExtractionClient client =
            new MemoryExtractionClient(null, properties(), JsonMapper.builder().build());

    final UUID regular = UUID.randomUUID();
    final List<MemoryRepository.KnownMemory> existing = List.of(
            new MemoryRepository.KnownMemory(UUID.randomUUID(), "name", "The user's name is Abhi."),
            new MemoryRepository.KnownMemory(regular, null, "The user works at a bank."));

    @Test
    void parsesCoreNewAndUpdates() {
        MemoryExtractionClient.Extraction extraction = client.parse("""
                {"core": {"name": "The user's name is Abhishek.", "Occupation": "The user is a teacher."},
                 "new": ["The user is preparing for exams."],
                 "updates": [{"number": 2, "fact": "The user used to work at a bank."}]}
                """, existing);

        assertThat(extraction.core()).isEqualTo(Map.of(
                "name", "The user's name is Abhishek.", "occupation", "The user is a teacher."));
        assertThat(extraction.newFacts()).containsExactly("The user is preparing for exams.");
        assertThat(extraction.updates()).containsExactly(
                new MemoryExtractionClient.Update(regular, "The user used to work at a bank."));
    }

    @Test
    void dropsUnknownCoreKeysBadNumbersAndCoreTargets() {
        MemoryExtractionClient.Extraction extraction = client.parse("""
                {"core": {"phone": "The user's phone is 98...", "name": ""},
                 "new": [42, "   "],
                 "updates": [{"number": 1, "fact": "core by number"}, {"number": 9, "fact": "out of range"}]}
                """, existing);

        assertThat(extraction.isEmpty()).isTrue();
    }

    @Test
    void emptyResultIsValid() {
        assertThat(client.parse("{\"core\": {}, \"new\": [], \"updates\": []}", existing).isEmpty()).isTrue();
    }

    @Test
    void nonObjectIsRejectedSoTheRouterTriesTheNextProvider() {
        assertThatThrownBy(() -> client.parse("[\"a fact\"]", existing)).isInstanceOf(IllegalStateException.class);
    }
}
