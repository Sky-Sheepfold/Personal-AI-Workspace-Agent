package com.sheepfold.personalaiworkspaceagent.planning;

public record PlanStep(int index, String title, String description, PlanStatus status) {
}
