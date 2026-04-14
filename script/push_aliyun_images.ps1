param(
    [string]$Version = "",
    [string]$FrontendVersion = "",
    [string]$Registry = "crpi-0ajp4qol6rvhbqjh.cn-shanghai.personal.cr.aliyuncs.com",
    [string]$Namespace = "coco_bike",
    [string]$BackendRepository = "simple_sq_music_plus_main",
    [string]$FrontendRepository = "simple_sq_music_plus_web",
    [string]$Username = "444503829@qq.com",
    [string]$BackendImage = "sqmusic_main:local",
    [string]$FrontendImage = "sqmusic_web:local",
    [switch]$UseVpc
)

$ErrorActionPreference = "Stop"

$WebBuildContext = "G:\Projects\simple_sq_music_plus_web\vue"

# 若未指定后端版本，自动从 pom.xml 读取
if ([string]::IsNullOrWhiteSpace($Version)) {
    [xml]$pom = Get-Content (Join-Path $PSScriptRoot "..\pom.xml")
    $Version = $pom.project.version
    Write-Host "从 pom.xml 读取后端版本: $Version"
}

# 若未指定前端版本，自动从 package.json 读取
if ([string]::IsNullOrWhiteSpace($FrontendVersion)) {
    $packageJson = Get-Content (Join-Path $WebBuildContext "package.json") -Raw | ConvertFrom-Json
    $FrontendVersion = $packageJson.version
    Write-Host "从 package.json 读取前端版本: $FrontendVersion"
}

function Require-Image {
    param(
        [string]$ImageName
    )

    $imageId = docker image inspect $ImageName --format "{{.Id}}" 2>$null
    if (-not $imageId) {
        throw "未找到本地镜像: $ImageName"
    }
}

function Login-Registry {
    param(
        [string]$RegistryHost,
        [string]$RegistryUsername
    )

    $plainPassword = $env:ALIYUN_DOCKER_PASSWORD
    if ([string]::IsNullOrWhiteSpace($plainPassword)) {
        $securePassword = Read-Host "请输入阿里云镜像仓库密码" -AsSecureString
        $bstr = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($securePassword)
        try {
            $plainPassword = [Runtime.InteropServices.Marshal]::PtrToStringBSTR($bstr)
        }
        finally {
            [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($bstr)
        }
    }

    if ([string]::IsNullOrWhiteSpace($plainPassword)) {
        throw "未提供阿里云镜像仓库密码。可设置环境变量 ALIYUN_DOCKER_PASSWORD，或在运行脚本时手动输入。"
    }

    $plainPassword | docker login --username=$RegistryUsername --password-stdin $RegistryHost
}

function Push-ImageWithTags {
    param(
        [string]$SourceImage,
        [string]$TargetRepository,
        [string[]]$Tags
    )

    foreach ($tag in $Tags) {
        $targetImage = "${registryHost}/${Namespace}/${TargetRepository}:${tag}"
        Write-Host "打标镜像: $SourceImage -> $targetImage"
        docker tag $SourceImage $targetImage

        Write-Host "推送镜像: $targetImage"
        docker push $targetImage
    }
}

$registryHost = $Registry
if ($UseVpc) {
    $registryHost = $registryHost -replace "\.cn-shanghai\.personal\.cr\.aliyuncs\.com$", "-vpc.cn-shanghai.personal.cr.aliyuncs.com"
}

$publishTags = @($Version, "latest")

Write-Host "构建后端镜像: $BackendImage"
docker build -t $BackendImage -f (Join-Path $PSScriptRoot "..\Dockerfile") (Join-Path $PSScriptRoot "..")
if ($LASTEXITCODE -ne 0) { throw "后端镜像构建失败" }

Write-Host "构建前端镜像: $FrontendImage (来源: $WebBuildContext)"
docker build -t $FrontendImage $WebBuildContext
if ($LASTEXITCODE -ne 0) { throw "前端镜像构建失败" }

Write-Host "检查本地镜像..."
Require-Image -ImageName $BackendImage
Require-Image -ImageName $FrontendImage

Write-Host "登录阿里云镜像仓库: $registryHost"
Login-Registry -RegistryHost $registryHost -RegistryUsername $Username

Write-Host "推送后端镜像..."
Push-ImageWithTags -SourceImage $BackendImage -TargetRepository $BackendRepository -Tags $publishTags

Write-Host "推送前端镜像..."
$frontendPublishTags = @($FrontendVersion, "latest")
Push-ImageWithTags -SourceImage $FrontendImage -TargetRepository $FrontendRepository -Tags $frontendPublishTags

Write-Host "推送完成。"
Write-Host "后端镜像: ${registryHost}/${Namespace}/${BackendRepository}:${Version}"
Write-Host "后端镜像: ${registryHost}/${Namespace}/${BackendRepository}:latest"
Write-Host "前端镜像: ${registryHost}/${Namespace}/${FrontendRepository}:${Version}"
Write-Host "前端镜像: ${registryHost}/${Namespace}/${FrontendRepository}:latest"