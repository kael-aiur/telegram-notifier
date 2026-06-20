## Why

前端源码目前独立存放在 `telegram-notifier-control-web` 模块中，但该模块实际上是一个空壳 pom（packaging: pom，无构建逻辑），构建产物直接落入 `control-server` 的 `src/main/resources/static/`。两个模块在物理上已经耦合，Maven 层面却没有依赖关系，导致：

1. 开发者需要在两个模块目录间切换，前端源码和最终部署产物分离在不同模块中。
2. web 模块的 pom 没有实际作用，增加了项目结构的认知成本。
3. CI/构建脚本需要跨模块协调工作目录。

将前端源码迁入 `control-server` 的 `src/main/frontend/` 目录，可以消除这个多余的模块边界，让前后端源码在同一个 Maven 模块中统一管理。

## What Changes

- 将 `telegram-notifier-control-web/src/main/frontend/` 下的全部源码（package.json、vite.config.js、index.html、src/）迁移到 `telegram-notifier-control-server/src/main/frontend/`。
- 调整 `vite.config.js` 中的 `build.outDir`，使其输出到 `../resources/static`（相对路径）。
- 从父 `pom.xml` 的 `<modules>` 中移除 `telegram-notifier-control-web`。
- 删除 `telegram-notifier-control-web` 模块目录。
- 更新 `.gitignore` 确保 `node_modules/` 规则覆盖新路径。
- 更新 AGENTS.md 中关于 Vue 源码路径的描述。

**BREAKING**: `telegram-notifier-control-web` Maven 模块将被移除。如果有外部脚本引用该模块路径，需要同步更新。

## Capabilities

### New Capabilities

无新增能力。本次变更是纯结构调整。

### Modified Capabilities

- `platform-modules`: 模块布局从三模块（starter、control-server、control-web）变为两模块（starter、control-server），前端源码作为 control-server 的一部分管理。
- `control-console`: Vue 源码路径从 `telegram-notifier-control-web/src/main/frontend/` 变更为 `telegram-notifier-control-server/src/main/frontend/`。

## Impact

- **项目结构**: Maven 模块数量从 3 个减少到 2 个。
- **构建流程**: 前端构建工作目录变更，Vite 输出路径调整。
- **开发者体验**: 无需跨模块切换，单一模块内完成前后端开发。
- **部署产物**: 无变化，`static/` 目录结构和 SPA 服务逻辑不变。
- **CI/CD**: 需要更新引用 `telegram-notifier-control-web` 路径的脚本。
