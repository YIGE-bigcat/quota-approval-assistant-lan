import { fetchWatchStatus, decideApproval } from '../common/BridgeClient';
import { BRIDGE_BASE_URL, REFRESH_INTERVAL_MS } from '../common/BridgeConfig';
import { WatchApproval, WatchNotification, WatchStatus } from '../common/Models';

type PageName = 'quota' | 'pending' | 'notifications' | 'history';

@Entry
@Component
struct Index {
  @State private page: PageName = 'quota';
  @State private status: WatchStatus | null = null;
  @State private loading: boolean = true;
  @State private message: string = '正在连接';
  @State private busyApprovalId: string = '';
  private timer: number = 0;

  aboutToAppear(): void {
    this.refresh();
    this.timer = setInterval(() => this.refresh(), REFRESH_INTERVAL_MS);
  }

  aboutToDisappear(): void {
    if (this.timer) {
      clearInterval(this.timer);
      this.timer = 0;
    }
  }

  private async refresh(): Promise<void> {
    try {
      const next = await fetchWatchStatus();
      this.status = next;
      this.message = next.approvals.pendingCount > 0 ? `待审批 ${next.approvals.pendingCount}` : '暂无待审批';
    } catch (error) {
      this.message = '连接失败';
    } finally {
      this.loading = false;
    }
  }

  private async decide(approval: WatchApproval, decision: 'allow' | 'deny'): Promise<void> {
    this.busyApprovalId = approval.id;
    try {
      await decideApproval(approval.id, decision);
      this.message = decision === 'allow' ? '已批准' : '已拒绝';
      await this.refresh();
    } catch (error) {
      this.message = '审批失败';
    } finally {
      this.busyApprovalId = '';
    }
  }

  private quotaColor(value: number | null): string {
    if (value === null || value === undefined) {
      return '#8DA099';
    }
    if (value >= 50) {
      return '#38D991';
    }
    if (value >= 25) {
      return '#DFA82C';
    }
    return '#F15D5D';
  }

  private syncMode(): string {
    return BRIDGE_BASE_URL.indexOf(':8790') >= 0 ? '手机中继' : '电脑直连';
  }

  private twoDigits(value: number): string {
    return value < 10 ? `0${value}` : `${value}`;
  }

  private timeLabel(timestamp: number): string {
    if (!timestamp) {
      return '--:--';
    }
    const date = new Date(timestamp);
    const hour = this.twoDigits(date.getHours());
    const minute = this.twoDigits(date.getMinutes());
    return `${hour}:${minute}`;
  }

  @Builder
  private Header(): void {
    Row() {
      Column() {
        Text('Codex 审批')
          .fontSize(18)
          .fontWeight(FontWeight.Bold)
          .fontColor('#F3F8F6')
        Text(`${this.syncMode()} · ${this.message}`)
          .fontSize(10)
          .fontColor('#8DA099')
          .margin({ top: 2 })
      }
      .alignItems(HorizontalAlign.Start)

      Blank()

      Button('刷新')
        .fontSize(11)
        .height(30)
        .backgroundColor('#173B2E')
        .fontColor('#62F2A7')
        .borderRadius(15)
        .onClick(() => this.refresh())
    }
    .width('100%')
    .padding({ left: 14, right: 14, top: 12, bottom: 8 })
  }

  @Builder
  private Tabs(): void {
    Row() {
      this.TabButton('额度', 'quota')
      this.TabButton('审批', 'pending')
      this.TabButton('通知', 'notifications')
      this.TabButton('历史', 'history')
    }
    .width('100%')
    .padding({ left: 12, right: 12, bottom: 8 })
    .justifyContent(FlexAlign.SpaceBetween)
  }

  @Builder
  private TabButton(label: string, target: PageName): void {
    Button(label)
      .height(28)
      .width(48)
      .fontSize(11)
      .fontColor(this.page === target ? '#042116' : '#BFE8D7')
      .backgroundColor(this.page === target ? '#62F2A7' : '#10231D')
      .borderRadius(14)
      .onClick(() => {
        this.page = target;
      })
  }

  @Builder
  private QuotaBar(title: string, remaining: number | null, reset: string, strong: boolean): void {
    Column() {
      Row() {
        Text(title)
          .fontSize(11)
          .fontColor('#9DB0A8')
        Blank()
        Text(reset)
          .fontSize(9)
          .fontColor(this.quotaColor(remaining))
      }
      .width('100%')

      Row() {
        Text(remaining === null || remaining === undefined ? '--' : `${Math.round(remaining)}%`)
          .fontSize(strong ? 34 : 28)
          .fontWeight(FontWeight.Bold)
          .fontColor('#F3F8F6')
        Text('剩余')
          .fontSize(12)
          .fontColor('#DCE8E2')
          .margin({ left: 4, top: strong ? 14 : 11 })
      }
      .width('100%')
      .margin({ top: 4 })

      Stack({ alignContent: Alignment.Start }) {
        Row()
          .height(strong ? 14 : 9)
          .width('100%')
          .backgroundColor('#24302C')
          .borderRadius(10)
        Row()
          .height(strong ? 14 : 9)
          .width(`${Math.max(0, Math.min(100, remaining ?? 0))}%`)
          .backgroundColor(this.quotaColor(remaining))
          .borderRadius(10)
      }
      .width('100%')
      .height(strong ? 14 : 9)
      .margin({ top: 6 })
    }
    .width('100%')
    .padding(12)
    .backgroundColor('#101C19')
    .borderRadius(18)
    .margin({ bottom: 8 })
  }

  @Builder
  private QuotaPage(): void {
    Column() {
      if (this.status?.quota.available) {
        this.QuotaBar('5 小时额度', this.status.quota.shortTerm.remainingPercent, this.status.quota.shortTerm.resetLabel, false)
        this.QuotaBar('本周额度', this.status.quota.weekly.remainingPercent, this.status.quota.weekly.resetLabel, true)
      } else {
        Text(this.status?.quota.message ?? '暂无额度数据')
          .fontSize(14)
          .fontColor('#9DB0A8')
          .textAlign(TextAlign.Center)
          .width('100%')
          .padding(20)
      }
    }
    .width('100%')
    .padding({ left: 12, right: 12 })
  }

  @Builder
  private ApprovalCard(approval: WatchApproval): void {
    Column() {
      Text(approval.title)
        .fontSize(15)
        .fontWeight(FontWeight.Bold)
        .fontColor('#F3F8F6')
        .maxLines(1)
        .textOverflow({ overflow: TextOverflow.Ellipsis })
        .width('100%')
      Text(approval.reason)
        .fontSize(11)
        .fontColor('#9DB0A8')
        .maxLines(2)
        .textOverflow({ overflow: TextOverflow.Ellipsis })
        .margin({ top: 4 })
        .width('100%')
      if (approval.target.length > 0) {
        Text(approval.target)
          .fontSize(9)
          .fontColor('#6F827A')
          .maxLines(1)
          .textOverflow({ overflow: TextOverflow.Ellipsis })
          .margin({ top: 3 })
          .width('100%')
      }
      Row() {
        Button(this.busyApprovalId === approval.id ? '处理中' : '拒绝')
          .enabled(this.busyApprovalId !== approval.id)
          .height(32)
          .layoutWeight(1)
          .fontSize(12)
          .fontColor('#FFC5C1')
          .backgroundColor('#3A1818')
          .borderRadius(16)
          .onClick(() => this.decide(approval, 'deny'))
        Button(this.busyApprovalId === approval.id ? '处理中' : '批准')
          .enabled(this.busyApprovalId !== approval.id)
          .height(32)
          .layoutWeight(1)
          .fontSize(12)
          .fontColor('#042116')
          .backgroundColor('#62F2A7')
          .borderRadius(16)
          .margin({ left: 8 })
          .onClick(() => this.decide(approval, 'allow'))
      }
      .width('100%')
      .margin({ top: 10 })
    }
    .width('100%')
    .padding(12)
    .backgroundColor('#101C19')
    .borderRadius(18)
    .margin({ bottom: 8 })
  }

  @Builder
  private PendingPage(): void {
    Column() {
      if ((this.status?.approvals.pending.length ?? 0) === 0) {
        Text('暂无待审批')
          .fontSize(14)
          .fontColor('#9DB0A8')
          .textAlign(TextAlign.Center)
          .width('100%')
          .padding(20)
      } else {
        ForEach(this.status?.approvals.pending ?? [], (approval: WatchApproval) => {
          this.ApprovalCard(approval)
        }, (approval: WatchApproval) => approval.id)
      }
    }
    .width('100%')
    .padding({ left: 12, right: 12 })
  }

  @Builder
  private NotificationCard(notification: WatchNotification): void {
    Column() {
      Row() {
        Text(notification.title)
          .fontSize(14)
          .fontWeight(FontWeight.Bold)
          .fontColor('#F3F8F6')
          .maxLines(1)
          .textOverflow({ overflow: TextOverflow.Ellipsis })
          .layoutWeight(1)
        Text(this.timeLabel(notification.createdAt))
          .fontSize(9)
          .fontColor('#62F2A7')
          .margin({ left: 6 })
      }
      .width('100%')

      Text(notification.message || '暂无通知详情')
        .fontSize(11)
        .fontColor('#9DB0A8')
        .maxLines(2)
        .textOverflow({ overflow: TextOverflow.Ellipsis })
        .margin({ top: 4 })
        .width('100%')

      if (notification.approvalId) {
        Button('查看审批')
          .height(28)
          .width('100%')
          .fontSize(11)
          .fontColor('#042116')
          .backgroundColor('#62F2A7')
          .borderRadius(14)
          .margin({ top: 8 })
          .onClick(() => {
            this.page = 'pending';
          })
      }
    }
    .width('100%')
    .padding(12)
    .backgroundColor('#101C19')
    .borderRadius(18)
    .margin({ bottom: 8 })
  }

  @Builder
  private NotificationsPage(): void {
    Column() {
      if ((this.status?.notifications?.length ?? 0) === 0) {
        Text('暂无通知')
          .fontSize(14)
          .fontColor('#9DB0A8')
          .textAlign(TextAlign.Center)
          .width('100%')
          .padding(20)
      } else {
        ForEach(this.status?.notifications ?? [], (notification: WatchNotification) => {
          this.NotificationCard(notification)
        }, (notification: WatchNotification) => notification.id)
      }
    }
    .width('100%')
    .padding({ left: 12, right: 12 })
  }

  @Builder
  private HistoryPage(): void {
    Column() {
      if ((this.status?.approvals.recent.length ?? 0) === 0) {
        Text('暂无审批历史')
          .fontSize(14)
          .fontColor('#9DB0A8')
          .textAlign(TextAlign.Center)
          .width('100%')
          .padding(20)
      } else {
        ForEach(this.status?.approvals.recent ?? [], (approval: WatchApproval) => {
          Column() {
            Text(approval.title)
              .fontSize(14)
              .fontWeight(FontWeight.Bold)
              .fontColor('#F3F8F6')
              .maxLines(1)
              .textOverflow({ overflow: TextOverflow.Ellipsis })
            Text(approval.status === 'allow' ? '已批准' : approval.status === 'deny' ? '已拒绝' : '已处理')
              .fontSize(11)
              .fontColor(approval.status === 'allow' ? '#62F2A7' : '#FF756F')
              .margin({ top: 4 })
          }
          .width('100%')
          .padding(12)
          .backgroundColor('#101C19')
          .borderRadius(18)
          .margin({ bottom: 8 })
        }, (approval: WatchApproval) => approval.id)
      }
    }
    .width('100%')
    .padding({ left: 12, right: 12 })
  }

  build() {
    Column() {
      this.Header()
      this.Tabs()
      Scroll() {
        Column() {
          if (this.loading) {
            Text('正在加载')
              .fontSize(14)
              .fontColor('#9DB0A8')
              .padding(24)
          } else if (this.page === 'quota') {
            this.QuotaPage()
          } else if (this.page === 'pending') {
            this.PendingPage()
          } else if (this.page === 'notifications') {
            this.NotificationsPage()
          } else {
            this.HistoryPage()
          }
        }
        .width('100%')
      }
      .layoutWeight(1)
      .scrollBar(BarState.Off)
    }
    .width('100%')
    .height('100%')
    .backgroundColor('#07100E')
  }
}
