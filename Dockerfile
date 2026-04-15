FROM maven:3.9.9-eclipse-temurin-17 AS builder
LABEL maintainer="SQ"

WORKDIR /build/

# 配置阿里云 Maven 镜像，加速国内依赖下载
COPY settings.xml /root/.m2/settings.xml

# 单独复制 pom.xml，先下载依赖（利用 Docker 层缓存）
COPY pom.xml /build/
RUN mvn dependency:go-offline -B

COPY src /build/src/
RUN mvn clean package -B

# 使用更稳定、体积更小的 JRE 运行时镜像
FROM amazoncorretto:17-alpine

# 设置工作目录
WORKDIR /app

# 从构建阶段复制JAR文件
COPY --from=builder /build/target/*.jar /app/app.jar

# 显示架构信息（便于调试）
RUN echo "Running on architecture: $(uname -m)"


# 暴露端口
EXPOSE 8099

# 挂载音乐目录
VOLUME ["/music"]

# 启动应用
CMD ["sh", "-c", "java -jar app.jar"]
