import { describe, expect, test } from "bun:test";
import { execute } from "./runtime";

const run = (command: string, files: Record<string, string> = {}, extra: Record<string, unknown> = {}) =>
  execute(JSON.stringify({ id: "test", command, cwd: ".", files, timeoutMs: 10_000, maxOutputChars: 20_000, ...extra }));

describe("just-bash 隔离协议", () => {
  test("读取文件、管道和退出码保留", async () => {
    const result = await run("cat src/a.txt | tr a-z A-Z", { "src/a.txt": "hello" });
    expect(result.ok).toBe(true);
    expect(result.stdout).toContain("HELLO");
    expect(result.exitCode).toBe(0);
  });

  test("拒绝越界路径和不合法 cwd", async () => {
    await expect(run("pwd", { "../outside.txt": "x" })).rejects.toThrow("invalid workspace path");
    await expect(run("pwd", {}, { cwd: "/../outside" })).rejects.toThrow("invalid cwd");
  });

  test("输出按请求限制并报告截断", async () => {
    const result = await run("printf 1234567890", {}, { maxOutputChars: 5 });
    expect(result.truncated).toBe(true);
    expect(result.stdout.length).toBeLessThanOrEqual(5);
  });

  test("连续执行 100 次不会串用上一次 Workspace 快照", async () => {
    for (let index = 0; index < 100; index += 1) {
      const result = await run("cat value.txt", { "value.txt": `run-${index}` });
      expect(result.ok).toBe(true);
      expect(result.stdout).toBe(`run-${index}`);
      expect(result.files["value.txt"]).toBe(`run-${index}`);
      expect(Object.keys(result.files)).toEqual(["value.txt"]);
    }
  });

  test("命令失败保留退出码和 stderr", async () => {
    const result = await run("cat missing.txt");
    expect(result.ok).toBe(false);
    expect(result.exitCode).not.toBe(0);
    expect(result.stderr.length).toBeGreaterThan(0);
  });

  test("内存修改不进入下次调用且用户 bin 目录不会混入系统文件", async () => {
    const first = await run("mkdir -p bin; echo draft > bin/draft.txt");
    expect(first.files["bin/draft.txt"]).toBe("draft\n");
    const second = await run("find . -type f");
    expect(second.files).toEqual({});
    expect(second.stdout).not.toContain("draft.txt");
  });

  test("JSON 处理和文本转换支持管道及变量", async () => {
    const result = await run('name=world; echo "$name"; cat data.json | jq -r .name | sed s/world/hello/ | awk \'{print toupper($0)}\'', { "data.json": '{"name":"world"}' });
    expect(result.stdout).toBe("world\nHELLO\n");
  });

  test("网络及真实系统运行时不在命令集合中", async () => {
    for (const command of ["curl https://example.com", "python --version", "node --version"]) {
      const result = await run(command);
      expect(result.exitCode).not.toBe(0);
    }
  });
});
