## 1. 迁移前端源码

- [x] 1.1 将 `telegram-notifier-control-web/src/main/frontend/` 下的所有文件（排除 node_modules 和 target）复制到 `telegram-notifier-control-server/src/main/frontend/`
- [x] 1.2 删除 `telegram-notifier-control-web/` 模块目录
- [x] 1.3 从父 `pom.xml` 的 `<modules>` 中移除 `telegram-notifier-control-web`

## 2. 调整构建配置

- [x] 2.1 修改 `vite.config.js` 的 `build.outDir` 为 `'../resources/static'`（相对路径）
- [x] 2.2 在 `src/main/frontend/` 下执行 `npm install` 验证依赖安装正常
- [x] 2.3 执行 `npm run build` 验证构建产物正确输出到 `src/main/resources/static/`

## 3. 更新项目文档

- [x] 3.1 更新 AGENTS.md 中关于 Vue 源码路径的描述（`telegram-notifier-control-web/src/main/frontend` → `telegram-notifier-control-server/src/main/frontend`）
- [x] 3.2 检查并更新 README.md 中引用 `telegram-notifier-control-web` 的内容

## 4. 清理与验证

- [x] 4.1 确认 `.gitignore` 规则覆盖 `telegram-notifier-control-server/src/main/frontend/node_modules/`
- [x] 4.2 执行 `mvn test` 验证后端测试通过
- [x] 4.3 执行 `npm test`（在 `src/main/frontend/` 下）验证前端测试通过
- [x] 4.4 全文搜索项目中残留的 `telegram-notifier-control-web` 引用并清理
