package com.android.everytalk.data.computer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** 锁定 root helper 的关键安全边界，防止后续修改重新放开 Docker 控制权。 */
class ComputerContainerHelperContractTest {
    @Test
    fun `上传Shell资产前必须转成Linux换行`() {
        listOf(
            shellAssetFile("everytalk-containerctl.sh"),
            shellAssetFile("runtime-wrapper.sh"),
        ).forEach { file ->
            val normalized = normalizeComputerShellAsset(file.readBytes())
            assertFalse("${file.name} 仍包含 CR 字节", normalized.any { it == '\r'.code.toByte() })
            assertTrue(String(normalized, Charsets.UTF_8).startsWith("#!/bin/sh\n"))
        }
    }

    @Test
    fun `Workspace 和 Preview Container 都禁止自动重启`() {
        val source = helperSource()

        assertEquals(2, Regex("--restart\\s+no").findAll(source).count())
        assertTrue(source.contains("docker update --restart=no"))
        assertFalse(source.contains("unless-stopped"))
    }

    @Test
    fun `helper 只接受固定子命令参数并校验资源归属`() {
        val source = helperSource()
        val expectedArgumentCounts = mapOf(
            "install" to 2,
            "version" to 0,
            "build-image" to 0,
            "set-network" to 1,
            "ensure-workspace" to 1,
            "container-address" to 1,
            "run" to 4,
            "run-background" to 4,
            "start-execution" to 6,
            "execution-status" to 3,
            "execution-result" to 6,
            "list-executions" to 1,
            "cancel-execution" to 3,
            "terminal" to 1,
            "open-public" to 4,
            "preview-status" to 1,
            "close-public" to 1,
            "delete-workspace" to 2,
        )

        expectedArgumentCounts.forEach { (command, count) ->
            val contract = Regex("${Regex.escape(command)}\\)\\s+require_exact_args $count")
            assertTrue("$command 必须校验参数数量", contract.containsMatchIn(source))
        }
        assertTrue(source.contains("Container 归属校验失败"))
        assertTrue(source.contains("Preview 归属校验失败"))
        assertTrue(source.contains("container_allowed_owner_uids"))
        assertTrue(source.contains("EVERYTALK_ALLOWED_OWNER_UIDS"))
        assertTrue(source.contains("docker exec -i -e \"EVERYTALK_ALLOWED_OWNER_UIDS=\$owner_uids\""))
        assertTrue(source.contains("root_real=\"$(realpath -e -- \"\$root\""))
        assertTrue(source.contains("execution_real=\"$(realpath -e -- \"\$execution_dir\""))
        assertTrue(source.contains("execution_owner=\"$(stat -c \"%u\" -- \"\$execution_dir\""))
        assertTrue(source.contains("workspace_real=\"$(realpath -e -- \"\$workspace\""))
        assertTrue(source.contains("executions_real=\"$(realpath -e -- \"\$executions\""))
        assertFalse(source.contains("docker exec \"\$name\" mkdir -p /workspace/.everytalk/executions"))
        assertTrue(source.contains("已安装 helper 禁止重复 install"))
        assertTrue(source.contains("/usr/bin/timeout --signal=TERM"))
        assertFalse(source.contains("docker \$@"))
    }

    @Test
    fun `后台任务状态保存在VPS且删除Workspace前会停止任务`() {
        val helper = helperSource()
        val runtimeWrapper = runtimeWrapperSource()
        val deleteWorkspaceBody = helper
            .substringAfter("delete_workspace() {")
            .substringBefore("\n}\n\nrequire_root")

        assertTrue(helper.contains("VERSION=\"8\""))
        assertTrue(helper.contains("docker exec -i"))
        assertTrue(helper.contains("runtime_target=\"\$RUNTIME_WRAPPER_PATH-\$runtime_hash\""))
        assertTrue(helper.contains("ln -sfn"))
        assertTrue(runtimeWrapper.contains("EVERYTALK_EXEC_V1"))
        assertTrue(runtimeWrapper.contains("EVERYTALK_EXEC_HOST_V1"))
        assertFalse(runtimeWrapper.contains("host-background"))
        assertFalse(runtimeWrapper.contains("--host-files"))
        assertTrue(runtimeWrapper.contains("--envelope"))
        assertTrue(deleteWorkspaceBody.contains("stop_workspace_backgrounds"))
        assertTrue(runtimeWrapper.contains("status=RUNNING"))
        assertTrue(runtimeWrapper.contains("start_ticks="))
        assertTrue(runtimeWrapper.contains("status=SUCCEEDED"))
        assertTrue(runtimeWrapper.contains("status=FAILED"))
        assertTrue(runtimeWrapper.contains("state_owner_allowed"))
        assertTrue(runtimeWrapper.contains("execution_directory_safe"))
        assertTrue(runtimeWrapper.contains("execution_parent_safe"))
        assertTrue(runtimeWrapper.contains("if ! execution_parent_safe || ! execution_directory_safe"))
        assertTrue(runtimeWrapper.contains("path_owner_allowed"))
        assertTrue(runtimeWrapper.contains("state_has_expected_identity"))
        assertTrue(runtimeWrapper.contains("process_group_owner_allowed"))
        assertTrue(runtimeWrapper.contains("ensure_host_private_dir"))
        assertTrue(runtimeWrapper.contains("root_real=\"$(realpath -e -- \"\$root\""))
        assertTrue(runtimeWrapper.contains("write_v2_state UNKNOWN"))
        assertTrue(runtimeWrapper.contains("request hash 冲突"))
    }

    @Test
    fun `Wrapper升级会重建受管Container并保留Workspace挂载`() {
        val helper = helperSource()
        val ensureWorkspaceBody = helper
            .replace("\r\n", "\n")
            .substringAfter("ensure_workspace() {")
            .substringBefore("\n}\n\ncontainer_address")

        assertTrue(ensureWorkspaceBody.contains("runtime_hash=\"\$(runtime_wrapper_hash)\""))
        assertTrue(ensureWorkspaceBody.contains("mounted_wrapper_hash="))
        assertTrue(ensureWorkspaceBody.contains("com.everytalk.wrapper=\$runtime_hash"))
        assertTrue(ensureWorkspaceBody.contains("stop_workspace_backgrounds \"\$workspace_id\" \"\$name\""))
        assertTrue(ensureWorkspaceBody.contains("docker rm --force \"\$name\""))
        assertTrue(ensureWorkspaceBody.contains("--mount \"type=bind,src=\$workspace,dst=/workspace\""))
        assertTrue(ensureWorkspaceBody.contains("src=\$RUNTIME_WRAPPER_PATH-\$runtime_hash"))
        assertFalse(ensureWorkspaceBody.contains("rm -rf -- \"\$workspace\""))
    }

    private fun helperSource(): String {
        val candidates = listOf(
            File("src/main/assets/computer/everytalk-containerctl.sh"),
            File("app/src/main/assets/computer/everytalk-containerctl.sh"),
            File("app1/app/src/main/assets/computer/everytalk-containerctl.sh"),
        )
        return requireNotNull(candidates.firstOrNull(File::isFile)) {
            "找不到 everytalk-containerctl.sh"
        }.readText(Charsets.UTF_8)
    }

    private fun runtimeWrapperSource(): String {
        val candidates = listOf(
            File("src/main/assets/computer/runtime-wrapper.sh"),
            File("app/src/main/assets/computer/runtime-wrapper.sh"),
            File("app1/app/src/main/assets/computer/runtime-wrapper.sh"),
        )
        return requireNotNull(candidates.firstOrNull(File::isFile)) {
            "找不到 runtime-wrapper.sh"
        }.readText(Charsets.UTF_8)
    }

    private fun shellAssetFile(name: String): File {
        val candidates = listOf(
            File("src/main/assets/computer/$name"),
            File("app/src/main/assets/computer/$name"),
            File("app1/app/src/main/assets/computer/$name"),
        )
        return requireNotNull(candidates.firstOrNull(File::isFile)) { "找不到 $name" }
    }
}
