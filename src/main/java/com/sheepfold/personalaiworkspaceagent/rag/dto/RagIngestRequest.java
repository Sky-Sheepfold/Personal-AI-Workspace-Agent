package com.sheepfold.personalaiworkspaceagent.rag.dto;

import java.util.List;

public record RagIngestRequest(List<String> chunks) {
}
