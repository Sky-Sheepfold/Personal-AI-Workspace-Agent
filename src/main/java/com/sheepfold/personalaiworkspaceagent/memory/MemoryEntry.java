package com.sheepfold.personalaiworkspaceagent.memory;

import java.time.Instant;

public record MemoryEntry(MemoryRole role, String content, Instant createdAt) {
}
