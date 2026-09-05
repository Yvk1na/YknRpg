# YknRpg

适用于 Paper 1.21.11+、JDK 25、MythicMobs 与 AuraSkills 的配置驱动 SkyBlock 风格内容包。完整内容包含 31 个递进锻造材料、44 把武器、12 套共 48 件护甲，以及 36 只 EcoPets 宠物；其中包括 7 枚 YknClass 职业纹章与 14 件职业主题武器。物品本体由 MythicMobs 定义，数值、技能、套装效果与合成方式由 YknRpg 管理。

## 保留功能

- `/yknrpg admin items` 仅显示锻造材料和道具。
- `/yknrpg admin weapon` 仅显示武器。
- `/yknrpg admin armor` 仅显示护甲；`/yknrpg admin` 默认打开物品图鉴。
- `/yknrpg admin pets` 动态显示 EcoPets 当前注册的全部宠物蛋。
- 四类图鉴使用相同 GUI；物品、武器、护甲与宠物蛋分别独立展示。
- 自动发现 `Group: YknRpg` 且使用稳定兼容前缀 `YKI_` 的 MythicMobs 物品。
- 制作材料会阻止原版放置、投掷、种植、熔炼、酿造、堆肥与猪灵交易等误用；自动合成器只允许已注册的 YknRpg 配方消耗它们。
- 图鉴左键领取 1 个，Shift+左键领取一组。
- 右键进入 3×3 配方编辑器，可保存、清空或恢复默认配方；宠物蛋也能作为精确配方材料或结果。
- 保存后重新注册原版工作台配方。
- CraftEngine 仅提供配方 GUI 的工作台与箭头界面资源。
- `rpg.yml` 按 MythicMobs 物品 ID 配置持有/穿戴属性、技能触发器、效果链、法力、冷却与充能。
- 内置近战伤害、暴击、攻速、生命、防御、AuraSkills 智慧、凶猛、活力、治疗增幅与移动速度计算；装备 Lore 中的生命默认按 1:1 增加最终实际最大生命，力量、生命、智慧、防御、暴击和速度会同步到 AuraSkills 面板，并通过结算隔离避免与 YknRpg 战斗公式重复生效。
- 所有技能直接读取并扣除 AuraSkills 的当前魔力值；魔力上限、持久化与恢复完全由 AuraSkills 管理，YknRpg 不再维护第二套魔力。
- 技能可由右键、潜行右键、近战命中、弹射物命中、击杀、受伤或周期触发，并组合伤害、治疗、药水、位移、弹射物、击退、闪电、粒子、音效、吸收、净化和临时属性效果。
- `rpg.yml` 的 `sets` 可定义穿戴件数门槛、套装属性和套装技能；只统计实际穿戴的护甲部件。
- `/yknrpg reload` 重新读取目录与配方。
- 36 只宠物分为普通、少见、稀有、史诗、传说、神话六档，每档 6 只；均可升至 100 级，并按采集、战斗、钓鱼、附魔、酿造或探索等对应行为获得经验。
- 宠物只使用 libreforge 的 `add_stat` 向 AuraSkills 提供力量、生命、生命恢复、幸运、智慧、韧性、暴击率、暴击伤害与速度加成，不维护第二套玩家属性。
- 宠物数值按普通/少见、稀有/史诗、传说/神话三个阶段独立控制档位预算，多属性宠物共享对应预算；宠物继续直接使用 AuraSkills 属性单位，不随 YknRpg 装备生命的 1:1 换算调整。

管理员保存的覆盖配方位于 `plugins/YknRpg/recipes.yml`，RPG 定义位于 `plugins/YknRpg/rpg.yml`。提升内置内容版本时，插件会先把旧配置备份到 `plugins/YknRpg/backups`，再应用新版目录，避免已废弃内容残留。

从旧版升级时，部署器会把整个 `plugins/YknItems` 数据目录迁移为 `plugins/YknRpg`，并清理旧 JAR、Paper 重映射缓存、CraftEngine 包和 MythicMobs 文件；同时会把 Skyblock-Core Tab 的旧生命占位符迁移为读取最终最大生命的 `%auraskills_hp_max%`，原配置随发布备份。`/yknitems` 与 `yknitems.admin` 仅作为兼容别名保留；新配置和文档统一使用 `/yknrpg` 与 `yknrpg.admin`。

## 内容分工

- MythicMobs：物品材质、名称、Lore 和基础物品数据。
- AuraSkills：玩家当前魔力、魔力上限、恢复和持久化。
- EcoPets + libreforge：宠物解锁、升级、展示，以及 AuraSkills 宠物属性修饰器。
- `equipment.yml`：管理员图鉴顺序与默认合成表。
- `rpg.yml`：属性、技能、消耗、冷却、充能和套装规则。
- `recipes.yml`：管理员在游戏内保存的合成表覆盖项。

所有能力均由通用执行器解释配置，不需要为每把武器新增 Java 监听器。材料沿用原版物品图标并增加附魔光泽，护甲使用原版染色与盔甲纹饰区分套装；高稀有度配方依赖低阶压缩材料，形成逐级升级链。

## 构建

```powershell
.\deploy.cmd -BuildOnly
```

产物：

- `build/libs/YknRpg-<version>.jar`
- `build/distributions/YknRpg-CraftEngine-Pack-<version>.zip`
- `build/distributions/YknRpg-MythicMobs-Pack-<version>.zip`
- `build/distributions/YknRpg-EcoPets-Pack-<version>.zip`

插件 JAR 会在构建阶段通过 ProGuard 混淆，供排查堆栈使用的映射文件保存在 `build/obfuscation/`，不会打入发布 JAR。

构建脚本固定使用 `C:\java\25` 与 `C:\Users\y\.gradle`。YknRpg 将 MythicMobs 与 AuraSkills 声明为必需依赖。完整部署还要求服务端已安装 EcoPets 与 libreforge，并把宠物包安装到 `plugins/EcoPets/pets/yknrpg`，不会覆盖 EcoPets 自带宠物；部署器会拒绝部署到低于 Minecraft 1.21.11 的服务端。
