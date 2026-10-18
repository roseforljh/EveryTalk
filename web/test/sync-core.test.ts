import { describe, expect, test } from "bun:test";
import { mergeRemote } from "../src/sync-core";
import type { PendingChange, StoredRecord, SyncRecord } from "../src/types";

const session = (modified: number, title: string): StoredRecord => ({ key: "sessions:a", entity: "sessions", id: "a", deleted: false, payload: { clientModifiedAt: modified, title } });
const remote = (modified: number, title: string): SyncRecord => ({ entity: "sessions", id: "a", deleted: false, payload: { clientModifiedAt: modified, title } });

describe("同步合并规则", () => {
  test("本地待上传版本更晚时保留本地", () => {
    const pending: PendingChange = { key: "sessions:a", entity: "sessions", id: "a", deleted: false, revision: "1", payload: { clientModifiedAt: 20, title: "本地" } };
    expect(mergeRemote(session(20, "本地"), remote(10, "远端"), pending).payload.title).toBe("本地");
  });
  test("消息远端只补充本地不存在的字段", () => {
    const current: StoredRecord = { key: "messages:m", entity: "messages", id: "m", deleted: false, payload: { text: "原文", timestamp: 1 } };
    const result = mergeRemote(current, { entity: "messages", id: "m", deleted: false, payload: { text: "远端改写", reasoning: "补充" } });
    expect(result.payload).toEqual({ text: "原文", timestamp: 1, reasoning: "补充" });
  });
});
