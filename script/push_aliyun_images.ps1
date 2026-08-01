[CmdletBinding(SupportsShouldProcess = $true, ConfirmImpact = "Medium")]
param(
    [string]$Version = "",
    [string]$FrontendVersion = "",
    [string]$FrontendContext = "",
    [string]$Registry = "crpi-0ajp4qol6rvhbqjh.cn-shanghai.personal.cr.aliyuncs.com",
    [string]$Namespace = "coco_bike",
    [string]$BackendRepository = "simple_sq_music_plus_main",
    [string]$FrontendRepository = "simple_sq_music_plus_web",
    [string]$Username = $env:ALIYUN_DOCKER_USERNAME,
    [string[]]$Platforms = @("linux/amd64", "linux/arm64"),
    [string]$Builder = "sqmusic-release",
    [string]$MavenImage = "docker.m.daocloud.io/library/maven:3.9.9-eclipse-temurin-17",
    [string]$RuntimeImage = "docker.m.daocloud.io/library/amazoncorretto:17-alpine",
    [string]$NodeImage = "docker.m.daocloud.io/library/node:22-alpine",
    [string]$NginxImage = "docker.m.daocloud.io/library/nginx:1.27-alpine",
    [switch]$NoLatest,
    [switch]$NoCache,
    [switch]$SkipLogin,
    [switch]$SkipBackend,
    [switch]$SkipFrontend
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

$projectRoot = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
$backendDockerfile = Join-Path $projectRoot "Dockerfile"
$applicationConfig = Join-Path $projectRoot "src\main\resources\application.yml"
if ([string]::IsNullOrWhiteSpace($FrontendContext)) {
    $FrontendContext = Join-Path (Split-Path $projectRoot -Parent) "simple_sq_music_plus_web\vue"
}

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

function Get-FrontendVersion {
    param([string]$PackageJsonPath)

    $package = Get-Content -Raw -LiteralPath $PackageJsonPath | ConvertFrom-Json
    if ([string]::IsNullOrWhiteSpace($package.version)) {
        throw "无法从 $PackageJsonPath 读取 version。"
    }

    return $package.version
}

function Normalize-DockerTagVersion {
    param(
        [string]$Value,
        [string]$DisplayName
    )

    $normalized = $Value.Trim() -replace '^[vV]', ''
    if ($normalized -notmatch '^[0-9A-Za-z][0-9A-Za-z_.-]{0,127}$') {
        throw "$DisplayName 不能作为 Docker 标签使用: $Value"
    }

    return $normalized
}

function Get-ImageTags {
    param(
        [string]$Image,
        [string]$ImageVersion,
        [bool]$IncludeLatest
    )

    $result = @("${Image}:v$ImageVersion")
    if ($IncludeLatest) {
        $result += "${Image}:latest"
    }

    return $result
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

function Publish-MultiArchImage {
    param(
        [string]$ContextPath,
        [string]$DockerfilePath,
        [string]$ImageTitle,
        [string]$ImageVersion,
        [string[]]$Tags,
        [hashtable]$BuildArgs
    )

    $buildArguments = @(
        "buildx", "build",
        "--builder", $Builder,
        "--platform", ($Platforms -join ","),
        "--file", $DockerfilePath,
        "--pull",
        "--provenance=false",
        "--label", "org.opencontainers.image.title=$ImageTitle",
        "--label", "org.opencontainers.image.version=v$ImageVersion",
        "--push"
    )

    foreach ($name in $BuildArgs.Keys) {
        $buildArguments += @("--build-arg", "$name=$($BuildArgs[$name])")
    }
    foreach ($tag in $Tags) {
        $buildArguments += @("--tag", $tag)
    }
    if ($NoCache) {
        $buildArguments += "--no-cache"
    }
    $buildArguments += $ContextPath

    Invoke-Docker -DockerArguments $buildArguments -FailureMessage "构建或推送 $ImageTitle 失败"
}

if (-not (Get-Command docker -ErrorAction SilentlyContinue)) {
    throw "未找到 docker 命令，请先安装并启动 Docker Desktop。"
}
if ($SkipBackend -and $SkipFrontend) {
    throw "不能同时指定 SkipBackend 和 SkipFrontend。"
}
if ($Platforms.Count -eq 0) {
    throw "至少需要指定一个目标平台。"
}

$backendTags = @()
$frontendTags = @()
$releaseImages = @()

if (-not $SkipBackend) {
    if (-not (Test-Path -LiteralPath $backendDockerfile)) {
        throw "未找到后端 Dockerfile: $backendDockerfile"
    }
    if (-not (Test-Path -LiteralPath $applicationConfig)) {
        throw "未找到应用配置: $applicationConfig"
    }
    if ([string]::IsNullOrWhiteSpace($Version)) {
        $Version = Get-ApplicationVersion -ConfigPath $applicationConfig
    }
    $Version = Normalize-DockerTagVersion -Value $Version -DisplayName "后端版本号"
    $backendImage = "$Registry/$Namespace/$BackendRepository"
    $backendTags = Get-ImageTags -Image $backendImage -ImageVersion $Version -IncludeLatest (-not $NoLatest)
    $releaseImages += $backendImage
}

if (-not $SkipFrontend) {
    if (-not (Test-Path -LiteralPath $FrontendContext)) {
        throw "未找到前端目录: $FrontendContext"
    }
    $FrontendContext = (Resolve-Path -LiteralPath $FrontendContext).Path
    $frontendDockerfile = Join-Path $FrontendContext "Dockerfile"
    $frontendPackageJson = Join-Path $FrontendContext "package.json"
    if (-not (Test-Path -LiteralPath $frontendDockerfile)) {
        throw "未找到前端 Dockerfile: $frontendDockerfile"
    }
    if (-not (Test-Path -LiteralPath $frontendPackageJson)) {
        throw "未找到前端 package.json: $frontendPackageJson"
    }
    if ([string]::IsNullOrWhiteSpace($FrontendVersion)) {
        $FrontendVersion = Get-FrontendVersion -PackageJsonPath $frontendPackageJson
    }
    $FrontendVersion = Normalize-DockerTagVersion -Value $FrontendVersion -DisplayName "前端版本号"
    $frontendImage = "$Registry/$Namespace/$FrontendRepository"
    $frontendTags = Get-ImageTags -Image $frontendImage -ImageVersion $FrontendVersion -IncludeLatest (-not $NoLatest)
    $releaseImages += $frontendImage
}

Write-Host "目标平台: $($Platforms -join ', ')"
if (-not $SkipBackend) {
    Write-Host "后端版本: $Version"
    $backendTags | ForEach-Object { Write-Host "  $_" }
}
if (-not $SkipFrontend) {
    Write-Host "前端版本: $FrontendVersion"
    $frontendTags | ForEach-Object { Write-Host "  $_" }
}

if (-not $PSCmdlet.ShouldProcess(($releaseImages -join ", "), "构建并推送多架构镜像")) {
    return
}

if (-not $SkipLogin) {
    Connect-AliyunRegistry -RegistryHost $Registry -RegistryUsername $Username
}

Initialize-BuildxBuilder -BuilderName $Builder

if (-not $SkipBackend) {
    Write-Host "开始发布后端镜像..."
    Publish-MultiArchImage `
        -ContextPath $projectRoot `
        -DockerfilePath $backendDockerfile `
        -ImageTitle "Simple Music Server" `
        -ImageVersion $Version `
        -Tags $backendTags `
        -BuildArgs @{
            MAVEN_IMAGE = $MavenImage
            RUNTIME_IMAGE = $RuntimeImage
        }
}

if (-not $SkipFrontend) {
    Write-Host "开始发布前端镜像..."
    Publish-MultiArchImage `
        -ContextPath $FrontendContext `
        -DockerfilePath $frontendDockerfile `
        -ImageTitle "Simple Music Web" `
        -ImageVersion $FrontendVersion `
        -Tags $frontendTags `
        -BuildArgs @{
            NODE_IMAGE = $NodeImage
            NGINX_IMAGE = $NginxImage
        }
}

Write-Host "全部推送完成:"
@($backendTags) + @($frontendTags) | ForEach-Object { Write-Host "  $_" }
