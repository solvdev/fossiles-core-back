package com.fossiles.fossilescorebackend.application.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MinorExpenseSummaryResponse {
    private BigDecimal totalExpenses;
    private BigDecimal totalPendingReimbursements;
    private Long totalExpensesCount;
    private Long pendingReimbursementsCount;
    private Map<String, BigDecimal> expensesByPurchaser;
    private Map<String, Long> expensesBySupplier;
    private List<MinorExpenseResponse> recentExpenses;
}

