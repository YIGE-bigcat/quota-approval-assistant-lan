package com.codexbridge.approval;

import java.util.ArrayList;
import java.util.List;

final class StatusSnapshot {
    final List<Approval> approvals = new ArrayList<>();
    String quotaSummary = "暂无额度数据";
    String quotaTitle = "额度暂无数据";
    String errorMessage;

    List<Approval> pendingApprovals() {
        List<Approval> pending = new ArrayList<>();
        for (Approval approval : approvals) {
            if (approval.isPending()) pending.add(approval);
        }
        return pending;
    }
}
