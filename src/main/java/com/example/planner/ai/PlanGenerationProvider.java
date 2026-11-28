package com.example.planner.ai;

@FunctionalInterface
public interface PlanGenerationProvider {
    String generate(String instructions, String input);
}
