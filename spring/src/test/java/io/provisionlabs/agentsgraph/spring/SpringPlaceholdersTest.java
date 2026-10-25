package io.provisionlabs.agentsgraph.spring;

import io.provisionlabs.agentsgraph.config.Placeholders;
import org.junit.jupiter.api.Test;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class SpringPlaceholdersTest {

    @Test
    void applicationSettingsAnswerWhatTheContainerDoesNotSet() {
        StandardEnvironment environment = new StandardEnvironment();
        // имя, которого заведомо нет среди переменных окружения машины, где идёт тест
        environment.getPropertySources().addFirst(new MapPropertySource("application",
                Map.of("AGENTSGRAPH_TEST_LLM_SERVER", "http://llm-from-yml:8096")));

        Placeholders placeholders = SpringPlaceholders.create(environment);

        assertThat(placeholders.resolve("${{AGENTSGRAPH_TEST_LLM_SERVER}}/v1")).isEqualTo("http://llm-from-yml:8096/v1");
        assertThat(placeholders.describe("${{AGENTSGRAPH_TEST_LLM_SERVER}}")).singleElement()
                .satisfies(v -> assertThat(v.getOrigin()).isEqualTo(SpringPlaceholders.SETTINGS));
        assertThat(placeholders.resolve("${{AGENTSGRAPH_TEST_OCR_SERVER:http://ocr-default:8191}}"))
                .isEqualTo("http://ocr-default:8191");
    }

    @Test
    void withoutAnEnvironmentOnlyVariablesAndDefaultsAreUsed() {
        assertThat(SpringPlaceholders.create(null).resolve("${{AGENTSGRAPH_TEST_NONE:x}}")).isEqualTo("x");
    }
}
