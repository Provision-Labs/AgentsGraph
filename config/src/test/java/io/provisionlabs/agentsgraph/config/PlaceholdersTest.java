package io.provisionlabs.agentsgraph.config;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PlaceholdersTest {

    private final Map<String, String> env = new HashMap<>();
    private final Map<String, String> settings = new HashMap<>();
    private final Placeholders placeholders = new Placeholders(List.of(
            new Placeholders.Source(Placeholders.ENVIRONMENT, env::get),
            new Placeholders.Source("settings", settings::get)));

    @Test
    void valueComesFromTheFirstSourceThatHasIt() {
        env.put("OCR_SERVER", "http://ocr-env:8191");
        settings.put("OCR_SERVER", "http://ocr-settings:8191");
        settings.put("LLM_SERVER", "http://llm-settings:8096");

        assertThat(placeholders.resolve("${{OCR_SERVER}}/processing/{template}"))
                .isEqualTo("http://ocr-env:8191/processing/{template}");
        assertThat(placeholders.resolve("${{LLM_SERVER}}/v1/chat/completions"))
                .isEqualTo("http://llm-settings:8096/v1/chat/completions");
    }

    @Test
    void defaultAfterTheColonMayItselfBeAUrl() {
        assertThat(placeholders.resolve("${{OCR_SERVER:http://10.64.0.39:8191}}/processing/{template}"))
                .isEqualTo("http://10.64.0.39:8191/processing/{template}");

        env.put("OCR_SERVER", "   ");
        assertThat(placeholders.resolve("${{OCR_SERVER:http://fallback:1}}"))
                .as("a blank variable counts as unset - the default applies").isEqualTo("http://fallback:1");

        env.put("OCR_SERVER", "http://set:2");
        assertThat(placeholders.resolve("${{OCR_SERVER:http://fallback:1}}")).isEqualTo("http://set:2");
    }

    @Test
    void placeholderWithoutValueOrDefaultFailsWithItsName() {
        assertThatThrownBy(() -> placeholders.resolve("${{LLM_SERVER}}/v1"))
                .isInstanceOf(Placeholders.UnresolvedPlaceholderException.class)
                .hasMessageContaining("LLM_SERVER")
                .satisfies(e -> assertThat(((Placeholders.UnresolvedPlaceholderException) e).getName()).isEqualTo("LLM_SERVER"));
    }

    @Test
    void springStyleAndSingleBraceTemplatesAreLeftAlone() {
        assertThat(placeholders.resolve("${spring.prop:x}/{template}/{page}"))
                .isEqualTo("${spring.prop:x}/{template}/{page}");
        assertThat(Placeholders.contains("${spring.prop}")).isFalse();
    }

    @Test
    void paramsAreResolvedDeepAndUntouchedWhenTheyHaveNoPlaceholder() {
        env.put("LLM_SERVER", "http://llm:8096");
        Map<String, Object> plain = Map.of("retries", 3, "processUrl", "http://fixed/processing");
        assertThat(placeholders.resolveParams(plain)).isSameAs(plain);

        Map<String, Object> params = Map.of(
                "llmBaseUrl", "${{LLM_SERVER}}",
                "retries", 3,
                "fallbacks", List.of("${{LLM_SERVER}}/a", "http://other"),
                "nested", Map.of("url", "${{LLM_SERVER}}/b"));

        Map<String, Object> resolved = placeholders.resolveParams(params);

        assertThat(resolved).containsEntry("llmBaseUrl", "http://llm:8096").containsEntry("retries", 3)
                .containsEntry("fallbacks", List.of("http://llm:8096/a", "http://other"))
                .containsEntry("nested", Map.of("url", "http://llm:8096/b"));
        assertThat(params.get("llmBaseUrl")).as("the definition keeps what is written").isEqualTo("${{LLM_SERVER}}");
    }

    @Test
    void stepKeepsItsShapeWithParamsResolved() {
        settings.put("LLM_MODEL", "qwen3-8b");
        StepDefinition step = new StepDefinition("step_llm_prompt", "llm-prompt-prepare",
                Map.of("model", "${{LLM_MODEL:qwen2.5-chat}}"), List.of("prompt"), List.of("answer"), true);

        StepDefinition resolved = placeholders.resolveStep(step);

        assertThat(resolved.getParams()).containsEntry("model", "qwen3-8b");
        assertThat(resolved.getId()).isEqualTo("step_llm_prompt");
        assertThat(resolved.getProcessorRef()).isEqualTo("llm-prompt-prepare");
        assertThat(resolved.getOutputToNext()).containsExactly("prompt");
        assertThat(resolved.getOutputToSave()).containsExactly("answer");
        assertThat(resolved.isSnapshot()).isTrue();

        StepDefinition plain = new StepDefinition("s", "noop", Map.of("k", "v"));
        assertThat(placeholders.resolveStep(plain)).isSameAs(plain);
    }

    @Test
    void describeTellsWhatEachPlaceholderResolvesToAndWhere() {
        env.put("OCR_SERVER", "http://ocr:8191");
        settings.put("LLM_SERVER", "http://llm:8096");
        Map<String, Object> params = Map.of(
                "processUrl", "${{OCR_SERVER}}/processing/{template}",
                "llmBaseUrl", "${{LLM_SERVER}}",
                "model", "${{LLM_MODEL:qwen2.5-chat}}",
                "key", "${{LLM_KEY}}",
                "again", "${{OCR_SERVER}}/health");

        List<Placeholders.Value> values = placeholders.describe(params);

        assertThat(values).extracting(Placeholders.Value::getName)
                .containsExactlyInAnyOrder("OCR_SERVER", "LLM_SERVER", "LLM_MODEL", "LLM_KEY");
        assertThat(values).filteredOn(v -> v.getName().equals("OCR_SERVER")).singleElement()
                .satisfies(v -> {
                    assertThat(v.getValue()).isEqualTo("http://ocr:8191");
                    assertThat(v.getOrigin()).isEqualTo(Placeholders.ENVIRONMENT);
                });
        assertThat(values).filteredOn(v -> v.getName().equals("LLM_SERVER")).singleElement()
                .satisfies(v -> assertThat(v.getOrigin()).isEqualTo("settings"));
        assertThat(values).filteredOn(v -> v.getName().equals("LLM_MODEL")).singleElement()
                .satisfies(v -> {
                    assertThat(v.getValue()).isEqualTo("qwen2.5-chat");
                    assertThat(v.getOrigin()).isEqualTo(Placeholders.DEFAULT);
                });
        assertThat(values).filteredOn(v -> v.getName().equals("LLM_KEY")).singleElement()
                .satisfies(v -> {
                    assertThat(v.getValue()).isNull();
                    assertThat(v.getOrigin()).isEqualTo(Placeholders.MISSING);
                });
    }
}
