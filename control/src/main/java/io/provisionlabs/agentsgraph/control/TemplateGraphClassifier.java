package io.provisionlabs.agentsgraph.control;

import io.provisionlabs.agentsgraph.config.ConfigStore;
import io.provisionlabs.agentsgraph.config.GraphDefinition;

import java.util.Map;
import java.util.Objects;

/**
 * Reference {@link GraphClassifier} matching a graph's declared {@link GraphDefinition#getTemplates()}
 * against the input, mirroring the legacy docscan-pipeline's template-matching classifier
 * ({@code PipelineClassifierService}):
 * <ul>
 *   <li>{@link #INPUT_GRAPH_ID} in the input, if present, is returned directly - an explicit
 *       caller override that skips matching entirely.</li>
 *   <li>otherwise, each candidate graph's templates are checked against {@link #INPUT_HAS_FILE}
 *       and {@link #INPUT_TEMPLATE_TAG} - a short classification tag such as {@code "accountant"}
 *       or {@code "passport"} (e.g. produced upstream from a file's declared type), <em>not</em>
 *       free-form user text: a template containing {@code "file"} only matches when a file is
 *       present; stripped of an optional {@code "file:"} prefix, a template matches when it's
 *       blank (any file matches) or when it equals the tag.</li>
 *   <li>if nothing matches, one of the two configured default graph ids is used, depending on
 *       whether the input has a file.</li>
 * </ul>
 */
public final class TemplateGraphClassifier implements GraphClassifier {

    public static final String INPUT_GRAPH_ID = "graphId";
    public static final String INPUT_TEMPLATE_TAG = "templateTag";
    public static final String INPUT_HAS_FILE = "hasFile";

    private final ConfigStore configStore;
    private final String defaultGraphIdWithFile;
    private final String defaultGraphIdWithoutFile;

    public TemplateGraphClassifier(ConfigStore configStore, String defaultGraphIdWithFile,
                                    String defaultGraphIdWithoutFile) {
        this.configStore = Objects.requireNonNull(configStore, "configStore");
        this.defaultGraphIdWithFile = defaultGraphIdWithFile;
        this.defaultGraphIdWithoutFile = defaultGraphIdWithoutFile;
    }

    @Override
    public String classify(Map<String, Object> input) {
        Object graphIdOverride = input.get(INPUT_GRAPH_ID);
        if (graphIdOverride != null) {
            return String.valueOf(graphIdOverride);
        }

        boolean hasFile = Boolean.TRUE.equals(input.get(INPUT_HAS_FILE));
        String templateTag = String.valueOf(input.getOrDefault(INPUT_TEMPLATE_TAG, "")).toLowerCase();

        for (GraphDefinition graph : configStore.findAll()) {
            if (matchesTemplate(graph, templateTag, hasFile)) {
                return graph.getId();
            }
        }

        String fallback = hasFile ? defaultGraphIdWithFile : defaultGraphIdWithoutFile;
        if (fallback == null) {
            throw new IllegalStateException("No graph matched input and no default graph is configured");
        }
        return fallback;
    }

    private boolean matchesTemplate(GraphDefinition graph, String templateTag, boolean hasFile) {
        for (String template : graph.getTemplates()) {
            String lowerTemplate = template.toLowerCase();
            if (lowerTemplate.contains("file") && !hasFile) {
                continue;
            }
            String cleanTemplate = lowerTemplate.replace("file:", "").trim();
            if (cleanTemplate.isEmpty()) {
                if (hasFile) {
                    return true;
                }
            } else if (templateTag.contains(cleanTemplate)) {
                return true;
            }
        }
        return false;
    }
}
