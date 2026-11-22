# YknRpg

适用于 Paper 1.21.11+、JDK 25、MythicMobs、AuraSkills 与 EcoPets 的配置驱动 SkyBlock 风格内容包。完整内容包含 90 个递进锻造材料、105 把武器、33 套共 132 件护甲（合计 327 件 MythicMobs 物品），以及 72 只 EcoPets 宠物；其中包括 7 枚 YknClass 职业纹章、14 件原有职业主题武器与 12 把具有正常战斗属性、技能偏探索、移动、支援和采集的功能性武器。物品本体由 MythicMobs 定义，数值、技能、套装效果、宠物目录与合成方式由 YknRpg 管理。

材料在普通、少见、稀有、史诗、传说、神话六档分别配置 12、12、12、18、18、18 件；武器按现有设计分别为 13、14、15、22、21、20 把，无需在六档之间平均分配；护甲分别为 20、20、20、24、24、24 件，四个部位各 33 件。武器覆盖剑、弓、弩、长枪、三叉戟、战锤、镰刀、短刃、双刃、盾牌、枪械、法杖、法典、法器、号角与战旗；其中 12 把是正常参与战斗、但技能侧重非伤害功能的武器。每种材料都必须被至少一个位于其后的默认配方引用。

骨龙、烬日冥卫·阿努赫与终焉之魔女·伊芙蕾雅分别拥有 6 种主题材料、覆盖剑士、弓兵、枪兵、刺客、奶妈、坦克和法师的 7 把主题武器，以及 1 套四件主题护甲。

## 保留功能

- `/yknrpg admin items` 仅显示锻造材料和道具。
- `/yknrpg admin weapon` 仅显示武器。
- `/yknrpg admin armor` 仅显示护甲；`/yknrpg admin` 默认打开物品图鉴。
- `/yknrpg admin pets` 动态显示 EcoPets 当前注册的全部宠物蛋。
- `/yknrpg admin fishing` 打开含鱼获、渔具、钓鱼装备三个按钮的管理菜单，分别同步 YknFish 的钓鱼与海怪掉落自定义物品、钓竿和钓鱼装备。
- `/yknrpg admin mining` 打开含物品、矿镐、装备三个按钮的管理菜单，分别同步已安装的 YknMining 自定义物品、矿镐和采矿装备。
- `/yknrpg edit` 打开管理员单件属性编辑器：上方放入 1 件物品，点击十种属性按钮后通过虚拟告示牌第一行输入绝对数值；其余格为淡灰色玻璃。支持 0–1000000 的小数，暴击率、暴击伤害、攻击速度、技能伤害、治疗增幅自动显示 `%`，留空取消。关闭、离线或输入超时会归还物品（背包满时掉落脚下）。
- 编辑数值写入该物品自身的持久数据，并参与持有/穿戴时的战斗与面板结算，不改变 MythicMobs 模板、默认配方或其他同名物品。普通物品也可编辑；无模板的护甲在对应部位生效，其他物品在主手生效。虚拟告示牌使用 Paper 原生接口，不改真实世界方块。
- 各类图鉴独立展示，共享分页、左键领取、Shift+左键领取一组及右键配方编辑；钓鱼、采矿图鉴可返回各自的管理菜单。
- 自动发现 `Group: YknRpg` 且使用稳定兼容前缀 `YKI_` 的 MythicMobs 物品。
- 制作材料会阻止原版放置、投掷、种植、熔炼、酿造、堆肥与猪灵交易等误用；自动合成器只允许已注册的 YknRpg 配方消耗它们。
- 图鉴左键领取 1 个，Shift+左键领取一组。
- 右键进入 3×3 配方编辑器，可保存、清空或恢复默认配方；宠物蛋、YknFish 与 YknMining 物品也能作为精确配方材料或结果。
- YknFish 原生配方在未修改时保留无序合成；在 GUI 保存后改为所编辑的有序配方，恢复默认时还原原生无序配方。
- YknMining 原生配方在未修改时保留原有合成方式；在 GUI 保存后使用编辑的有序配方，恢复默认时还原原生配方。
- 保存后增量更新工作台配方，不重复注册未变化的配方。
- CraftEngine 资源包提供配方 GUI 的工作台、箭头，以及完整的物品动画材质。
- `rpg.yml` 按 MythicMobs 物品 ID 配置持有/穿戴属性、技能触发器、效果链、法力、冷却与充能。
- 内置近战伤害、暴击、攻速、生命、防御、AuraSkills 智慧、治疗增幅与移动速度计算；已移除全部武器、护甲与套装的活力和凶猛及其技能增益，旧物品装备时自动清理对应属性说明。装备 Lore 中的生命默认按 1:1 增加最终实际最大生命，力量、生命、智慧、防御、暴击和速度会同步到 AuraSkills 面板，并通过结算隔离避免与 YknRpg 战斗公式重复生效。
- 所有技能直接读取并扣除 AuraSkills 的当前魔力值；魔力上限、持久化与恢复完全由 AuraSkills 管理，YknRpg 不再维护第二套魔力。
- 技能可由右键、潜行右键、近战命中、弹射物命中、击杀、受伤或周期触发，并组合伤害、治疗、药水、位移、弹射物、击退、闪电、粒子、音效、吸收、净化和临时属性效果。
- `rpg.yml` 的 `sets` 可定义穿戴件数门槛、套装属性和套装技能；只统计实际穿戴的护甲部件。
- `/yknrpg reload` 重新读取目录与配方。
- 72 只宠物分为普通、少见、稀有、史诗、传说、神话六档，每档 12 只；均可升至 100 级，并按采集、战斗、钓鱼、附魔、酿造或探索等对应行为获得经验。
- 宠物覆盖战斗、采矿、伐木、耕作、钓鱼、炼药、附魔、探索与通用辅助用途；效果、倍率、概率、目标过滤和资源增产白名单全部由 EcoPets 配置驱动。
- 宠物属性通过 libreforge 的 `add_stat` 接入 AuraSkills，不维护第二套玩家属性；构建会严格校验六档预算、效果参数、描述占位符、纹理、宠物蛋、经验与自然资源增产边界。

管理员保存的覆盖配方位于 `plugins/YknRpg/recipes.yml`，RPG 定义位于 `plugins/YknRpg/rpg.yml`。提升内置内容版本时，插件会先把旧配置备份到 `plugins/YknRpg/backups`，再应用新版目录，避免已废弃内容残留。

从旧版升级时，部署器会把整个 `plugins/YknItems` 数据目录迁移为 `plugins/YknRpg`，并清理旧 JAR、Paper 重映射缓存、CraftEngine 包和 MythicMobs 文件；同时会把 Skyblock-Core Tab 的旧生命占位符迁移为读取最终最大生命的 `%auraskills_hp_max%`，原配置随发布备份。`/yknitems` 与 `yknitems.admin` 仅作为兼容别名保留；新配置和文档统一使用 `/yknrpg` 与 `yknrpg.admin`。

## 内容分工

锻造边界新增 `ForgeItemAccess.forgeable/item`，仅识别本体 `YKI_` 武器与 132 件 `CatalogKind.ARMOR` 护甲，返回基础名、稀有度、WEAPON/ARMOR 和未加成属性；`kind` 用于轻量查询，旧 `isWeapon/weapon` 签名与行为不变。碎裂护甲在 Lore 刷新后跳过属性、技能与套装计数；修复后按现有穿戴位置恢复。未锻造护甲不改名称与 Lore。测试遍历完整目录，验证新旧 API、护甲固定值与倍率、套装件数失效/恢复及外观保留。

YknRpg 1.2.3 的锻造扩展由 Bukkit 服务 `ForgeBonusProvider` 提供。`flat(ItemStack, statId)` 默认为 0，属性按「基础值 × multiplier + flat」计算，支持为原本没有该属性的装备追加固定值；`decorateName(ItemStack, baseName)` 默认返回原名，保持旧实现兼容。显示刷新入口为 `ForgeSupport.rewrite`，首次装饰时保存完整基础名组件，后续始终以基础名调用，`ForgeItemAccess.item/weapon` 也返回基础名，防止面板重复后缀。服务移除时恢复基础名；未锻造物品的名称与 Lore 完全不写回。自动测试覆盖固定值顺序、缺失属性、名称/Lore 幂等及未锻造外观保留。

- MythicMobs：物品材质、名称、Lore 和基础物品数据。
- AuraSkills：玩家当前魔力、魔力上限、恢复和持久化。
- EcoPets、eco 与 libreforge：宠物解锁、升级、展示、特殊效果，以及 AuraSkills 宠物属性修饰器。
- YknFish（可选）：鱼获、钓竿与钓鱼装备的物品定义；启动、YknFish 启停与 `/yknrpg reload` 时同步目录，菜单浏览读取已加载目录，不触发配方重载；未就绪时提示重试。
- YknMining（可选）：采矿物品、矿镐与采矿装备的物品定义；等采矿岛初始化完成后同步目录和原生配方，插件启停与 `/yknrpg reload` 时更新，菜单浏览读取已加载目录，不触发配方重载；未就绪时提示重试。未给 YknRpg 添加 YknMining 软依赖，避免与 Skyblock-Core 的加载顺序形成循环。
- `equipment.yml`：管理员图鉴顺序与默认合成表。
- `rpg.yml`：属性、技能、消耗、冷却、充能和套装规则。
- `recipes.yml`：管理员在游戏内保存的合成表覆盖项。

所有能力均由通用执行器解释配置。327 件物品包含 90 个材料、105 把武器和 132 件护甲。每件沿用同一个 `ItemModel`，背包、图鉴和快捷栏显示 32×32 图标，第一、第三人称的左手与右手显示 64×64 手持纹理。两种纹理共用该物品的 16 帧时间线及原生 `.mcmeta` 插值。护甲穿戴沿用独立静态贴图。玩家加载 CraftEngine 合并后的资源包即可看到新外观。高稀有度配方依赖低阶压缩材料，形成逐级升级链。

327 件新版像素材质均已纳入构建。动画预览可以切换背包和手持视图，格子预览按图鉴分类和稀有度排列。构建检查模型引用、两种分辨率、帧时间、轮廓以及原画和特效图层的 SHA-256。[动画预览](docs/design/catalog-32-v2/animated-preview.html) · [全目录格子](docs/design/catalog-32-v2/previews/catalog-slots.png) · [审核清单](docs/design/catalog-32-v2/progress.json)

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

构建脚本固定使用 `C:\java\25` 与 `C:\Users\y\.gradle`。YknRpg 将 MythicMobs 与 AuraSkills 声明为必需依赖。完整部署还要求服务端安装 EcoPets、eco 与 libreforge，并以备份、替换和逐文件 SHA-256 校验的方式把 72 份宠物配置安装到 `plugins/EcoPets/pets/yknrpg`；部署器会拒绝部署到低于 Minecraft 1.21.11 或仍在运行的服务端。
