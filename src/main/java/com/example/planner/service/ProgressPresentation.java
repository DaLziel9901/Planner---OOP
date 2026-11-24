package com.example.planner.service;

import com.example.planner.model.PlanNode;

public record ProgressPresentation(State state,Integer completedCount,Integer totalCount,String percentage,String label) {
    public enum State { UNAVAILABLE, EMPTY, AVAILABLE }
    public static ProgressPresentation unavailable() {
        return new ProgressPresentation(State.UNAVAILABLE,null,null,null,"Chưa có tiến độ khả dụng");
    }
    public static ProgressPresentation from(PlanNode node) {
        int done=node.getCompletedCount(),total=node.getTotalCount();
        if (total<0 || done<0 || done>total) throw new IllegalArgumentException("Counts tiến độ không hợp lệ");
        if (total==0) return new ProgressPresentation(State.EMPTY,0,0,null,"Chưa có công việc");
        String percent=java.math.BigDecimal.valueOf(done).multiply(java.math.BigDecimal.valueOf(100))
                .divide(java.math.BigDecimal.valueOf(total),2,java.math.RoundingMode.HALF_UP)
                .stripTrailingZeros().toPlainString().replace('.',',') + "%";
        return new ProgressPresentation(State.AVAILABLE,done,total,percent,done + "/" + total + " SubTask — " + percent);
    }
}
