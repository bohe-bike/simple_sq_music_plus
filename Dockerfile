FROM m.daocloud.io/docker.io/library/maven:3.9.9-eclipse-temurin-17 AS builder
MAINTAINER SQ

WORKDIR /build/

COPY pom.xml /build/
COPY src /build/src/

RUN mvn clean package

# 使用更稳定、体积更小的 JRE 运行时镜像
FROM m.daocloud.io/docker.io/library/eclipse-temurin:17-jre-alpine

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
