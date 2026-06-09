# IDEA 导入说明

如果 IDEA 报：`maven-default-http-blocker`、`Non-resolvable parent POM`、`0.0.0.0`，这是 Maven 使用了 HTTP 仓库镜像导致的依赖解析问题，不是 Java 代码错误。

本项目已经加入项目级 Maven 配置：

- `.mvn/settings.xml`：强制使用 HTTPS Maven 仓库。
- `.mvn/maven.config`：自动使用该 settings，并开启 `-U` 强制更新。

## 推荐操作

1. 在 IDEA 右侧 Maven 面板点击 Reload All Maven Projects。
2. 如果仍然爆红，进入：
   `Settings / Preferences -> Build, Execution, Deployment -> Build Tools -> Maven`
3. 将 User settings file 指向本项目：
   `outputs/毕设闭环原型/backend-java/.mvn/settings.xml`
4. 勾选/确认 Maven importer 使用 JDK 17。
5. 重新 Reload Maven。

## 命令行验证

```bash
cd outputs/毕设闭环原型/backend-java
mvn -DskipTests clean compile
mvn -DskipTests package
```

## 启动

```bash
cd outputs/毕设闭环原型/backend-java
export DEEPSEEK_API_KEY="你的 key"
export DEEPSEEK_MODEL="deepseek-v4-flash"
PORT=5199 java -jar target/robot-taskgraph-demo-0.1.0.jar
```

或在 IDEA 中运行：

`com.example.robotdemo.RobotDemoApplication`

需要在 Run Configuration 的 Environment variables 中设置：

```text
DEEPSEEK_API_KEY=你的 key;DEEPSEEK_MODEL=deepseek-v4-flash;PORT=5199
```
