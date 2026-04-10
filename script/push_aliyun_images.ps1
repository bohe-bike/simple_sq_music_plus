param(
    [string]$Version = "4.0",
    [string]$Registry = "crpi-0ajp4qol6rvhbqjh.cn-shanghai.personal.cr.aliyuncs.com",
    [string]$Namespace = "coco_bike",
    [string]$BackendRepository = "song-nas",
    [string]$FrontendRepository = "song-nas-web",
    [string]$Username = "444503829@qq.com",
    [string]$BackendImage = "sqmusic_main:local",
    [string]$FrontendImage = "sqmusic_web:local",
    [switch]$UseVpc
)

$ErrorActionPreference = "Stop"

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
        $targetImage = "$registryHost/$Namespace/$TargetRepository:$tag"
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

Write-Host "检查本地镜像..."
Require-Image -ImageName $BackendImage
Require-Image -ImageName $FrontendImage

Write-Host "登录阿里云镜像仓库: $registryHost"
Login-Registry -RegistryHost $registryHost -RegistryUsername $Username

Write-Host "推送后端镜像..."
Push-ImageWithTags -SourceImage $BackendImage -TargetRepository $BackendRepository -Tags $publishTags

Write-Host "推送前端镜像..."
Push-ImageWithTags -SourceImage $FrontendImage -TargetRepository $FrontendRepository -Tags $publishTags

Write-Host "推送完成。"
Write-Host "后端镜像: $registryHost/$Namespace/$BackendRepository:$Version"
Write-Host "后端镜像: $registryHost/$Namespace/$BackendRepository:latest"
Write-Host "前端镜像: $registryHost/$Namespace/$FrontendRepository:$Version"
Write-Host "前端镜像: $registryHost/$Namespace/$FrontendRepository:latest"