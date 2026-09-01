# YknItems 项目约定

- 操作系统：Windows。
- Java：JDK 25，固定目录 `C:\java\25`。
- 服务端：Paper 1.21.10 及以上。
- 构建必须使用项目自带 `gradlew.bat`，Gradle 缓存使用 `C:\Users\y\.gradle`。
- 普通验证运行 `deploy.cmd -BuildOnly`，不得因此修改服务端。
- 默认服务端目录为 `F:\mc服务端\Paper-1.21.10-Inf-Skyblock`；未经用户明确要求不得部署、启动或停止服务端。
- CraftEngine 包产物为 `build\distributions\YknItems-CraftEngine-Pack-<version>.zip`。
- 用户明确要求部署且服务端已停止时运行 `deploy.cmd`；脚本会备份旧插件、Paper 重映射缓存与旧 CraftEngine 包。
- 保留用户未提交的修改，不使用 `git reset --hard` 或宽范围删除命令。
