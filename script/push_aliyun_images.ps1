[CmdletBinding(SupportsShouldProcess = $true, ConfirmImpact = "Medium")]
param(
    [string]$Version = "",
    [string]$Registry = "crpi-0ajp4qol6rvhbqjh.cn-shanghai.personal.cr.aliyuncs.com",
    [string]$Namespace = "coco_bike",
    [string]$Repository = "simple_sq_music_plus_main",
    [string]$Username = $env:ALIYUN_DOCKER_USERNAME,
    [string[]]$Platforms = @("linux/amd64", "linux/arm64"),
    [string]$Builder = "sqmusic-release",
    [string]$MavenImage = "docker.m.daocloud.io/library/maven:3.9.9-eclipse-temurin-17",
    [string]$RuntimeImage = "docker.m.daocloud.io/library/amazoncorretto:17-alpine",
    [switch]$NoLatest,
    [switch]$NoCache,
    [switch]$SkipLogin
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

$projectRoot = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
$dockerfile = Join-Path $projectRoot "Dockerfile"
$applicationConfig = Join-Path $projectRoot "src\main\resources\application.yml"

function Invoke-Docker {
    param(
        [Parameter(Mandatory = $true)]
        [string[]]$DockerArguments,
        [Parameter(Mandatory = $true)]
        [string]$FailureMessage
    )

    & docker @DockerArguments
    if ($LASTEXITCODE -ne 0) {
        throw "$FailureMessage (docker exit code: $LASTEXITCODE)"
    }
}

function Get-ApplicationVersion {
    param([string]$ConfigPath)

    $content = Get-Content -Raw -LiteralPath $ConfigPath
    $match = [regex]::Match($content, '(?m)^\s*version:\s*["'']?([^\s#"'']+)')
    if (-not $match.Success) {
        throw "无法从 $ConfigPath 读取 version。"
    }

    return $match.Groups[1].Value
}

function Connect-AliyunRegistry {
    param(
        [string]$RegistryHost,
        [string]$RegistryUsername
    )

    if ([string]::IsNullOrWhiteSpace($RegistryUsername)) {
        $RegistryUsername = Read-Host "请输入阿里云镜像仓库用户名"
    }
    if ([string]::IsNullOrWhiteSpace($RegistryUsername)) {
        throw "未提供阿里云镜像仓库用户名。可设置 ALIYUN_DOCKER_USERNAME。"
    }

    $plainPassword = $env:ALIYUN_DOCKER_PASSWORD
    if ([string]::IsNullOrWhiteSpace($plainPassword)) {
        $securePassword = Read-Host "请输入阿里云镜像仓库密码" -AsSecureString
        $passwordPointer = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($securePassword)
        try {
            $plainPassword = [Runtime.InteropServices.Marshal]::PtrToStringBSTR($passwordPointer)
        }
        finally {
            [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($passwordPointer)
        }
    }

    if ([string]::IsNullOrWhiteSpace($plainPassword)) {
        throw "未提供阿里云镜像仓库密码。可设置 ALIYUN_DOCKER_PASSWORD。"
    }

    try {
        $plainPassword | & docker login $RegistryHost --username $RegistryUsername --password-stdin
        if ($LASTEXITCODE -ne 0) {
            throw "登录阿里云镜像仓库失败 (docker exit code: $LASTEXITCODE)"
        }
    }
    finally {
        $plainPassword = $null
    }
}

function Initialize-BuildxBuilder {
    param([string]$BuilderName)

    $null = & docker buildx inspect $BuilderName 2>$null
    if ($LASTEXITCODE -ne 0) {
        Write-Host "创建多架构 builder: $BuilderName"
        Invoke-Docker -DockerArguments @(
            "buildx", "create",
            "--name", $BuilderName,
            "--driver", "docker-container",
            "--use"
        ) -FailureMessage "创建 buildx builder 失败"
    }
    else {
        Invoke-Docker -DockerArguments @(
            "buildx", "use", $BuilderName
        ) -FailureMessage "切换 buildx builder 失败"
    }

    Invoke-Docker -DockerArguments @(
        "buildx", "inspect", "--bootstrap", $BuilderName
    ) -FailureMessage "初始化 buildx builder 失败"
}

if (-not (Get-Command docker -ErrorAction SilentlyContinue)) {
    throw "未找到 docker 命令，请先安装并启动 Docker Desktop。"
}
if (-not (Test-Path -LiteralPath $dockerfile)) {
    throw "未找到 Dockerfile: $dockerfile"
}
if (-not (Test-Path -LiteralPath $applicationConfig)) {
    throw "未找到应用配置: $applicationConfig"
}
if ($Platforms.Count -eq 0) {
    throw "至少需要指定一个目标平台。"
}

if ([string]::IsNullOrWhiteSpace($Version)) {
    $Version = Get-ApplicationVersion -ConfigPath $applicationConfig
}
$Version = $Version.Trim() -replace '^[vV]', ''
if ($Version -notmatch '^[0-9A-Za-z][0-9A-Za-z_.-]{0,127}$') {
    throw "版本号不能作为 Docker 标签使用: $Version"
}

$image = "$Registry/$Namespace/$Repository"
$tags = @("${image}:v$Version")
if (-not $NoLatest) {
    $tags += "${image}:latest"
}

Write-Host "发布版本: $Version"
Write-Host "目标平台: $($Platforms -join ', ')"
Write-Host "目标标签:"
$tags | ForEach-Object { Write-Host "  $_" }

if (-not $PSCmdlet.ShouldProcess($image, "构建并推送多架构镜像")) {
    return
}

if (-not $SkipLogin) {
    Connect-AliyunRegistry -RegistryHost $Registry -RegistryUsername $Username
}

Initialize-BuildxBuilder -BuilderName $Builder

$buildArguments = @(
    "buildx", "build",
    "--builder", $Builder,
    "--platform", ($Platforms -join ","),
    "--file", $dockerfile,
    "--pull",
    "--provenance=false",
    "--build-arg", "MAVEN_IMAGE=$MavenImage",
    "--build-arg", "RUNTIME_IMAGE=$RuntimeImage",
    "--label", "org.opencontainers.image.title=Simple Music Server",
    "--label", "org.opencontainers.image.version=v$Version",
    "--push"
)
foreach ($tag in $tags) {
    $buildArguments += @("--tag", $tag)
}
if ($NoCache) {
    $buildArguments += "--no-cache"
}
$buildArguments += $projectRoot

Invoke-Docker -DockerArguments $buildArguments -FailureMessage "构建或推送镜像失败"

Write-Host "推送完成:"
$tags | ForEach-Object { Write-Host "  $_" }
