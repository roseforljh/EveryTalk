import assert from "node:assert/strict";
import test from "node:test";
import { __test } from "../api/index.js";

test("Skill ID 只能表示一个 GitHub 仓库和 Skill 名称", () => {
  const encoded = __test.encodeSkillId({ repository: "anthropics/skills", skillName: "pdf" });
  assert.deepEqual(__test.decodeSkillId(encoded), { repository: "anthropics/skills", skillName: "pdf" });
  assert.equal(__test.safeRepository("https://example.com/a"), false);
});

test("文件树哈希稳定且包含路径", () => {
  const first = __test.treeHash([{ path: "SKILL.md", sha256: "a" }]);
  assert.equal(first, __test.treeHash([{ path: "SKILL.md", sha256: "a" }]));
  assert.notEqual(first, __test.treeHash([{ path: "other.md", sha256: "a" }]));
});

test("Vercel 重复查询参数只读取首个值", () => {
  assert.equal(__test.firstQuery(["skill-id", "cache-bust"]), "skill-id");
});

test("公开详情页按最严重审计结果归一化", () => {
  const parsed = __test.parsePublicAudit(">Security Audits< >Pass< >Fail<");
  assert.equal(parsed.status, "FAIL");
  assert.deepEqual(parsed.audit, { source: "skills.sh", pass: 1, warn: 0, fail: 1 });
});

test("单实例基础限流在窗口内拒绝第 61 次请求", () => {
  const request = { headers: { "x-forwarded-for": "203.0.113.7" } };
  for (let index = 0; index < 60; index += 1) assert.equal(__test.allowRequest(request, 1000), true);
  assert.equal(__test.allowRequest(request, 1000), false);
  assert.equal(__test.allowRequest(request, 61_001), true);
});
