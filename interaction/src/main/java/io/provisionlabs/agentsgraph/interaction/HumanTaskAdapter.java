package io.provisionlabs.agentsgraph.interaction;

/**
 * SPI for a delivery channel that puts tasks in front of humans: a chat, an admin inbox, email -
 * anything. The way back is the same for every channel:
 * {@link InteractionService#complete(String, HumanTaskDecision)}.
 */
public interface HumanTaskAdapter {

    /** Whether this channel delivers this particular task (by graph/reason/payload). */
    boolean supports(HumanTask task);

    /** Deliver the question to a human. Called at most once per task. */
    void publish(HumanTask task);

    /**
     * The task was closed: answered (possibly through another channel), expired, or cancelled -
     * the channel may remove it from its UI. Optional.
     */
    default void closed(HumanTask task, TaskOutcome outcome) {
    }

    enum TaskOutcome { COMPLETED, EXPIRED, CANCELLED }
}
