package com.example.planner.model;

import java.util.List;

public interface PlanNode {

    String getName();

    int getCompletedCount();

    int getTotalCount();

    double getProgress();

    List<PlanNode> getChildren();
}