export interface QuotaWindow {
  label: string;
  available: boolean;
  usedPercent: number | null;
  remainingPercent: number | null;
  resetAt?: number;
  resetAfterSeconds?: number;
  resetLabel: string;
  summary?: string;
}

export interface WatchQuota {
  available: boolean;
  message: string;
  planType: string | null;
  updatedAt: number | null;
  title: string;
  shortTerm: QuotaWindow;
  weekly: QuotaWindow;
}

export interface WatchApproval {
  id: string;
  status: string;
  title: string;
  reason: string;
  target: string;
  toolName: string;
  createdAt: number;
  expiresAt?: number | null;
  decidedAt?: number | null;
}

export interface WatchNotification {
  id: string;
  type: string;
  title: string;
  message: string;
  approvalId?: string | null;
  createdAt: number;
}

export interface WatchStatus {
  serverTime: number;
  quota: WatchQuota;
  approvals: {
    pending: WatchApproval[];
    recent: WatchApproval[];
    pendingCount: number;
  };
  notifications: WatchNotification[];
}
