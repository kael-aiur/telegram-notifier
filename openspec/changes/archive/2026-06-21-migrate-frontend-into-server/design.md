## Context

当前项目采用三模块 Maven 布局：

```
telegram-notifier/
├── telegram-spring-boot-starter/       ← Telegram 集成边界
├── telegram-notifier-control-server/   ← Spring Boot 后端 + 静态资源
└── telegram-notifier-control-web/      ← 空壳 pom + Vue 前端源码
```

`control-web` 模块的 pom.xml 声明 `packaging: pom`，没有 Maven 构建逻辑。前端通过 Vite 独立构建，产物直接写入 `control-server/src/main/resources/static/`。两个模块在 Maven 层面无依赖，物理上通过 `static/` 目录耦合。

## Goals / Non-Goals

**Goals:**

- 消除 `control-web` 空壳模块，将前端源码迁入 `control-server` 统一管理。
- 保持前端构建流程不变（Vite），仅调整工作目录和输出路径。
- 保持部署产物结构不变（`static/` 目录下的 SPA 资源）。
- 保持 Spring Boot 的 `SpaFallbackFilter` 和静态资源服务逻辑不变。

**Non-Goals:**

- 不改变前端技术栈（Vue + Vite）。
- 不改变后端 API 结构或数据库 schema。
- 不引入 Maven 前端插件（如 frontend-maven-plugin）自动化前端构建——这属于后续优化。
- 不改变 `telegram-spring-boot-starter` 模块。

## Decisions

### 决策 1：前端源码放置在 `src/main/frontend/`

**选择**: `telegram-notifier-control-server/src/main/frontend/`

**备选方案**:
- `frontend/`（模块根目录）—— 会污染 Maven 模块根目录，破坏标准项目结构的直觉。
- `src/main/webapp/` —— 这是传统 Java Web 项目的约定，但与 Vue/Vite 生态不搭，且 `webapp` 通常暗示 JSP/Servlet 资源。
- `src/frontend/` —— 去掉 `main` 层级，与 Maven 的 `src/main` / `src/test` 对称性不一致。

**理由**: 与当前 `control-web` 模块的路径一致（`src/main/frontend/`），迁移零认知成本。在 `src/main/` 下按资源类型分目录是 Maven 的自然延伸（`java/`、`resources/`、`frontend/`）。Vite 的 `outDir` 用相对路径 `../resources/static` 即可，路径关系清晰。

### 决策 2：Vite 输出路径使用相对路径

**选择**: `build.outDir: '../resources/static'`

**理由**: 从 `src/main/frontend/` 到 `src/main/resources/static/` 的相对路径简洁明了，不依赖项目根目录的绝对路径，可在任何环境下工作。

### 决策 3：删除 `control-web` 模块而非保留空目录

**选择**: 完整删除 `telegram-notifier-control-web/` 目录，并从父 pom.xml 移除 module 声明。

**理由**: 保留一个空的或只有 README 的模块没有价值，只会增加维护负担和困惑。Git 历史保留了完整的迁移记录。

## Risks / Trade-offs

- **[风险] 构建脚本硬编码路径** → 迁移后需检查 CI 脚本和 README 中是否引用了 `telegram-notifier-control-web` 路径。缓解：全文搜索并更新。
- **[风险] `.gitignore` 漏配** → `node_modules/` 如果只在旧模块的 ignore 规则中，新路径下会意外提交。缓解：确认 `.gitignore` 使用通配或包含新路径。
- **[权衡] 前端构建未自动化** → 当前前端构建仍需手动执行 `npm run build`，未集成到 Maven 生命周期。这是已有的现状，本次迁移不解决，后续可通过 `frontend-maven-plugin` 改进。
