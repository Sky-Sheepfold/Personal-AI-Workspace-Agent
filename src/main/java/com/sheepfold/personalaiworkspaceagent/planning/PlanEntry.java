package com.sheepfold.personalaiworkspaceagent.planning;

import java.time.Instant;
import java.util.List;

public record PlanEntry(
        String sessionId,
        String goal,
        String summary,
        List<PlanStep> steps,
        Instant createdAt,
        Instant updatedAt) {
}
