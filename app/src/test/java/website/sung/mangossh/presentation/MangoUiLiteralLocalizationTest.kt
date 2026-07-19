package website.sung.mangossh.presentation

import org.junit.Assert.assertEquals
import org.junit.Test

class MangoUiLiteralLocalizationTest {
    @Test
    fun resolvesNewFixedUiAndVaultMessagesForEnglish() {
        assertEquals(
            "Port-forward configuration is incomplete.",
            MangoUiLiteralLocalization.resolve("端口转发配置不完整", "en"),
        )
        assertEquals(
            "Unable to save the encrypted vault. Existing data was not overwritten.",
            MangoUiLiteralLocalization.resolve("无法保存加密保险库。数据未被覆盖。", "en"),
        )
        assertEquals(
            "Select a private-key file rather than a public-key file.",
            MangoUiLiteralLocalization.resolve("请选择私钥文件，而不是公钥文件。", "en"),
        )
    }

    @Test
    fun resolvesTemplatesWithoutChangingRuntimeValues() {
        assertEquals(
            "Generated 生产服务器.",
            MangoUiLiteralLocalization.resolve("已生成 生产服务器。", "en"),
        )
        assertEquals(
            "Password for 密钥一",
            MangoUiLiteralLocalization.resolve("密钥一 的密码", "en"),
        )
        assertEquals(
            "PIN must contain 4 to 12 digits.",
            MangoUiLiteralLocalization.resolve("PIN 必须为 4 到 12 位数字。", "en"),
        )
        assertEquals(
            "\r\n[MangoSSH] Resource query failed: timeout\r\n",
            MangoUiLiteralLocalization.resolve("\r\n[MangoSSH] 资源查询失败：timeout\r\n", "en"),
        )
    }

    @Test
    fun retainsChineseAndUnknownValuesVerbatim() {
        assertEquals(
            "端口转发配置不完整",
            MangoUiLiteralLocalization.resolve("端口转发配置不完整", "zh"),
        )
        assertEquals(
            "自定义标签",
            MangoUiLiteralLocalization.resolve("自定义标签", "en"),
        )
        assertEquals(
            "\r\n[MangoSSH] 服务器自定义提示\r\n",
            MangoUiLiteralLocalization.resolve("\r\n[MangoSSH] 服务器自定义提示\r\n", "en"),
        )
    }
}
