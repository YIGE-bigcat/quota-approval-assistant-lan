import assert from "node:assert/strict";
import { test } from "node:test";
import { quotaSummary, shortNotificationSummary } from "../src/notify.mjs";

test("quotaSummary includes 5-hour and weekly windows", () => {
  const quota = {
    capturedAt: Math.floor(Date.now() / 1000),
    primary: {
      usedPercent: 20,
      remainingPercent: 80,
    },
    secondary: {
      usedPercent: 37,
      remainingPercent: 63,
    },
  };

  assert.equal(quotaSummary(quota), "5小时已用20% 剩80%\n本周已用37% 剩63%");
});

test("quotaSummary handles missing quota data", () => {
  assert.equal(quotaSummary(null), "额度暂无最新数据");
});

test("shortNotificationSummary returns a compact title", () => {
  assert.equal(shortNotificationSummary("需要读取电脑端应用启动说明并检查配置"), "读取电脑端应用启动说");
});
