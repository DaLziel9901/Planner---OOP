package com.example.planner.service;

import com.example.planner.model.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ProgressPresentationTest {
    Task task(int done,int total) {
        Task task=new Task("Task");
        for(int i=0;i<total;i++) { SubTask leaf=new SubTask("Leaf"); if(i<done) leaf.markDone(); task.addSubTask(leaf); }
        return task;
    }
    @Test void unavailableHasNoCountsOrNumber() {
        var p=ProgressPresentation.unavailable(); assertEquals(ProgressPresentation.State.UNAVAILABLE,p.state());
        assertNull(p.completedCount()); assertNull(p.totalCount()); assertNull(p.percentage());
        assertEquals("Chưa có tiến độ khả dụng",p.label()); assertFalse(p.label().contains("%"));
    }
    @Test void loadedEmptyKeepsModelZeroButDoesNotDisplayZeroOrHundredPercent() {
        Task task=task(0,0); assertEquals(0,task.getProgress()); var p=ProgressPresentation.from(task);
        assertEquals(ProgressPresentation.State.EMPTY,p.state()); assertEquals("Chưa có công việc",p.label());
        assertNull(p.percentage()); assertEquals(0,p.totalCount());
    }
    @Test void fractionsRoundOnlyAtEndToTwoDigits() {
        assertEquals("33,33%",ProgressPresentation.from(task(1,3)).percentage());
        assertEquals("66,67%",ProgressPresentation.from(task(2,3)).percentage());
        assertEquals("0%",ProgressPresentation.from(task(0,3)).percentage());
        assertEquals("100%",ProgressPresentation.from(task(3,3)).percentage());
    }
    @Test void weightedByLeafCountsNotAverageOfRoundedBranches() {
        Plan root=new Plan("Plan"); root.addTask(task(1,1)); root.addTask(task(0,3));
        var p=ProgressPresentation.from(root); assertEquals("25%",p.percentage());
        assertEquals(1,p.completedCount()); assertEquals(4,p.totalCount()); assertTrue(p.label().contains("1/4"));
    }
}
