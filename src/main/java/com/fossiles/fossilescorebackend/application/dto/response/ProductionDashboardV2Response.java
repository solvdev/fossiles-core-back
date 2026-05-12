package com.fossiles.fossilescorebackend.application.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ProductionDashboardV2Response {
    private LocalDate from;
    private LocalDate to;
    private LocalDate referenceDate;
    private ExecutiveSummary summary;
    private ProductionSummary production;
    private TaskSummary tasks;
    private List<DeskSummary> desks;
    private List<CriticalOrder> criticalOrders;
    private List<ProductStageSummary> productStages;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ExecutiveSummary {
        private long totalOrders;
        private long activeOrders;
        private long pendingOrders;
        private long inProgressOrders;
        private long inQaOrders;
        private long completedOrders;
        private long cancelledOrders;
        private long overdueOrders;
        private long dueTodayOrders;
        private long dueTomorrowOrders;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ProductionSummary {
        private int plannedUnits;
        private int pendingUnits;
        private int inProgressUnits;
        private int completedUnits;
        private int wasteUnits;
        private double completionRate;
        private double wasteRate;
        private double onTimeTaskRate;
        private long completedTasksWithTime;
        private long completedTasksOnTime;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class TaskSummary {
        private long totalTasks;
        private long pendingTasks;
        private long inProgressTasks;
        private long completedTasks;
        private long cancelledTasks;
        private long unassignedTasks;
        private long overdueTasks;
        private long dueTodayTasks;
        private double completionRate;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class DeskSummary {
        private Integer desk;
        private long totalTasks;
        private long pendingTasks;
        private long inProgressTasks;
        private long completedTasks;
        private int plannedUnits;
        private int completedUnits;
        private double completionRate;
        private double efficiencyRate;
        private int avgEstimatedMinutes;
        private int avgActualMinutes;
        private String health;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class CriticalOrder {
        private Long id;
        private String code;
        private String orderType;
        private String status;
        private LocalDate startDate;
        private LocalDate deliveryDate;
        private String customerName;
        private long totalTasks;
        private long pendingTasks;
        private long inProgressTasks;
        private long completedTasks;
        private boolean overdue;
        private boolean dueToday;
        private boolean dueTomorrow;
        private boolean withoutTasks;
        private boolean materialsPending;
        private int plannedUnits;
        private int completedUnits;
        private double completionRate;
        private List<String> reasons;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ProductStageSummary {
        private Long productId;
        private String productCode;
        private String productName;
        private int pendingUnits;
        private int inProgressUnits;
        private int completedUnits;
        private int totalUnits;
        private double completionRate;
    }
}
