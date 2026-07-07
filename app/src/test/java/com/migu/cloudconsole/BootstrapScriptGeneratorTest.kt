package com.migu.cloudconsole

import org.junit.Assert.assertTrue
import org.junit.Test

class BootstrapScriptGeneratorTest {
    @Test
    fun `ocr api example should point to chat completions`() {
        val model = ModelCatalog.getModel("PADDLEOCR_VL_16")
        val text = BootstrapScriptGenerator.buildApiExample("https://chat.example.com", "", model)
        assertTrue(text.contains("/v1/chat/completions"))
        assertTrue(text.contains("PaddleOCR-VL-1.6-GGUF"))
    }

    @Test
    fun `bootstrap script should include tunnel token`() {
        val settings = AppSettings(tunnelToken = "token-123", modelVariant = "Q4_K_M")
        val model = ModelCatalog.getModel(settings.modelVariant)
        val text = BootstrapScriptGenerator.buildScript(settings, model)
        assertTrue(text.contains("token-123"))
        assertTrue(text.contains(model.fileName))
    }

    @Test
    fun `bootstrap script should route github downloads through mirror`() {
        val settings = AppSettings(modelVariant = "Q4_K_M")
        val model = ModelCatalog.getModel(settings.modelVariant)
        val text = BootstrapScriptGenerator.buildScript(settings, model)
        assertTrue(text.contains("https://github.999cq.fun/https://github.com/cloudflare/cloudflared/releases/latest/download/cloudflared-windows-amd64.exe"))
        assertTrue(text.contains("https://github.com/cloudflare/cloudflared/releases/latest/download/cloudflared-windows-amd64.exe"))
        assertTrue(text.contains("https://github.999cq.fun/https://github.com/ggml-org/llama.cpp/releases/download/b9490/llama-b9490-bin-win-cuda-13.3-x64.zip"))
        assertTrue(text.contains("https://github.999cq.fun/https://github.com/ggml-org/llama.cpp/releases/download/b9490/cudart-llama-bin-win-cuda-13.3-x64.zip"))
    }

    @Test
    fun `bootstrap script should include hugging face mirror candidates`() {
        val settings = AppSettings(modelVariant = "Q4_K_M")
        val model = ModelCatalog.getModel(settings.modelVariant)
        val text = BootstrapScriptGenerator.buildScript(settings, model)
        assertTrue(text.contains("https://hf-mirror.com/HauhauCS/Qwen3.6-35B-A3B-Uncensored-HauhauCS-Aggressive/resolve/main/${model.remoteName}"))
        assertTrue(text.contains("https://huggingface.co/HauhauCS/Qwen3.6-35B-A3B-Uncensored-HauhauCS-Aggressive/resolve/main/${model.remoteName}"))
    }

    @Test
    fun `download plan should prefer hf mirror for ocr model`() {
        val settings = AppSettings(modelVariant = "PADDLEOCR_VL_16")
        val model = ModelCatalog.getModel(settings.modelVariant)
        val targets = BootstrapScriptGenerator.buildDownloadTargets(settings, model)
        val modelTarget = targets.first { it.label == model.fileName }
        val mmprojTarget = targets.first { it.label == model.mmprojFileName }
        assertTrue(modelTarget.urls.first().startsWith("https://hf-mirror.com/"))
        assertTrue(modelTarget.urls.last().startsWith("https://huggingface.co/"))
        assertTrue(mmprojTarget.urls.first().startsWith("https://hf-mirror.com/"))
        assertTrue(mmprojTarget.urls.last().startsWith("https://huggingface.co/"))
    }

    @Test
    fun `bootstrap script should switch to repository command when repo base url is configured`() {
        val settings = AppSettings(
            modelVariant = "PADDLEOCR_VL_16",
            tunnelToken = "token-123",
            bootstrapRepoBaseUrl = "https://github.999cq.fun/https://raw.githubusercontent.com/demo/repo/main/scripts/",
        )
        val model = ModelCatalog.getModel(settings.modelVariant)
        val text = BootstrapScriptGenerator.buildScript(settings, model)
        assertTrue(text.contains("bootstrap.ps1"))
        assertTrue(text.contains("-RepoBaseUrl 'https://github.999cq.fun/https://raw.githubusercontent.com/demo/repo/main/scripts'"))
        assertTrue(text.contains("-ModelKey 'PADDLEOCR_VL_16'"))
        assertTrue(text.contains("-TunnelToken 'token-123'"))
    }
}
