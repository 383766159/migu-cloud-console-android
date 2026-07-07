param(
  [Parameter(Mandatory = $true)][string]$RepoBaseUrl,
  [Parameter(Mandatory = $true)][string]$ModelKey,
  [Parameter(Mandatory = $true)][string]$TunnelToken,
  [Parameter(Mandatory = $true)][string]$TunnelHostname,
  [int]$Port = 8080,
  [string]$CloudflaredUrl = ''
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version 2.0

function Write-Stage([string]$Message) {
  Write-Host ''
  Write-Host "==> $Message" -ForegroundColor Cyan
}

function Ensure-Directory([string]$Path) {
  if (-not (Test-Path -LiteralPath $Path)) {
    New-Item -ItemType Directory -Path $Path -Force | Out-Null
  }
}

function Resolve-UrlList([object]$Value) {
  if ($null -eq $Value) { return @() }
  if ($Value -is [System.Array]) {
    return @($Value | ForEach-Object { "$_".Trim() } | Where-Object { $_ })
  }
  return @("$Value".Trim() | Where-Object { $_ })
}

function Invoke-Download([string[]]$Urls, [string]$OutputPath, [string]$Label) {
  Ensure-Directory -Path (Split-Path -Parent $OutputPath)
  $uniqueUrls = @($Urls | Where-Object { -not [string]::IsNullOrWhiteSpace($_) } | Select-Object -Unique)
  if ($uniqueUrls.Count -eq 0) { throw "未提供可用下载地址: $Label" }

  $tempPath = "$OutputPath.download"
  $lastError = ''
  foreach ($url in $uniqueUrls) {
    Write-Stage "下载 $Label"
    Write-Host "尝试地址: $url" -ForegroundColor DarkGray
    if (Test-Path -LiteralPath $tempPath) {
      $existingSize = (Get-Item -LiteralPath $tempPath).Length
      Write-Host "检测到未完成下载，继续续传: $existingSize bytes" -ForegroundColor DarkGray
    }
    & curl.exe -fL -C - --connect-timeout 10 --retry 5 --retry-all-errors --retry-delay 3 --speed-limit 16384 --speed-time 30 -o $tempPath $url
    if ($LASTEXITCODE -eq 0 -and (Test-Path -LiteralPath $tempPath)) {
      Move-Item -LiteralPath $tempPath -Destination $OutputPath -Force
      break
    }
    $lastError = "地址失败: $url, curl 退出码 $LASTEXITCODE"
    Write-Host $lastError -ForegroundColor Yellow
  }

  if (Test-Path -LiteralPath $tempPath -and Test-Path -LiteralPath $OutputPath) {
    Remove-Item -LiteralPath $tempPath -Force -ErrorAction SilentlyContinue
  }
  if (-not (Test-Path -LiteralPath $OutputPath)) { throw "下载失败: $Label, $lastError" }
}

function Test-ZipFile([string]$Path) {
  try {
    Add-Type -AssemblyName System.IO.Compression.FileSystem -ErrorAction SilentlyContinue
    $zip = [System.IO.Compression.ZipFile]::OpenRead($Path)
    try { [void]$zip.Entries.Count; return $true } finally { $zip.Dispose() }
  } catch { return $false }
}

function Resolve-LlamaServer([string]$Root) {
  $direct = Join-Path $Root 'llama-server.exe'
  if (Test-Path -LiteralPath $direct) { return $direct }
  $found = Get-ChildItem -LiteralPath $Root -Filter 'llama-server.exe' -File -Recurse -ErrorAction SilentlyContinue | Select-Object -First 1
  if ($found) { return $found.FullName }
  return $null
}

function Wait-HttpReady([string]$Url, [int]$TimeoutSeconds = 180) {
  $deadline = (Get-Date).AddSeconds($TimeoutSeconds)
  while ((Get-Date) -lt $deadline) {
    try {
      $response = Invoke-WebRequest -UseBasicParsing -Uri $Url -TimeoutSec 5
      if ($response.StatusCode -ge 200 -and $response.StatusCode -lt 500) {
        return $true
      }
    } catch {}
    Start-Sleep -Seconds 3
  }
  return $false
}

$normalizedBaseUrl = $RepoBaseUrl.Trim().TrimEnd('/')
if ([string]::IsNullOrWhiteSpace($normalizedBaseUrl)) {
  throw 'RepoBaseUrl 不能为空'
}

Write-Stage '读取仓库模型清单'
$manifest = Invoke-RestMethod -UseBasicParsing -Uri "${normalizedBaseUrl}/models.json"
$modelEntry = $manifest.models.PSObject.Properties[$ModelKey]
if (-not $modelEntry) {
  throw "仓库脚本未找到模型配置: $ModelKey"
}

$shared = $manifest.shared
$model = $modelEntry.Value
$llamaDir = Join-Path ([Environment]::GetFolderPath('Desktop')) 'llama'
$modelDir = "$($model.modelDir)"
$modelPath = Join-Path $modelDir "$($model.modelFileName)"
$mmprojPath = if ($model.mmprojFileName) { Join-Path $modelDir "$($model.mmprojFileName)" } else { $null }
$llamaZipUrls = Resolve-UrlList $shared.llamaZipUrls
$cudartZipUrls = Resolve-UrlList $shared.cudartZipUrls
$cloudflaredUrls = @()
if (-not [string]::IsNullOrWhiteSpace($CloudflaredUrl)) {
  $cloudflaredUrls += $CloudflaredUrl.Trim()
}
$cloudflaredUrls += Resolve-UrlList $shared.cloudflaredUrls
$cloudflaredUrls = @($cloudflaredUrls | Select-Object -Unique)
$modelUrls = Resolve-UrlList $model.modelUrls
$mmprojUrls = Resolve-UrlList $model.mmprojUrls
$cloudflaredPath = Join-Path $llamaDir 'cloudflared.exe'
$llamaZipPath = Join-Path $llamaDir 'llama-b9490-bin-win-cuda-13.3-x64.zip'
$cudartZipPath = Join-Path $llamaDir 'cudart-llama-bin-win-cuda-13.3-x64.zip'

Ensure-Directory -Path $modelDir
Ensure-Directory -Path $llamaDir

if (-not (Test-Path -LiteralPath $cloudflaredPath)) {
  Invoke-Download -Urls $cloudflaredUrls -OutputPath $cloudflaredPath -Label 'cloudflared'
}

if (-not (Test-Path -LiteralPath $cudartZipPath) -or -not (Test-ZipFile -Path $cudartZipPath)) {
  Invoke-Download -Urls $cudartZipUrls -OutputPath $cudartZipPath -Label 'cudart'
}

if (-not (Test-Path -LiteralPath $llamaZipPath) -or -not (Test-ZipFile -Path $llamaZipPath)) {
  Invoke-Download -Urls $llamaZipUrls -OutputPath $llamaZipPath -Label 'llama.cpp'
}

Write-Stage '解压运行库与 llama.cpp'
Expand-Archive -LiteralPath $cudartZipPath -DestinationPath $llamaDir -Force
Expand-Archive -LiteralPath $llamaZipPath -DestinationPath $llamaDir -Force

if (-not (Test-Path -LiteralPath $modelPath)) {
  Invoke-Download -Urls $modelUrls -OutputPath $modelPath -Label "$($model.modelFileName)"
}

if ($mmprojPath -and -not (Test-Path -LiteralPath $mmprojPath)) {
  Invoke-Download -Urls $mmprojUrls -OutputPath $mmprojPath -Label "$($model.mmprojFileName)"
}

$server = Resolve-LlamaServer -Root $llamaDir
if (-not $server) { throw '未找到 llama-server.exe' }

Write-Stage '启动模型服务'
$serverArgs = @(
  '-m', $modelPath,
  '-ngl', "$($model.ngl)",
  '-c', "$($model.contextSize)",
  '-n', "$($model.maxTokens)",
  '--host', '127.0.0.1',
  '--port', "$Port"
)

if ($model.family -eq 'paddleocr-vl') {
  $serverArgs += @('--mmproj', $mmprojPath, '--temp', '0', '--jinja')
} elseif ($mmprojPath) {
  $serverArgs += @('-np', '1', '--mmproj', $mmprojPath)
}

Start-Process -FilePath $server -ArgumentList $serverArgs -WorkingDirectory (Split-Path -Parent $server)

Write-Stage '等待本地 OpenAI 接口就绪'
if (-not (Wait-HttpReady -Url "http://127.0.0.1:${Port}/v1/models" -TimeoutSeconds 180)) {
  throw 'llama-server 已启动，但 /v1/models 在 180 秒内未就绪'
}

Write-Stage '启动 Cloudflare Tunnel'
$tunnelTokenPath = Join-Path $llamaDir "llama-${Port}.token"
Set-Content -LiteralPath $tunnelTokenPath -Value $TunnelToken -Encoding ASCII
Start-Process -FilePath $cloudflaredPath -ArgumentList @('tunnel', 'run', '--token-file', $tunnelTokenPath) -WorkingDirectory $llamaDir

Write-Stage '部署完成'
Write-Host "模型: $($model.modelFileName)" -ForegroundColor Green
Write-Host "本地 API: http://127.0.0.1:${Port}/v1/models" -ForegroundColor Green
Write-Host "公网域名: https://$TunnelHostname" -ForegroundColor Green
Write-Host "完成时间: $(Get-Date -Format 'yyyy-MM-dd HH:mm:ss')" -ForegroundColor Green
