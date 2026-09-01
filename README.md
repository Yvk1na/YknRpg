# YknItems

适用于 Paper 1.21.10+、JDK 25 的独立 RPG 装备插件。它不依赖 YknBoss，统一提供自定义材料、武器、护甲、配方、管理员图鉴与战斗能力。

## 已设计内容

- 3 个阶段材料、18 把武器、9 套共 36 件护甲，总计 57 个自定义物品。
- 六类武器：单手剑、巨剑、匕首、弓、长枪、法杖。
- 前/中/后期推荐 AuraSkills 等级分别为 5/20/40；未安装 AuraSkills 时等级与法力限制自动关闭。
- 武器与护甲完全使用原版材质；CraftEngine 仅提供锻造材料与合成 GUI 的工作台/箭头图标。
- EcoEnchants、Reforges、YknClass 均为软兼容，不作为启动必需依赖。

## 指令与 GUI

- `/yknitems admin`：打开管理员图鉴。
- `/yknitems reload`：重载配置并重新注册配方。
- 图鉴左键领取 1 个，Shift+左键领取一组，右键进入管理员配方编辑器。
- 配方编辑器从玩家背包选择材料，在左侧 3×3 格放置幽灵材料；保存后写入 `plugins/YknItems/recipes.yml` 并立即重新注册配方。

## 构建

```powershell
.\deploy.cmd -BuildOnly
```

输出位于 `build\libs` 与 `build\distributions`。部署到已停止的默认服务端使用 `.\deploy.cmd`；旧插件、Paper 重映射缓存与 CraftEngine 包会备份到 `backups\yknitems-deploy`。

构建脚本固定使用 `C:\java\25` 与 `C:\Users\y\.gradle`。
