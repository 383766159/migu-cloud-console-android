package com.migu.cloudconsole

object BootstrapScriptGenerator {
    data class DownloadTarget(
        val label: String,
        val urls: List<String>,
    )

    private const val githubMirrorPrefix = "https://github.999cq.fun/"
    private const val huggingFaceMirrorPrefix = "https://hf-mirror.com/"
    private const val cloudflaredGithubUrl = "https://github.com/cloudflare/cloudflared/releases/latest/download/cloudflared-windows-amd64.exe"
    private const val llamaZipGithubUrl = "https://github.com/ggml-org/llama.cpp/releases/download/b9490/llama-b9490-bin-win-cuda-13.3-x64.zip"
    private const val cudartZipGithubUrl = "https://github.com/ggml-org/llama.cpp/releases/download/b9490/cudart-llama-bin-win-cuda-13.3-x64.zip"

    fun normalizeBootstrapRepoBaseUrl(url: String): String {
        return url.trim().trimEnd('/')
    }

    fun buildTunnelBaseUrl(host: String): String {
        val trimmed = host.trim().removePrefix("https://").removePrefix("http://").trimEnd('/')
        return if (trimmed.isBlank()) "" else "https://$trimmed"
    }

    fun preferGithubMirror(url: String): String {
        val trimmed = url.trim()
        if (trimmed.isBlank()) return trimmed
        if (trimmed.startsWith(githubMirrorPrefix, ignoreCase = true)) return trimmed
        return if (trimmed.startsWith("https://github.com/", ignoreCase = true)) {
            githubMirrorPrefix + trimmed
        } else {
            trimmed
        }
    }

    fun defaultCloudflaredUrl(): String = preferGithubMirror(cloudflaredGithubUrl)

    fun preferHuggingFaceMirror(url: String): String {
        val trimmed = url.trim()
        if (trimmed.isBlank()) return trimmed
        if (trimmed.startsWith(huggingFaceMirrorPrefix, ignoreCase = true)) return trimmed
        return if (trimmed.startsWith("https://huggingface.co/", ignoreCase = true)) {
            huggingFaceMirrorPrefix + trimmed.removePrefix("https://huggingface.co/")
        } else {
            trimmed
        }
    }

    private fun githubDownloadCandidates(url: String): List<String> {
        val trimmed = url.trim()
        if (trimmed.isBlank()) return emptyList()
        if (trimmed.startsWith(githubMirrorPrefix, ignoreCase = true)) {
            val original = trimmed.removePrefix(githubMirrorPrefix)
            return listOf(trimmed, original)
                .map { it.trim() }
                .filter { it.startsWith(githubMirrorPrefix, ignoreCase = true) || it.startsWith("https://github.com/", ignoreCase = true) }
                .distinct()
        }
        val mirror = preferGithubMirror(trimmed)
        return listOf(mirror, trimmed).distinct()
    }

    private fun huggingFaceDownloadCandidates(url: String): List<String> {
        val mirror = preferHuggingFaceMirror(url)
        return listOf(mirror, url).distinct()
    }

    fun buildApiExample(baseUrl: String, modelId: String, model: ModelInfo): String {
        if (baseUrl.isBlank()) return ""

        return if (ModelCatalog.isOcrModel(model)) {
            val actualModelId = modelId.ifBlank { "PaddleOCR-VL-1.6-GGUF" }
            """
curl -X POST "$baseUrl/v1/chat/completions" \
  -H "Content-Type: application/json" \
  -d '{"model":"$actualModelId","messages":[{"role":"user","content":[{"type":"text","text":"请识别这张图片中的主要内容，返回简洁中文。"},{"type":"image_url","image_url":{"url":"https://paddle-model-ecology.bj.bcebos.com/paddlex/imgs/demo_image/paddleocr_vl_demo.png"}}]}],"max_tokens":512,"temperature":0}'
            """.trim()
        } else {
            val actualModelId = modelId.ifBlank { model.fileName }
            """
curl -X POST "$baseUrl/v1/chat/completions" \
  -H "Content-Type: application/json" \
  -d '{"model":"$actualModelId","messages":[{"role":"user","content":"你好，请返回一句已启动。"}],"max_tokens":64,"temperature":0}'
            """.trim()
        }
    }

    fun buildScript(settings: AppSettings, model: ModelInfo): String {
        val repoBaseUrl = normalizeBootstrapRepoBaseUrl(settings.bootstrapRepoBaseUrl)
        return if (repoBaseUrl.isNotBlank()) {
            buildRepositoryBootstrapCommand(repoBaseUrl, settings, model)
        } else if (ModelCatalog.isOcrModel(model)) {
            buildPaddleScript(settings, model)
        } else {
            buildQwenScript(settings, model)
        }
    }

    fun buildDownloadTargets(settings: AppSettings, model: ModelInfo): List<DownloadTarget> {
        val sharedTargets = listOf(
            DownloadTarget("cloudflared", githubDownloadCandidates(settings.cloudflaredUrl)),
            DownloadTarget("cudart", githubDownloadCandidates(cudartZipGithubUrl)),
            DownloadTarget("llama.cpp", githubDownloadCandidates(llamaZipGithubUrl)),
        )
        return if (ModelCatalog.isOcrModel(model)) {
            sharedTargets + listOf(
                DownloadTarget(
                    model.fileName,
                    huggingFaceDownloadCandidates("https://huggingface.co/PaddlePaddle/PaddleOCR-VL-1.6-GGUF/resolve/main/PaddleOCR-VL-1.6-GGUF.gguf"),
                ),
                DownloadTarget(
                    model.mmprojFileName,
                    huggingFaceDownloadCandidates("https://huggingface.co/PaddlePaddle/PaddleOCR-VL-1.6-GGUF/resolve/main/PaddleOCR-VL-1.6-GGUF-mmproj.gguf"),
                ),
            )
        } else {
            sharedTargets + listOf(
                DownloadTarget(
                    model.remoteName,
                    huggingFaceDownloadCandidates("https://huggingface.co/HauhauCS/Qwen3.6-35B-A3B-Uncensored-HauhauCS-Aggressive/resolve/main/${model.remoteName}"),
                ),
                DownloadTarget(
                    "mmproj-QW36-35B-A3B-Uncensored-HauhauCS-Aggressive-f16.gguf",
                    huggingFaceDownloadCandidates("https://huggingface.co/HauhauCS/Qwen3.6-35B-A3B-Uncensored-HauhauCS-Aggressive/resolve/main/mmproj-QW36-35B-A3B-Uncensored-HauhauCS-Aggressive-f16.gguf"),
                ),
            )
        }
    }

    private fun toPowerShellArray(values: List<String>): String {
        return values.joinToString(prefix = "@(", postfix = ")") { "'${it.replace("'", "''")}'" }
    }

    private fun toPowerShellSingleQuoted(value: String): String {
        return "'${value.replace("'", "''")}'"
    }

    private fun buildRepositoryBootstrapCommand(repoBaseUrl: String, settings: AppSettings, model: ModelInfo): String {
        val normalizedBase = normalizeBootstrapRepoBaseUrl(repoBaseUrl)
        return """
${'$'}scriptBase = ${toPowerShellSingleQuoted(normalizedBase)}
${'$'}scriptPath = Join-Path ${'$'}env:TEMP 'migu-bootstrap.ps1'
iwr -UseBasicParsing "${'$'}scriptBase/bootstrap.ps1" -OutFile ${'$'}scriptPath
& powershell.exe -NoProfile -ExecutionPolicy Bypass -File ${'$'}scriptPath `
  -RepoBaseUrl ${toPowerShellSingleQuoted(normalizedBase)} `
  -ModelKey ${toPowerShellSingleQuoted(model.key)} `
  -Port ${settings.port} `
  -TunnelHostname ${toPowerShellSingleQuoted(settings.tunnelHostname)} `
  -TunnelToken ${toPowerShellSingleQuoted(settings.tunnelToken)} `
  -CloudflaredUrl ${toPowerShellSingleQuoted(settings.cloudflaredUrl)}
        """.trimIndent()
    }

    private fun buildQwenScript(settings: AppSettings, model: ModelInfo): String {
        val llamaZipUrls = toPowerShellArray(githubDownloadCandidates(llamaZipGithubUrl))
        val cudartZipUrls = toPowerShellArray(githubDownloadCandidates(cudartZipGithubUrl))
        val cloudflaredUrls = toPowerShellArray(githubDownloadCandidates(settings.cloudflaredUrl))
        val modelUrls = toPowerShellArray(
            huggingFaceDownloadCandidates("https://huggingface.co/HauhauCS/Qwen3.6-35B-A3B-Uncensored-HauhauCS-Aggressive/resolve/main/${model.remoteName}"),
        )
        val mmprojUrls = toPowerShellArray(
            huggingFaceDownloadCandidates("https://huggingface.co/HauhauCS/Qwen3.6-35B-A3B-Uncensored-HauhauCS-Aggressive/resolve/main/mmproj-QW36-35B-A3B-Uncensored-HauhauCS-Aggressive-f16.gguf"),
        )

        return """
${'$'}ErrorActionPreference = 'Stop'
Set-StrictMode -Version 2.0

function Write-Stage([string]${'$'}Message) {
  Write-Host ""
  Write-Host "==> ${'$'}Message" -ForegroundColor Cyan
}

function Ensure-Directory([string]${'$'}Path) {
  if (-not (Test-Path -LiteralPath ${'$'}Path)) {
    New-Item -ItemType Directory -Path ${'$'}Path -Force | Out-Null
  }
}

function Invoke-Download([Alias('Url')][string[]]${'$'}Urls, [string]${'$'}OutputPath, [string]${'$'}Label) {
  Ensure-Directory -Path (Split-Path -Parent ${'$'}OutputPath)
  ${'$'}uniqueUrls = @(${ '$' }Urls | Where-Object { -not [string]::IsNullOrWhiteSpace(${ '$' }_) } | Select-Object -Unique)
  if (${ '$' }uniqueUrls.Count -eq 0) { throw "未提供可用下载地址: ${'$'}Label" }

  ${'$'}tempPath = "${'$'}OutputPath.download"
  ${'$'}lastError = ''
  foreach (${ '$' }url in ${'$'}uniqueUrls) {
    Write-Stage "下载 ${'$'}Label"
    Write-Host "尝试地址: ${'$'}url" -ForegroundColor DarkGray
    if (Test-Path -LiteralPath ${'$'}tempPath) {
      ${'$'}existingSize = (Get-Item -LiteralPath ${'$'}tempPath).Length
      Write-Host "检测到未完成下载，继续续传: ${'$'}existingSize bytes" -ForegroundColor DarkGray
    }
    & curl.exe -fL -C - --connect-timeout 10 --retry 5 --retry-all-errors --retry-delay 3 --speed-limit 16384 --speed-time 30 -o ${'$'}tempPath ${'$'}url
    if (${ '$' }LASTEXITCODE -eq 0 -and (Test-Path -LiteralPath ${'$'}tempPath)) {
      Move-Item -LiteralPath ${'$'}tempPath -Destination ${'$'}OutputPath -Force
      break
    }
    ${'$'}lastError = "地址失败: ${'$'}url, curl 退出码 ${'$'}LASTEXITCODE"
    Write-Host ${'$'}lastError -ForegroundColor Yellow
  }

  if (Test-Path -LiteralPath ${'$'}tempPath -and Test-Path -LiteralPath ${'$'}OutputPath) {
    Remove-Item -LiteralPath ${'$'}tempPath -Force -ErrorAction SilentlyContinue
  }
  if (-not (Test-Path -LiteralPath ${'$'}OutputPath)) { throw "下载失败: ${'$'}Label, ${'$'}lastError" }
}

function Test-ZipFile([string]${'$'}Path) {
  try {
    Add-Type -AssemblyName System.IO.Compression.FileSystem -ErrorAction SilentlyContinue
    ${'$'}zip = [System.IO.Compression.ZipFile]::OpenRead(${ '$' }Path)
    try { [void]${'$'}zip.Entries.Count; return ${'$'}true } finally { ${'$'}zip.Dispose() }
  } catch { return ${'$'}false }
}

function Resolve-LlamaServer([string]${'$'}Root) {
  ${'$'}direct = Join-Path ${'$'}Root 'llama-server.exe'
  if (Test-Path -LiteralPath ${'$'}direct) { return ${'$'}direct }
  ${'$'}found = Get-ChildItem -LiteralPath ${'$'}Root -Filter 'llama-server.exe' -File -Recurse -ErrorAction SilentlyContinue | Select-Object -First 1
  if (${ '$' }found) { return ${'$'}found.FullName }
  return ${'$'}null
}

${'$'}ModelDir = 'G:/Models/HauhauCS'
${'$'}LlamaDir = Join-Path ([Environment]::GetFolderPath('Desktop')) 'llama'
${'$'}Port = ${settings.port}
${'$'}Model = [ordered]@{
  Key = '${model.key}'
  FileName = '${model.fileName}'
  RemoteName = '${model.remoteName}'
  Ngl = ${model.ngl}
  ContextSize = ${model.contextSize}
  MaxTokens = ${model.maxTokens}
}
${'$'}LlamaZipUrls = $llamaZipUrls
${'$'}CudartZipUrls = $cudartZipUrls
${'$'}CloudflaredUrls = $cloudflaredUrls
${'$'}ModelUrls = $modelUrls
${'$'}MmprojUrls = $mmprojUrls
${'$'}TunnelToken = '${settings.tunnelToken}'
${'$'}CloudflaredPath = Join-Path ${'$'}LlamaDir 'cloudflared.exe'
${'$'}LlamaZipPath = Join-Path ${'$'}LlamaDir 'llama-b9490-bin-win-cuda-13.3-x64.zip'
${'$'}CudartZipPath = Join-Path ${'$'}LlamaDir 'cudart-llama-bin-win-cuda-13.3-x64.zip'
${'$'}ModelPath = Join-Path ${'$'}ModelDir ${'$'}Model.FileName
${'$'}MmprojPath = Join-Path ${'$'}ModelDir 'mmproj-QW36-35B-f16.gguf'

Ensure-Directory -Path ${'$'}ModelDir
Ensure-Directory -Path ${'$'}LlamaDir

if (-not (Test-Path -LiteralPath ${'$'}CloudflaredPath)) {
  Invoke-Download -Urls ${'$'}CloudflaredUrls -OutputPath ${'$'}CloudflaredPath -Label 'cloudflared'
}

if (-not (Test-Path -LiteralPath ${'$'}CudartZipPath) -or -not (Test-ZipFile -Path ${'$'}CudartZipPath)) {
  Invoke-Download -Urls ${'$'}CudartZipUrls -OutputPath ${'$'}CudartZipPath -Label 'cudart'
}

if (-not (Test-Path -LiteralPath ${'$'}LlamaZipPath) -or -not (Test-ZipFile -Path ${'$'}LlamaZipPath)) {
  Invoke-Download -Urls ${'$'}LlamaZipUrls -OutputPath ${'$'}LlamaZipPath -Label 'llama.cpp'
}

Write-Stage '解压运行库与 llama.cpp'
Expand-Archive -LiteralPath ${'$'}CudartZipPath -DestinationPath ${'$'}LlamaDir -Force
Expand-Archive -LiteralPath ${'$'}LlamaZipPath -DestinationPath ${'$'}LlamaDir -Force

if (-not (Test-Path -LiteralPath ${'$'}ModelPath)) {
  Invoke-Download -Urls ${'$'}ModelUrls -OutputPath ${'$'}ModelPath -Label ${'$'}Model.FileName
}

if (-not (Test-Path -LiteralPath ${'$'}MmprojPath)) {
  Invoke-Download -Urls ${'$'}MmprojUrls -OutputPath ${'$'}MmprojPath -Label 'mmproj'
}

${'$'}Server = Resolve-LlamaServer -Root ${'$'}LlamaDir
if (-not ${'$'}Server) { throw '未找到 llama-server.exe' }

Write-Stage '启动 llama-server'
Start-Process -FilePath ${'$'}Server -ArgumentList @(
  '-m', ${'$'}ModelPath,
  '-ngl', "$(${ '$' }Model.Ngl)",
  '-c', "$(${ '$' }Model.ContextSize)",
  '-n', "$(${ '$' }Model.MaxTokens)",
  '--host', '127.0.0.1',
  '--port', "${'$'}Port",
  '-np', '1',
  '--mmproj', ${'$'}MmprojPath
) -WorkingDirectory (Split-Path -Parent ${'$'}Server)

Start-Sleep -Seconds 6

Write-Stage '启动 Cloudflare Tunnel'
${'$'}TunnelTokenPath = Join-Path ${'$'}LlamaDir 'llama-8080.token'
Set-Content -LiteralPath ${'$'}TunnelTokenPath -Value ${'$'}TunnelToken -Encoding ASCII
Start-Process -FilePath ${'$'}CloudflaredPath -ArgumentList @('tunnel','run','--token-file',${'$'}TunnelTokenPath) -WorkingDirectory ${'$'}LlamaDir

Write-Stage '部署完成'
Write-Host "模型: $(${ '$' }Model.FileName)" -ForegroundColor Green
Write-Host "llama 地址: http://127.0.0.1:${'$'}Port" -ForegroundColor Green
Write-Host "完成时间: $(Get-Date -Format 'yyyy-MM-dd HH:mm:ss')" -ForegroundColor Green
        """.trimIndent()
    }

    private fun buildPaddleScript(settings: AppSettings, model: ModelInfo): String {
        val llamaZipUrls = toPowerShellArray(githubDownloadCandidates(llamaZipGithubUrl))
        val cudartZipUrls = toPowerShellArray(githubDownloadCandidates(cudartZipGithubUrl))
        val cloudflaredUrls = toPowerShellArray(githubDownloadCandidates(settings.cloudflaredUrl))
        val modelUrls = toPowerShellArray(
            huggingFaceDownloadCandidates("https://huggingface.co/PaddlePaddle/PaddleOCR-VL-1.6-GGUF/resolve/main/PaddleOCR-VL-1.6-GGUF.gguf"),
        )
        val mmprojUrls = toPowerShellArray(
            huggingFaceDownloadCandidates("https://huggingface.co/PaddlePaddle/PaddleOCR-VL-1.6-GGUF/resolve/main/PaddleOCR-VL-1.6-GGUF-mmproj.gguf"),
        )

        return """
${'$'}ErrorActionPreference = 'Stop'
Set-StrictMode -Version 2.0

function Write-Stage([string]${'$'}Message) {
  Write-Host ""
  Write-Host "==> ${'$'}Message" -ForegroundColor Cyan
}

function Ensure-Directory([string]${'$'}Path) {
  if (-not (Test-Path -LiteralPath ${'$'}Path)) {
    New-Item -ItemType Directory -Path ${'$'}Path -Force | Out-Null
  }
}

function Invoke-Download([Alias('Url')][string[]]${'$'}Urls, [string]${'$'}OutputPath, [string]${'$'}Label) {
  Ensure-Directory -Path (Split-Path -Parent ${'$'}OutputPath)
  ${'$'}uniqueUrls = @(${ '$' }Urls | Where-Object { -not [string]::IsNullOrWhiteSpace(${ '$' }_) } | Select-Object -Unique)
  if (${ '$' }uniqueUrls.Count -eq 0) { throw "未提供可用下载地址: ${'$'}Label" }

  ${'$'}tempPath = "${'$'}OutputPath.download"
  ${'$'}lastError = ''
  foreach (${ '$' }url in ${'$'}uniqueUrls) {
    Write-Stage "下载 ${'$'}Label"
    Write-Host "尝试地址: ${'$'}url" -ForegroundColor DarkGray
    if (Test-Path -LiteralPath ${'$'}tempPath) {
      ${'$'}existingSize = (Get-Item -LiteralPath ${'$'}tempPath).Length
      Write-Host "检测到未完成下载，继续续传: ${'$'}existingSize bytes" -ForegroundColor DarkGray
    }
    & curl.exe -fL -C - --connect-timeout 10 --retry 5 --retry-all-errors --retry-delay 3 --speed-limit 16384 --speed-time 30 -o ${'$'}tempPath ${'$'}url
    if (${ '$' }LASTEXITCODE -eq 0 -and (Test-Path -LiteralPath ${'$'}tempPath)) {
      Move-Item -LiteralPath ${'$'}tempPath -Destination ${'$'}OutputPath -Force
      break
    }
    ${'$'}lastError = "地址失败: ${'$'}url, curl 退出码 ${'$'}LASTEXITCODE"
    Write-Host ${'$'}lastError -ForegroundColor Yellow
  }

  if (Test-Path -LiteralPath ${'$'}tempPath -and Test-Path -LiteralPath ${'$'}OutputPath) {
    Remove-Item -LiteralPath ${'$'}tempPath -Force -ErrorAction SilentlyContinue
  }
  if (-not (Test-Path -LiteralPath ${'$'}OutputPath)) { throw "下载失败: ${'$'}Label, ${'$'}lastError" }
}

function Test-ZipFile([string]${'$'}Path) {
  try {
    Add-Type -AssemblyName System.IO.Compression.FileSystem -ErrorAction SilentlyContinue
    ${'$'}zip = [System.IO.Compression.ZipFile]::OpenRead(${ '$' }Path)
    try { [void]${'$'}zip.Entries.Count; return ${'$'}true } finally { ${'$'}zip.Dispose() }
  } catch { return ${'$'}false }
}

function Resolve-LlamaServer([string]${'$'}Root) {
  ${'$'}direct = Join-Path ${'$'}Root 'llama-server.exe'
  if (Test-Path -LiteralPath ${'$'}direct) { return ${'$'}direct }
  ${'$'}found = Get-ChildItem -LiteralPath ${'$'}Root -Filter 'llama-server.exe' -File -Recurse -ErrorAction SilentlyContinue | Select-Object -First 1
  if (${ '$' }found) { return ${'$'}found.FullName }
  return ${'$'}null
}

function Wait-HttpReady([string]${'$'}Url, [int]${'$'}TimeoutSeconds = 180) {
  ${'$'}deadline = (Get-Date).AddSeconds(${ '$' }TimeoutSeconds)
  while ((Get-Date) -lt ${'$'}deadline) {
    try {
      ${'$'}response = Invoke-WebRequest -UseBasicParsing -Uri ${'$'}Url -TimeoutSec 5
      if (${ '$' }response.StatusCode -ge 200 -and ${'$'}response.StatusCode -lt 500) {
        return ${'$'}true
      }
    } catch {}
    Start-Sleep -Seconds 3
  }
  return ${'$'}false
}

${'$'}ModelDir = 'G:/Models/PaddleOCR-VL'
${'$'}LlamaDir = Join-Path ([Environment]::GetFolderPath('Desktop')) 'llama'
${'$'}Port = ${settings.port}
${'$'}Model = [ordered]@{
  Key = '${model.key}'
  FileName = '${model.fileName}'
  MmprojFileName = '${model.mmprojFileName}'
  Ngl = ${model.ngl}
  ContextSize = ${model.contextSize}
  MaxTokens = ${model.maxTokens}
}
${'$'}LlamaZipUrls = $llamaZipUrls
${'$'}CudartZipUrls = $cudartZipUrls
${'$'}CloudflaredUrls = $cloudflaredUrls
${'$'}ModelUrls = $modelUrls
${'$'}MmprojUrls = $mmprojUrls
${'$'}TunnelToken = '${settings.tunnelToken}'
${'$'}CloudflaredPath = Join-Path ${'$'}LlamaDir 'cloudflared.exe'
${'$'}LlamaZipPath = Join-Path ${'$'}LlamaDir 'llama-b9490-bin-win-cuda-13.3-x64.zip'
${'$'}CudartZipPath = Join-Path ${'$'}LlamaDir 'cudart-llama-bin-win-cuda-13.3-x64.zip'
${'$'}ModelPath = Join-Path ${'$'}ModelDir ${'$'}Model.FileName
${'$'}MmprojPath = Join-Path ${'$'}ModelDir ${'$'}Model.MmprojFileName

Ensure-Directory -Path ${'$'}ModelDir
Ensure-Directory -Path ${'$'}LlamaDir

if (-not (Test-Path -LiteralPath ${'$'}CloudflaredPath)) {
  Invoke-Download -Urls ${'$'}CloudflaredUrls -OutputPath ${'$'}CloudflaredPath -Label 'cloudflared'
}

if (-not (Test-Path -LiteralPath ${'$'}CudartZipPath) -or -not (Test-ZipFile -Path ${'$'}CudartZipPath)) {
  Invoke-Download -Urls ${'$'}CudartZipUrls -OutputPath ${'$'}CudartZipPath -Label 'cudart'
}

if (-not (Test-Path -LiteralPath ${'$'}LlamaZipPath) -or -not (Test-ZipFile -Path ${'$'}LlamaZipPath)) {
  Invoke-Download -Urls ${'$'}LlamaZipUrls -OutputPath ${'$'}LlamaZipPath -Label 'llama.cpp'
}

Write-Stage '解压运行库与 llama.cpp'
Expand-Archive -LiteralPath ${'$'}CudartZipPath -DestinationPath ${'$'}LlamaDir -Force
Expand-Archive -LiteralPath ${'$'}LlamaZipPath -DestinationPath ${'$'}LlamaDir -Force

if (-not (Test-Path -LiteralPath ${'$'}ModelPath)) {
  Invoke-Download -Urls ${'$'}ModelUrls -OutputPath ${'$'}ModelPath -Label ${'$'}Model.FileName
}

if (-not (Test-Path -LiteralPath ${'$'}MmprojPath)) {
  Invoke-Download -Urls ${'$'}MmprojUrls -OutputPath ${'$'}MmprojPath -Label ${'$'}Model.MmprojFileName
}

${'$'}Server = Resolve-LlamaServer -Root ${'$'}LlamaDir
if (-not ${'$'}Server) { throw '未找到 llama-server.exe' }

Write-Stage '启动 PaddleOCR-VL OpenAI 兼容服务'
Start-Process -FilePath ${'$'}Server -ArgumentList @(
  '-m', ${'$'}ModelPath,
  '--mmproj', ${'$'}MmprojPath,
  '-ngl', "$(${ '$' }Model.Ngl)",
  '-c', "$(${ '$' }Model.ContextSize)",
  '-n', "$(${ '$' }Model.MaxTokens)",
  '--host', '127.0.0.1',
  '--port', "${'$'}Port",
  '--temp', '0',
  '--jinja'
) -WorkingDirectory (Split-Path -Parent ${'$'}Server)

Write-Stage '等待本地 OpenAI 接口就绪'
if (-not (Wait-HttpReady -Url "http://127.0.0.1:${'$'}Port/v1/models" -TimeoutSeconds 180)) {
  throw 'llama-server 已启动，但 /v1/models 在 180 秒内未就绪'
}

Write-Stage '启动 Cloudflare Tunnel'
${'$'}TunnelTokenPath = Join-Path ${'$'}LlamaDir 'llama-8080.token'
Set-Content -LiteralPath ${'$'}TunnelTokenPath -Value ${'$'}TunnelToken -Encoding ASCII
Start-Process -FilePath ${'$'}CloudflaredPath -ArgumentList @('tunnel','run','--token-file',${'$'}TunnelTokenPath) -WorkingDirectory ${'$'}LlamaDir

Write-Stage '部署完成'
Write-Host "模型: $(${ '$' }Model.FileName)" -ForegroundColor Green
Write-Host "本地 API: http://127.0.0.1:${'$'}Port/v1/models" -ForegroundColor Green
Write-Host "识别接口: /v1/chat/completions" -ForegroundColor Green
Write-Host "完成时间: $(Get-Date -Format 'yyyy-MM-dd HH:mm:ss')" -ForegroundColor Green
        """.trimIndent()
    }
}


