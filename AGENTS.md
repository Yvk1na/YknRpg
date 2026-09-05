# YknRpg 项目约定

- 操作系统：Windows。
- Java：JDK 25，固定目录 `C:\java\25`。
- 服务端：Paper 1.21.11 及以上。
- 必需插件：MythicMobs、AuraSkills 2.3.12；CraftEngine 为 GUI 资源软依赖。
- 构建必须使用项目自带 `gradlew.bat`，Gradle 缓存使用 `C:\Users\y\.gradle`。
- 普通验证运行 `deploy.cmd -BuildOnly`，不得因此修改服务端。
- 默认服务端目录为 `F:\mc服务端\Paper-1.21.10-Inf-Skyblock`；未经用户明确要求不得部署、启动或停止服务端。
- CraftEngine 包产物为 `build\distributions\YknRpg-CraftEngine-Pack-<version>.zip`。
- MythicMobs 物品包产物为 `build\distributions\YknRpg-MythicMobs-Pack-<version>.zip`；完整内容固定为 31 个材料、44 把武器和 12 套共 48 件护甲，其中包括 7 枚 YknClass 职业纹章与 14 件职业主题武器。
- 管理员图鉴分为 `admin items`、`admin weapon`、`admin armor`，三者必须互不混入其他分类，并分别按 COMMON、UNCOMMON、RARE、EPIC、LEGENDARY、MYTHIC 排序。
- 玩家可见稀有度名称和颜色固定为 `&8普通`、`&a少见的`、`&9稀有`、`&5史诗`、`&6传说`、`&d神话`。
- `rpg.yml` 是通用 SkyBlock 属性、技能与套装配置入口。能力必须配置驱动，不得为某件具体装备重新加入硬编码物品 ID 或专属 Java 监听器。
- YknRpg 负责 MythicMobs 物品目录、发放、合成方式、战斗属性、技能法力消耗、冷却/充能、通用技能效果和套装效果。
- 技能必须读取并通过 AuraSkills API 扣除魔力；魔力上限、当前值、恢复与持久化均由 AuraSkills 管理，不得重新加入 YknRpg 内部魔力缓存。
- 默认服务端目前仍是 1.21.10，部署脚本必须拒绝向低于 1.21.11 的服务端部署。
- 用户已授权：YknRpg 修改完成并通过构建验证后自动运行 `deploy.cmd`；仍须确认服务端已停止，且不得自动启动或停止服务端。脚本会备份旧插件、Paper 重映射缓存与旧 CraftEngine 包。
- 主指令为 `/yknrpg`；`/yknitems` 与 `yknitems.admin` 只作为旧版兼容入口。`YKI_` 物品 ID 前缀必须保持稳定。
- 保留用户未提交的修改，不使用 `git reset --hard` 或宽范围删除命令。
