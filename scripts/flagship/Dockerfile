# SPDX-License-Identifier: Apache-2.0
#
# JV-24 — the image the flagship demo runs the **generated** application in.
#
# It is deliberately plain. The point of the demo is that the generated artifact is what runs, so nothing
# here tailors the application: it copies the jar the project's own build already produced and starts it.
# `scripts/demo-flagship-generated.sh` copies this file into the generated project before building, so the
# build context is that project and `target/*.jar` is the artifact it just built.
#
# JV-24 —— 旗舰演示用来跑**生成物**的镜像。
#
# 它刻意朴素。演示的要点是**生成物本身**在跑，所以这里不为应用做任何定制：它只是把**项目自己的构建**
# 已经产出的 jar 拷进来并启动。`scripts/demo-flagship-generated.sh` 在构建前把本文件拷进生成物工程，
# 于是构建上下文就是那个工程，而 `target/*.jar` 正是不久前由它构建出来的产物。
FROM eclipse-temurin:17-jre

WORKDIR /app

# The database is a file under `./data`, relative to the working directory (the generated app configures
# it that way). Declaring it a VOLUME is what keeps it out of the container's writable layer; the demo
# asserts the consequence by **restarting the container and looking for the same rows**.
#
# 库是工作目录下 `./data` 里的文件（生成物就是这么配的）。把它声明成 VOLUME，正是为了让它**不在容器的
# 可写层里**；演示用**重启容器再找同样的行**来断言这个后果。
COPY target/*.jar /app/app.jar

EXPOSE 8080
VOLUME ["/app/data"]
ENTRYPOINT ["java", "-jar", "/app/app.jar", "--server.port=8080"]
