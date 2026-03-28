package com.sheepfold.personalaiworkspaceagent.rag.dto;

public record RagAskRequest(String question, Integer topK) {
}
