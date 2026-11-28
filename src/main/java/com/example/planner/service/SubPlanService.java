package com.example.planner.service;

import com.example.planner.model.Plan;
import com.example.planner.model.SubPlan;
import com.example.planner.repository.PlanRepository;
import com.example.planner.repository.SubPlanRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
public class SubPlanService {
    private final SubPlanRepository repository;
    private final PlanRepository plans;

    public SubPlanService(SubPlanRepository repository, PlanRepository plans) {
        this.repository = repository;
        this.plans = plans;
    }

    public Plan getPlan(long ownerId, long planId) {
        OwnerScope.require(ownerId);
        if (planId <= 0) throw notFound("Plan", planId);
        return plans.findByIdForOwner(ownerId, planId).orElseThrow(() -> notFound("Plan", planId));
    }

    public List<SubPlan> findByPlanId(long ownerId, long planId) {
        OwnerScope.require(ownerId);
        getPlan(ownerId, planId);
        return repository.findByPlanIdForOwner(ownerId, planId);
    }

    public SubPlan getById(long ownerId, long id) {
        OwnerScope.require(ownerId);
        if (id <= 0) throw notFound("SubPlan", id);
        return repository.findByIdForOwner(ownerId, id).orElseThrow(() -> notFound("SubPlan", id));
    }

    @Transactional
    public long create(long ownerId, long planId, Long parentId, String title, String description,
                       LocalDate startDate, LocalDate endDate, int orderIndex) {
        OwnerScope.require(ownerId);
        lockPlan(ownerId, planId);
        validateInput(parentId, title, startDate, endDate, orderIndex);
        SubPlan proposed = new SubPlan(null, planId, parentId, title, blankToNull(description),
                startDate, endDate, orderIndex, OffsetDateTime.now());
        Map<Long, SubPlan> tree = readHierarchy(ownerId, planId);
        // Khóa tạm chỉ dùng để kiểm tra cây trước INSERT, không lưu vào model/DB.
        tree.put(-1L, proposed);
        validateHierarchy(tree);
        return repository.insertForOwner(ownerId, proposed).orElseThrow(() -> notFound("Plan", planId));
    }

    @Transactional
    public void update(long ownerId, long id, Long parentId, String title, String description,
                       LocalDate startDate, LocalDate endDate, int orderIndex) {
        OwnerScope.require(ownerId);
        SubPlan current = getById(ownerId, id);
        lockPlan(ownerId, current.getPlanId());
        current = getById(ownerId, id); // Đọc lại sau khi lấy khóa.
        validateInput(parentId, title, startDate, endDate, orderIndex);
        if (Long.valueOf(id).equals(parentId)) {
            throw new InputException("parentSubPlanId", "SubPlan không thể làm cha của chính nó");
        }
        SubPlan proposed = new SubPlan(id, current.getPlanId(), parentId, title,
                blankToNull(description), startDate, endDate, orderIndex, current.getCreatedAt());
        Map<Long, SubPlan> tree = readHierarchy(ownerId, current.getPlanId());
        tree.put(id, proposed);
        // Kiểm tra cả cây sau thay đổi: tính cả độ sâu của các con khi chuyển nhánh.
        validateHierarchy(tree);
        if (!repository.updateForOwner(ownerId, proposed)) throw notFound("SubPlan", id);
    }

    @Transactional
    public long delete(long ownerId, long id) {
        OwnerScope.require(ownerId);
        SubPlan current = getById(ownerId, id);
        lockPlan(ownerId, current.getPlanId());
        current = getById(ownerId, id);
        if (!repository.deleteByIdForOwner(ownerId, id)) throw notFound("SubPlan", id);
        return current.getPlanId();
    }

    public List<SubPlan> getDirectChildren(long ownerId, SubPlan subPlan) {
        OwnerScope.require(ownerId);
        return repository.findByPlanIdForOwner(ownerId, subPlan.getPlanId()).stream()
                .filter(child -> subPlan.getId().equals(child.getParentSubPlanId())).toList();
    }

    // Cảnh báo không chặn lưu. So sánh các mốc có giá trị, không bắt buộc đủ cả hai ngày.
    public List<String> getDateWarnings(long ownerId, SubPlan subPlan) {
        OwnerScope.require(ownerId);
        LocalDate parentStart;
        LocalDate parentEnd;
        if (subPlan.getParentSubPlanId() == null) {
            Plan parent = getPlan(ownerId, subPlan.getPlanId());
            parentStart = parent.getStartDate();
            parentEnd = parent.getEndDate();
        } else {
            SubPlan parent = getById(ownerId, subPlan.getParentSubPlanId());
            parentStart = parent.getStartDate();
            parentEnd = parent.getEndDate();
        }
        return dateWarnings(subPlan,parentStart,parentEnd,getDirectChildren(ownerId, subPlan));
    }

    // Bản chỉ đọc bộ nhớ để cảnh báo và tiến độ dùng cùng metadata snapshot.
    public List<String> getDateWarnings(SubPlan node,Plan plan,List<SubPlan> all) {
        SubPlan parent=node.getParentSubPlanId()==null?null:all.stream()
                .filter(s->s.getId().equals(node.getParentSubPlanId())).findFirst().orElseThrow();
        return dateWarnings(node,parent==null?plan.getStartDate():parent.getStartDate(),parent==null?plan.getEndDate():parent.getEndDate(),
                all.stream().filter(s->node.getId().equals(s.getParentSubPlanId())).toList());
    }

    private List<String> dateWarnings(SubPlan subPlan,LocalDate parentStart,LocalDate parentEnd,List<SubPlan> children) {
        List<String> warnings = new ArrayList<>();
        if (outside(subPlan.getStartDate(), parentStart, parentEnd)
                || outside(subPlan.getEndDate(), parentStart, parentEnd)) {
            warnings.add("Ngày của SubPlan nằm ngoài khoảng ngày của cha. Dữ liệu vẫn được lưu.");
        }
        for (SubPlan child : children) {
            if (outside(child.getStartDate(), subPlan.getStartDate(), subPlan.getEndDate())
                    || outside(child.getEndDate(), subPlan.getStartDate(), subPlan.getEndDate())) {
                warnings.add("SubPlan con #" + child.getId() + " nằm ngoài khoảng ngày của SubPlan này.");
            }
        }
        return warnings;
    }

    private boolean outside(LocalDate date, LocalDate start, LocalDate end) {
        return date != null && ((start != null && date.isBefore(start))
                || (end != null && date.isAfter(end)));
    }

    private void lockPlan(long ownerId, long planId) {
        if (planId <= 0 || !repository.lockPlanForOwner(ownerId, planId)) throw notFound("Plan", planId);
    }

    private Map<Long, SubPlan> readHierarchy(long ownerId, long planId) {
        Map<Long, SubPlan> tree = new HashMap<>();
        for (SubPlan item : repository.findByPlanIdForOwner(ownerId, planId)) tree.put(item.getId(), item);
        return tree;
    }

    private void validateHierarchy(Map<Long, SubPlan> tree) {
        // Với mỗi nút, đi ngược lên gốc. Tầng 1 là SubPlan có parentId = NULL.
        for (Long nodeId : tree.keySet()) {
            Set<Long> visited = new HashSet<>();
            Long cursor = nodeId;
            int depth = 0;
            while (cursor != null) {
                if (!visited.add(cursor)) {
                    throw new InputException("parentSubPlanId", "Quan hệ cha tạo vòng lặp");
                }
                SubPlan node = tree.get(cursor);
                if (node == null) {
                    throw new InputException("parentSubPlanId", "SubPlan cha phải tồn tại trong cùng Plan");
                }
                if (++depth > 5) {
                    throw new InputException("parentSubPlanId", "SubPlan chỉ được lồng tối đa 5 tầng, kể cả các con khi chuyển nhánh");
                }
                cursor = node.getParentSubPlanId();
            }
        }
    }

    private void validateInput(Long parentId, String title, LocalDate start, LocalDate end, int order) {
        if (title == null || title.isBlank()) throw new InputException("title", "Tên không được để trống");
        if (title.codePointCount(0, title.length()) > 255) throw new InputException("title", "Tên tối đa 255 ký tự");
        if (parentId != null && parentId <= 0) throw new InputException("parentSubPlanId", "ID cha phải dương");
        if (start != null && end != null && start.isAfter(end)) throw new InputException("endDate", "Ngày kết thúc không được trước ngày bắt đầu");
        if (order < 0) throw new InputException("orderIndex", "Thứ tự không được âm");
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private ResponseStatusException notFound(String type, long id) {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, "Không tìm thấy " + type + " #" + id);
    }

    // Chỉ lỗi nhập liệu dự kiến được controller đưa trở lại form, không bắt lỗi DB.
    public static class InputException extends IllegalArgumentException {
        private final String field;
        public InputException(String field, String message) {
            super(message);
            this.field = field;
        }
        public String getField() { return field; }
    }

}
