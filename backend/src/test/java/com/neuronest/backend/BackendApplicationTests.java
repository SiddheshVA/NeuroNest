package com.neuronest.backend;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

// No knowledge-base ingestion from a test run: it would write to the configured database and call Gemini.
@SpringBootTest(properties = "kb.enabled=false")
class BackendApplicationTests {

	@Test
	void contextLoads() {
	}

}
