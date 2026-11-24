package com.example.planner.controller;

import com.example.planner.model.PlanNode;
import com.example.planner.service.PlanTreeService.InvalidTreeException;
import com.example.planner.service.ProgressPresentation;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import java.util.*;
import java.util.function.*;

// Không có giao dịch ở lớp điều phối: fallback chỉ xử lý sau khi loader proxy đã thoát.
final class ProgressReadSupport {
    private ProgressReadSupport() { }
    static <T> T primary(Supplier<T> read) {
        try { return read.get(); }
        catch (DataAccessException exception) { throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,"Không thể đọc dữ liệu"); }
    }
    static <T> Optional<T> tree(Supplier<T> read) {
        try { return Optional.of(read.get()); }
        catch (DataAccessException | InvalidTreeException exception) {
            org.slf4j.LoggerFactory.getLogger(ProgressReadSupport.class).warn("Không thể nạp cây kế hoạch ({})",exception.getClass().getSimpleName());
            return Optional.empty();
        }
    }
    static <T extends PlanNode> Map<Long,ProgressPresentation> progress(List<T> rows,Function<T,Long> id,boolean loaded) {
        Map<Long,ProgressPresentation> result=new LinkedHashMap<>();
        for(T row:rows) result.put(id.apply(row),loaded?ProgressPresentation.from(row):ProgressPresentation.unavailable());
        return result;
    }
}
