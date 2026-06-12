# 前端 UI 风格改造设计文档

> 目标：让 `knowledge-base-ui` 的视觉风格与 `docs/images/` 下的三张系统截图（运维平台 / 配置中心 / 应用与组件库）高度一致——从当前的「深色玻璃拟态（cyan/blue）」切换为「浅色企业 B2B 风格」。

日期：2026-06-12
范围：仅前端（`knowledge-base-ui`），不动后端

---

## 1. 背景与目标

### 1.1 现状

- 主题：暗色玻璃拟态（`antd.dark = true`，主色 `#38d7ff` 青色）
- 自定义深色 CSS 覆盖 200+ 行（`global.css`）
- Layout 模式 `mix`（侧栏 + 顶部）
- 含 ThemeSwitcher（`vite-ui-theme` 切换）
- 登录页有视频背景

### 1.2 目标

参考 `docs/images/` 三张截图，将整体视觉改为：

- **白底 + 蓝色主调 + 干净边框** 的浅色企业 B2B 风格
- Sider 顶部 Logo 区为深蓝渐变（Sider 菜单区保持白底蓝选）
- 表格/卡片/表单使用 Ant Design 5 默认浅色样式
- 删除暗色模式切换、玻璃拟态、霓虹光效
- 保留品牌「AI 知识库」+ 现有 logo

### 1.3 改造范围

| 类别 | 是否改造 |
|---|---|
| `.umirc.ts` 主题与路由 | 是 |
| `app.tsx` 布局 | 是 |
| `global.css` 全局样式 | 是（大量删减） |
| `ThemeSwitcher/` 组件 | 删除 |
| `ChatConversation/ChatWindow/ChatBottombar/ChatMessage/` | 是（改色） |
| `pages/Chat/` | 是 |
| `pages/KnowledgeBase/` | 是 |
| `pages/Document/` | 是 |
| `pages/Login/` | 是（去视频） |
| `pages/404/403/` | 是 |
| 后端、`services/`、`models/`、OpenAPI 生成 | **否** |

---

## 2. 主题 Token 设计

### 2.1 Ant Design 5 token

```ts
// .umirc.ts antd.theme.token
{
  colorPrimary: '#2563eb',     // 亮蓝（Ant Design 默认偏深）
  colorInfo: '#2563eb',
  colorSuccess: '#52c41a',
  colorWarning: '#faad14',
  colorError: '#ff4d4f',
  colorBgBase: '#ffffff',
  colorBgContainer: '#ffffff',
  colorBgLayout: '#f5f7fa',
  colorBorder: '#e5e7eb',
  colorText: '#1f2937',
  colorTextSecondary: '#4b5563',
  colorTextTertiary: '#6b7280',
  borderRadius: 6,
  wireframe: false,
}
```

- `antd.dark = false`（核心开关）
- 删除 `cyberBlueTheme`（在 `ThemeSwitcher.tsx` 和 `.umirc.ts` 中）
- 删除 `Menu`/`Button`/`Select` 组件级自定义 token

### 2.2 轻量点缀（仅 Sider 顶部 Logo 区）

```css
/* global.css 保留项 */
.ant-pro-layout-sider-logo,
.ant-pro-global-header-logo {
  background: linear-gradient(135deg, #1e3a8a 0%, #2563eb 100%) !important;
  color: #ffffff !important;
  border-bottom: 1px solid rgba(0, 0, 0, 0.06);
}
.ant-pro-layout-sider-logo h1,
.ant-pro-global-header-logo h1 {
  color: #ffffff !important;
  font-weight: 600;
}
```

仅此一项定制。

---

## 3. Layout 与路由

### 3.1 `.umirc.ts` 路由调整

- 新增父菜单分组（`path: '/'` 父路由 `name: '知识库管理'`）
- 子项：`/chat`（AI 对话）、`/knowlegeBase`（知识库列表）
- `/knowlegeBase/:id` 保持 `hideInMenu: true`

### 3.2 `app.tsx` Layout 配置

```ts
export const layout: RunTimeLayoutConfig = ({ initialState }) => {
  return {
    title: 'AI 知识库',
    logo: process.env.UMI_APP_LOGO,
    layout: 'sider',  // 原 'mix' → 'sider'
    menu: { locale: false },
    // 删除 ThemeSwitcher、GitHub 图标
    // 删除 collapsed/onCollapse（Chat 内层不再依赖外层折叠）
    avatarProps: { /* 保留 */ },
    onPageChange: () => { /* 保留 Token 校验 */ },
  };
};
```

`getInitialState` 中删除 `theme: localStorage.getItem('vite-ui-theme')` 相关逻辑。

---

## 4. 页面改造

### 4.1 Chat

- `ChatConversation`：会话列表从深色玻璃 → 白底 + 浅灰 hover + `#2563eb` 蓝选中
- `ChatWindow`：消息区白底；用户消息气泡 `#2563eb` 实底白字；AI 消息气泡 `#f3f4f6` 灰底深字
- `ChatBottombar`：输入框白底浅边框；发送按钮 `#2563eb` 主色
- `pages/Chat/index.css`：删除深色 glass 相关 CSS

### 4.2 KnowledgeBase 列表

- `ProList` 去掉 `ghost` 透明背景
- 卡片白底、`border-color: #e5e7eb`
- hover 浅灰底
- 「详情」主按钮；「删除」danger 按钮

### 4.3 Document 详情

- `ProDescriptions` / `ProTable` 用 Ant Design 默认浅色
- 文件上传 `Upload` 默认浅色

### 4.4 Login

- 删 `backgroundVideoUrl`
- 删 `containerStyle.backgroundColor: 'rgba(0,0,0,0.65)'`
- 页面背景 `#f5f7fa`，登录卡片白底
- 保留 `LoginFormPage` + 账号密码 Tab

### 4.5 404 / 403

- 用 Ant Design `Result` 组件，默认浅色

---

## 5. 删除项

- `src/component/ThemeSwitcher/` 整个目录
- `global.css` 中所有 `.ant-pro-layout .ant-pro-sider`、`.ant-card`、`.ant-btn-primary` 等深色覆盖
- `cyberBlueTheme` 硬编码（`.umirc.ts` + `ThemeSwitcher.tsx`）
- `vite-ui-theme` localStorage 逻辑
- 登录页视频背景 + 黑底遮罩

---

## 6. 验收

### 6.1 人工验收（`pnpm dev`）

| 路由 | 检查项 |
|---|---|
| `/login` | 浅色卡片，无视频背景 |
| `/chat` | 浅色会话列表 + 浅色聊天区 + 浅色输入框 |
| `/knowlegeBase` | 白底卡片网格 + 主色按钮 |
| `/knowlegeBase/:id` | 浅色详情/上传区 |
| `/404` `/403` | Ant Design Result 浅色样式 |
| 全局 | Sider 顶部深蓝渐变 Logo + 白底菜单区 |
| 全局 | 菜单选中蓝色、按钮主蓝、链接主蓝 |

### 6.2 DevTools 反查

搜索旧色值应为零匹配：

- `rgba(8, 24, 52`
- `rgba(56, 215, 255`
- `rgba(22, 119, 255`
- `#38d7ff`
- `#030712`
- `backdrop-filter: blur`

### 6.3 命令验证

- `pnpm dev` 无 React/TS 报错
- `pnpm build` 通过
- `pnpm format` 通过

### 6.4 回归保护

- 后端 API、数据库、JWT 流程**不变**
- 登录态、Token 携带**不变**
- 所有 `services/*`、OpenAPI 类型**不变**

---

## 7. 不在本次范围

- 后端任何修改
- 新功能、新页面
- 移动端响应式优化
- 多语言（保持中文）
- 暗色模式（已删除，无回归）

---

## 8. 风险与回滚

### 风险

- `global.css` 大面积删改，可能漏改导致某些组件仍带深色样式
- `cyberBlueTheme` 同时存在于两处（`.umirc.ts` 和 `ThemeSwitcher.tsx`），删完需双确认
- Chat 内部 `ChatConversation` 用作可折叠侧栏，若 `app.tsx` 的 `collapsed/onCollapse` 删除，需确认 Chat 内部是否仍能展开/收起

### 回滚

设计为「减法改造」+「轻量点缀」，每一步可独立回滚：

- `git revert` 整个 commit 即可回到改造前
- 主题 token 修改局限在 `.umirc.ts`，单文件回滚

---

## 9. 实施顺序（概要）

1. `.umirc.ts`：切换 `antd.dark`、改 token、调整路由
2. `app.tsx`：删除 ThemeSwitcher/切换逻辑、Layout 改 `sider`
3. `global.css`：删深色、加 Logo 渐变
4. 删除 `ThemeSwitcher/` 目录
5. Chat 内部组件：会话/气泡/输入框改色
6. KnowledgeBase / Document / Login / 404/403 改色
7. `pnpm dev` 验收
8. `pnpm build` + `pnpm format` 验证
