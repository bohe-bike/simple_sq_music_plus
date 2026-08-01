
## 推送阿里云多架构镜像

`push_aliyun_images.ps1` 会同时构建后端和前端的 `linux/amd64`、`linux/arm64`
镜像。后端版本从 `src/main/resources/application.yml` 读取，前端版本从相邻的
`simple_sq_music_plus_web/vue/package.json` 读取。

- `crpi-0ajp4qol6rvhbqjh.cn-shanghai.personal.cr.aliyuncs.com/coco_bike/simple_sq_music_plus_main:v<版本>`
- `crpi-0ajp4qol6rvhbqjh.cn-shanghai.personal.cr.aliyuncs.com/coco_bike/simple_sq_music_plus_main:latest`
- `crpi-0ajp4qol6rvhbqjh.cn-shanghai.personal.cr.aliyuncs.com/coco_bike/simple_sq_music_plus_web:v<前端版本>`
- `crpi-0ajp4qol6rvhbqjh.cn-shanghai.personal.cr.aliyuncs.com/coco_bike/simple_sq_music_plus_web:latest`

推荐通过环境变量提供凭据：

```powershell
$env:ALIYUN_DOCKER_USERNAME = "阿里云镜像仓库用户名"
$env:ALIYUN_DOCKER_PASSWORD = "阿里云镜像仓库密码"
.\script\push_aliyun_images.ps1
```

只查看即将推送的版本和标签，不执行构建或推送：

```powershell
.\script\push_aliyun_images.ps1 -WhatIf
```

可用 `-Version 4.0.1`、`-FrontendVersion 4.2.1` 指定版本，使用 `-NoLatest`
跳过 `latest` 标签，或用 `-SkipBackend`、`-SkipFrontend` 单独发布其中一个镜像。
`-FrontendContext` 可覆盖前端目录。

脚本默认通过 DaoCloud 镜像代理拉取 Maven 和 Java 基础镜像。可使用
`-MavenImage`、`-RuntimeImage`、`-NodeImage`、`-NginxImage` 指定其他镜像地址。

## 更新脚本

[check_update.sh](check_update.sh)脚本使用说明


如果用[docker-compose.yml](../docker-compose.yml)执行的则啥都不用改直接运行即可


如果改了配置文件则需要修改docker-compose.yml文件

### 数据库配置
- DB_IP="mysql" 数据库地址可以写服务名称
- DB_PORT="3306" 数据库端口（内部端口）
- DB_NAME="sqmusicv3" 数据库名称
- DB_USERNAME="root" 数据库用户名
- DB_PASSWORD="sqmusicv3password" 数据库密码

### 音乐目录配置
- MUSIC_DIR_HOST="$(pwd)/../music"  映射本地的路径
- MUSIC_DIR_CONTAINER="/music" 容器内部路径

###  容器名称配置
CONTAINER_MYSQL="sqmusic_mysql"  
CONTAINER_WEB="sqmusic_web"
CONTAINER_MAIN="sqmusic_main"

###  全局的网关名称 
NETWORK_NAME="simple_sq_music_plus_sq-app-network"
