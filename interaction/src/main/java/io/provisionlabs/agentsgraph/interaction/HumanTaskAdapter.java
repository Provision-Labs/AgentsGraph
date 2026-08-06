package io.provisionlabs.agentsgraph.interaction;

/**
 * SPI канала доставки задач человеку: чат, инбокс админки, почта - что угодно. Обратный путь у
 * всех каналов один: {@link InteractionService#complete(String, HumanTaskDecision)}.
 */
public interface HumanTaskAdapter {

    /** Доставляет ли этот канал такую задачу (по графу/reason/payload). */
    boolean supports(HumanTask task);

    /** Доставить вопрос человеку. Вызывается не более одного раза на задачу. */
    void publish(HumanTask task);

    /**
     * Задача закрыта: ответили (возможно, из другого канала), истёк дедлайн или отменили -
     * канал может убрать её из своего UI. Опционально.
     */
    default void closed(HumanTask task, TaskOutcome outcome) {
    }

    enum TaskOutcome { COMPLETED, EXPIRED, CANCELLED }
}
