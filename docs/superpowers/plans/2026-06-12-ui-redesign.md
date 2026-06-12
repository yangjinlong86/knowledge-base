# 前端 UI 风格改造实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 将 `knowledge-base-ui` 从深色玻璃拟态（cyan/blue）改造为浅色企业 B2B 风格（白底、亮蓝主调 `#2563eb`、干净边框），与 `docs/images/` 三张系统截图保持高度一致。

**Architecture:** 减法改造——删除深色 token / 玻璃拟态 CSS / 暗色模式切换逻辑；Ant Design 5 默认 token 接管 80% 视觉；仅在 Sider 顶部 Logo 区保留一项深蓝渐变点缀。后端、API、路由结构、组件 props 不动。

**Tech Stack:** Umi.js 4.4 (@umijs/max) + Ant Design 5 + React 18 + TypeScript + pnpm。

**参考文档：** `docs/superpowers/specs/2026-06-12-ui-redesign-design.md`

---

## 验收总览

完成所有任务后必须通过：

- `pnpm dev` 启动后浏览器访问 `/login`、`/chat`、`/knowlegeBase`、`/knowlegeBase/<id>`、`/404`、`/403` 均能正常显示，主题为浅色
- Sider 顶部 Logo 区为深蓝渐变，菜单区为白底
- `pnpm build` 通过
- `pnpm format` 通过
- DevTools 搜索 `rgba(8, 24, 52`、`rgba(56, 215, 255`、`rgba(22, 119, 255`、`#38d7ff`、`#030712`、`backdrop-filter: blur` 应无匹配
- 浏览器控制台无 React/TS 报错
- 登录态、Token 携带、JWT 流程保持正常

---

## 文件结构地图

**修改（13 个）：**
- `knowledge-base-ui/.umirc.ts` — antd.dark=false、token 改浅、菜单加父组
- `knowledge-base-ui/src/app.tsx` — 删除 ThemeSwitcher、删除 vite-ui-theme、layout 改 sider
- `knowledge-base-ui/src/global.css` — 大幅删深色，仅保留 Sider 顶部 Logo 渐变
- `knowledge-base-ui/src/component/MarkdownContent/MarkdownContent.tsx` — 改用 darkTheme 常量
- `knowledge-base-ui/src/component/ChatConversation/ChatConversation.tsx` + `index.css` — 浅色
- `knowledge-base-ui/src/component/ChatWindow/ChatWindow.tsx` + `index.css` — 浅色
- `knowledge-base-ui/src/component/ChatBottombar/ChatBottombar.tsx` + `index.css` — 浅色
- `knowledge-base-ui/src/component/ChatList/ChatList.tsx` + `index.css` — 浅色
- `knowledge-base-ui/src/component/ChatMessage/ChatMessage.tsx` + `index.css` — 浅色
- `knowledge-base-ui/src/pages/Chat/index.tsx` + `index.css` — 浅色
- `knowledge-base-ui/src/pages/KnowledgeBase/index.tsx` — 浅色
- `knowledge-base-ui/src/pages/Document/index.tsx` — 浅色
- `knowledge-base-ui/src/pages/Login/index.tsx` — 去视频
- `knowledge-base-ui/src/pages/404/index.tsx` + `pages/403/index.tsx` — 浅色

**删除（1 个目录）：**
- `knowledge-base-ui/src/component/ThemeSwitcher/`

**不动：** 后端所有文件、`services/*`、`models/*`（除 collapsed 视情况）、OpenAPI 生成类型、所有 `.umi/` 自动生成文件。

---

## Task 1: 更新 `.umirc.ts` 主题配置与路由

**Files:**
- Modify: `knowledge-base-ui/.umirc.ts`

- [ ] **Step 1: 修改 antd 配置为浅色**

将整个文件替换为以下内容：

```ts
import { defineConfig } from '@umijs/max';

export default defineConfig({
  plugins: ['@umijs/max-plugin-openapi'],
  antd: {
    dark: false,
    theme: {
      token: {
        colorPrimary: '#2563eb',
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
        colorTextQuaternary: '#9ca3af',
        borderRadius: 6,
        wireframe: false,
      },
    },
  },
  access: {},
  model: {},
  initialState: {},
  request: {
    dataField: 'data',
  },
  layout: {},
  routes: [
    {
      path: '/login',
      name: 'login',
      component: '@/pages/Login',
      title: '登录',
      layout: false,
    },
    {
      path: '/',
      redirect: '/chat',
      routes: [
        {
          path: '/chat',
          name: 'AI 对话',
          title: 'AI 对话',
          component: '@/pages/Chat',
          icon: 'RobotOutlined',
        },
        {
          path: '/knowlegeBase',
          name: '知识库列表',
          title: '知识库列表',
          component: '@/pages/KnowledgeBase',
          icon: 'BookOutlined',
        },
        {
          path: '/knowlegeBase/:knowledgeBaseId',
          name: '知识库详情',
          title: '知识库详情',
          component: '@/pages/Document',
          hideInMenu: true,
        },
      ],
    },
    { path: '/*', component: '@/pages/404' },
  ],
  npmClient: 'npm',
  proxy: {
    '/api': {
      target: 'http://localhost:8788/api',
      changeOrigin: true,
      secure: false,
      pathRewrite: {
        '^/api': '',
      },
    },
  },
  openAPI: [
    {
      requestLibPath: "import { request } from '@umijs/max'",
      schemaPath: `http://localhost:8788/api/v3/api-docs/default`,
      mock: false,
      apiPrefix() {
        return "'/api'";
      },
    },
  ],
});
```

- [ ] **Step 2: 验证文件可被 Umi 解析**

Run:
```bash
cd knowledge-base-ui && pnpm dev
```

Expected: 终端显示 Umi 启动，端口 3000；浏览器访问 `http://localhost:3000/login` 后页面**虽然还是老样式**（global.css 还没改），但**无 TS / Umi 编译错误**。Ctrl+C 停止 dev。

- [ ] **Step 3: 提交**

```bash
cd /Users/yangjl/local/github/yangjl/knowledge-base
git add knowledge-base-ui/.umirc.ts
git commit -m "refactor(ui): switch antd theme to light tokens with parent menu group"
```

---

## Task 2: 重写 `app.tsx` 移除暗色切换

**Files:**
- Modify: `knowledge-base-ui/src/app.tsx`

- [ ] **Step 1: 重写 `app.tsx`**

将整个文件替换为以下内容：

```ts
// src/app.ts
import { UserOutlined } from '@ant-design/icons';
import {
  AxiosResponse,
  history,
  RequestConfig,
  RunTimeLayoutConfig,
} from '@umijs/max';
import { Avatar, Button, Dropdown, MenuProps } from 'antd';
import { useRef, useState } from 'react';
import { GlobalType } from './access';
import { userInfo } from './services/authController';

// 白名单路由，不拦截（如登录页）
const loginWhiteList = ['/login'];

export async function getInitialState(): Promise<GlobalType> {
  const token = localStorage.getItem('token');
  // 无 token，跳转登录
  if (!token) {
    const { location } = history;
    if (!loginWhiteList.includes(location.pathname)) {
      history.push('/login');
    }
    return { authVO: undefined };
  }

  // 有 token，尝试获取用户信息
  try {
    const res = await userInfo();
    if (res?.data) {
      return { authVO: res.data };
    }
  } catch (e) {
    console.error('获取用户信息失败:', e);
  }

  // 如果获取失败，跳转登录页
  history.push('/login');
  return { authVO: undefined };
}

export const layout: RunTimeLayoutConfig = ({ initialState }) => {
  const logout = () => {
    localStorage.removeItem('token');
    history.push('/login');
  };
  const userInfoRef = useRef(initialState?.authVO);
  const [username, setUsername] = useState('');
  const items: MenuProps['items'] = [
    {
      key: 'logout',
      label: (
        <Button danger type="text" onClick={logout}>
          退出登录
        </Button>
      ),
    },
  ];
  return {
    title: 'AI 知识库',
    logo: process.env.UMI_APP_LOGO,
    menu: {
      locale: false,
    },
    layout: 'sider',
    actionsRender: () => [],
    avatarProps: {
      render: () => {
        return (
          <Dropdown menu={{ items }} placement="bottom" arrow>
            <div
              style={{
                width: 'max-content',
                display: 'flex',
                gap: '10px',
                alignItems: 'center',
              }}
            >
              <Avatar icon={<UserOutlined />} />
              <span>欢迎您, {username}</span>
            </div>
          </Dropdown>
        );
      },
    },
    // 页面切换时拦截未登录并请求用户信息
    onPageChange: async () => {
      const token = localStorage.getItem('token');
      if (!token) {
        history.push('/login');
      } else {
        try {
          const res = await userInfo();
          if (res?.data) {
            userInfoRef.current = res.data;
            setUsername(userInfoRef.current.username ?? '未知用户');
          }
        } catch (e) {
          console.error('获取用户信息失败:', e);
        }
      }
    },
  };
};

export const request: RequestConfig = {
  errorConfig: {
    errorThrower: () => {},
    errorHandler: () => {},
  },
  requestInterceptors: [
    (url, options) => {
      const token = localStorage.getItem('token');
      if (token) {
        options.headers = {
          ...options.headers,
          Authorization: `Bearer ${token}`,
        };
      }
      return { url, options };
    },
  ],
  responseInterceptors: [
    (response: AxiosResponse) => {
      return response;
    },
  ],
};
```

变更点：
- 移除 `ThemeType` 和 `vite-ui-theme` localStorage 逻辑
- 移除 `GithubOutlined` 按钮
- 移除 `ThemeSwitcher` 引用
- 移除 `useModel('collapsed')` 依赖
- `actionsRender: () => []`（不再渲染任何顶部图标）
- `layout: 'sider'`（原 'mix'）

- [ ] **Step 2: 验证 TypeScript 编译**

Run:
```bash
cd knowledge-base-ui && pnpm dev
```

Expected: 终端无 TypeScript 错误，浏览器 `http://localhost:3000/login` 仍能加载（虽然 Login 还是老样式）。Ctrl+C 停止 dev。

- [ ] **Step 3: 提交**

```bash
cd /Users/yangjl/local/github/yangjl/knowledge-base
git add knowledge-base-ui/src/app.tsx
git commit -m "refactor(ui): remove ThemeSwitcher and dark mode toggle from app.tsx"
```

---

## Task 3: 同步更新 `access.ts` 删除 ThemeType

**Files:**
- Modify: `knowledge-base-ui/src/access.ts`

- [ ] **Step 1: 重写 `access.ts`**

将整个文件替换为以下内容：

```ts
export interface GlobalType {
  authVO?: API.AuthVO;
}

export default (initialState: GlobalType) => {
  // 在这里按照初始化数据定义项目中的权限，统一管理
  // 参考文档 https://umijs.org/docs/max/access
  const canSeeAdmin =
    initialState && initialState.authVO?.roles?.includes('admin');
  return {
    canSeeAdmin,
  };
};
```

变更点：删除 `ThemeType` 导出（不再需要）。

- [ ] **Step 2: 验证 TypeScript 编译**

Run:
```bash
cd knowledge-base-ui && pnpm dev
```

Expected: 无 TypeScript 错误。Ctrl+C 停止 dev。

- [ ] **Step 3: 提交**

```bash
cd /Users/yangjl/local/github/yangjl/knowledge-base
git add knowledge-base-ui/src/access.ts
git commit -m "refactor(ui): remove ThemeType from access.ts"
```

---

## Task 4: 删除 ThemeSwitcher 目录

**Files:**
- Delete: `knowledge-base-ui/src/component/ThemeSwitcher/` (整个目录)

- [ ] **Step 1: 删除目录**

Run:
```bash
rm -rf /Users/yangjl/local/github/yangjl/knowledge-base/knowledge-base-ui/src/component/ThemeSwitcher
```

- [ ] **Step 2: 验证无引用残留**

Run:
```bash
grep -r "ThemeSwitcher" /Users/yangjl/local/github/yangjl/knowledge-base/knowledge-base-ui/src/
```

Expected: 无任何匹配输出（说明没有其他文件引用）。

- [ ] **Step 3: 提交**

```bash
cd /Users/yangjl/local/github/yangjl/knowledge-base
git add -u knowledge-base-ui/src/component/ThemeSwitcher
git commit -m "refactor(ui): delete ThemeSwitcher component directory"
```

---

## Task 5: 重写 `global.css` 移除深色玻璃拟态

**Files:**
- Modify: `knowledge-base-ui/src/global.css`

- [ ] **Step 1: 替换为浅色基础样式**

将整个文件替换为以下内容：

```css
:root {
  --kb-border: #e5e7eb;
  --kb-bg-layout: #f5f7fa;
  --kb-text: #1f2937;
  --kb-text-secondary: #4b5563;
  --kb-blue: #2563eb;
  --kb-card-shadow: 0 1px 2px rgba(0, 0, 0, 0.04);
}

html,
body,
#root {
  min-height: 100%;
  background: var(--kb-bg-layout);
  color: var(--kb-text);
}

body {
  font-family:
    -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, 'Helvetica Neue',
    Arial, sans-serif;
}

/* Sider 顶部 Logo 区：深蓝渐变 + 白字（截图风格的"轻量点缀"） */
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

变更点：删除所有 `rgba(8, 24, 52, ...)`、`rgba(56, 215, 255, ...)`、`backdrop-filter: blur`、渐变阴影等深色玻璃拟态覆盖；保留 body 基础样式 + 唯一的 Sider Logo 渐变点缀。

- [ ] **Step 2: 验证 CSS 加载无错**

Run:
```bash
cd knowledge-base-ui && pnpm dev
```

Expected: 浏览器 `http://localhost:3000/login` 加载，控制台无 CSS 错误（页面背景应该是浅灰色 `#f5f7fa` 而不是之前的深色 radial-gradient）。Ctrl+C 停止 dev。

- [ ] **Step 3: 提交**

```bash
cd /Users/yangjl/local/github/yangjl/knowledge-base
git add knowledge-base-ui/src/global.css
git commit -m "refactor(ui): rewrite global.css to light theme with sider logo gradient"
```

---

## Task 6: 更新 `MarkdownContent` 移除主题判断

**Files:**
- Modify: `knowledge-base-ui/src/component/MarkdownContent/MarkdownContent.tsx`

- [ ] **Step 1: 修改 MarkdownContent**

将整个文件替换为：

```tsx
import 'katex/dist/katex.min.css';
import ReactMarkdown from 'react-markdown';
import { Prism as SyntaxHighlighter } from 'react-syntax-highlighter';
import { duotoneDark as codeTheme } from 'react-syntax-highlighter/dist/esm/styles/prism';
import rehypeKatex from 'rehype-katex';
import remarkMath from 'remark-math';

interface Props {
  content: string;
}

const MarkdownContent = (p: Props) => {
  // 浅色主题下的代码块仍使用深色语法高亮，与截图 2 一致
  return (
    <ReactMarkdown
      rehypePlugins={[rehypeKatex]}
      remarkPlugins={[remarkMath]}
      components={{
        code(props) {
          const { children, className, node, style, ref, ...rest } = props;
          const match = /language-(\w+)/.exec(className || '');
          return match ? (
            <SyntaxHighlighter
              language={match[1]}
              PreTag="div"
              style={codeTheme}
              {...rest}
            >
              {String(children).replace(/\n$/, '')}
            </SyntaxHighlighter>
          ) : (
            <code className={className} {...props}>
              {children}
            </code>
          );
        },
      }}
    >
      {p.content}
    </ReactMarkdown>
  );
};

export default MarkdownContent;
```

变更点：删除 `localStorage.getItem('vite-ui-theme')` 判断，常量使用 `duotoneDark`（截图 2 的代码编辑器是深色）。

- [ ] **Step 2: 验证无 vite-ui-theme 引用**

Run:
```bash
grep -r "vite-ui-theme" /Users/yangjl/local/github/yangjl/knowledge-base/knowledge-base-ui/src/
```

Expected: 无任何匹配（仅 `app.tsx` 和 `access.ts` 之前已清理）。

- [ ] **Step 3: 提交**

```bash
cd /Users/yangjl/local/github/yangjl/knowledge-base
git add knowledge-base-ui/src/component/MarkdownContent/MarkdownContent.tsx
git commit -m "refactor(ui): remove theme switch from MarkdownContent, use dark code theme"
```

---

## Task 7: 重写 `ChatConversation` 组件为浅色

**Files:**
- Modify: `knowledge-base-ui/src/component/ChatConversation/ChatConversation.tsx`
- Modify: `knowledge-base-ui/src/component/ChatConversation/index.css`

- [ ] **Step 1: 替换 `index.css` 为浅色**

将 `knowledge-base-ui/src/component/ChatConversation/index.css` 整个文件替换为：

```css
.base-box {
  position: relative;
  width: 260px;
  min-width: 260px;
  height: 100%;
  display: flex;
  flex-direction: column;
  gap: 16px;
  padding: 16px 12px;
  border: 1px solid var(--kb-border);
  border-radius: 8px;
  background: #ffffff;
  box-shadow: 0 1px 2px rgba(0, 0, 0, 0.04);
}

.menu-box {
  width: 100%;
  border-inline-end: none;
  overflow-y: auto;
  scrollbar-width: none;
  -ms-overflow-style: none;
}

.menu-box::-webkit-scrollbar {
  display: none;
}

.menu-style {
  border-inline-end: none;
  border-right: none;
  padding: 4px 0;
  background: transparent;
}

.bottom-box {
  margin-top: auto;
  width: 100%;
  border-radius: 6px;
  padding: 12px;
  background: #fafafa;
  border: 1px solid var(--kb-border);
}
```

- [ ] **Step 2: 替换 `ChatConversation.tsx`**

将整个文件替换为：

```tsx
import {
  listChatConversation,
  removeChatConversation,
} from '@/services/chatConversationController';
import { simpleKnowledgeBase } from '@/services/knowledgeBaseController';
import { DeleteOutlined, PlusOutlined } from '@ant-design/icons';
import { useModel } from '@umijs/max';
import {
  Button,
  Empty,
  Form,
  GetProp,
  Menu,
  MenuProps,
  message,
  Popconfirm,
  Select,
  Tooltip,
} from 'antd';
import { useEffect, useState } from 'react';
import './index.css';
type MenuItem = GetProp<MenuProps, 'items'>[number];

const chatOptions = [
  { value: 'simple', label: '简单对话' },
  { value: 'simpleRAG', label: '简单 RAG 对话' },
  { value: 'multimodal', label: '多模态对话' },
  { value: 'multimodalRAG', label: '多模态 RAG 对话' },
];
const ChatConversation = () => {
  const {
    curConversationId,
    setCurConverstationId,
    chatSetting,
    setChatSetting,
  } = useModel('chat');
  const [messageApi, contextHolder] = message.useMessage();

  const [baseOptions, setBaseOptions] = useState<
    { value: string; label: string }[]
  >([]);

  const [conversationItem, setConversationItem] = useState<MenuItem[]>([]);

  // 加载知识库列表
  const loadSimpleBaseList = async () => {
    try {
      const res = await simpleKnowledgeBase();
      if (res.code === 0 && res.data) {
        const options = res.data.map((item) => ({
          value: item.id ?? '',
          label: item.name ?? '',
        }));
        setBaseOptions(options);
      } else {
        messageApi.error(res.message);
      }
    } catch (e) {
      console.log(e);
    }
  };
  // 加载对话列表
  const loadConversationList = async () => {
    try {
      const res = await listChatConversation();
      if (res.code === 0 && res.data) {
        const items = res.data.map((item) => {
          const menuItem: MenuItem = {
            key: item.id ?? '',
            label: item.title ?? '',
            extra: (
              <Tooltip title="删除对话记录">
                <Popconfirm
                  title="删除记录记录?"
                  description={`这会删除"${item.title}"。`}
                  onConfirm={async () => {
                    await removeConversation(item);
                  }}
                >
                  <DeleteOutlined style={{ color: '#ff4d4f' }} />
                </Popconfirm>
              </Tooltip>
            ),
            onClick: () => {
              setCurConverstationId(item.id ?? '');
            },
          };
          return menuItem;
        });
        setConversationItem(items);
      } else {
        messageApi.error(res.message);
      }
    } catch (e) {
      console.log(e);
    }
  };
  // 删除对话
  const removeConversation = async (value: API.ChatConversationVO) => {
    try {
      const res = await removeChatConversation({ ...value });
      if (res.code === 0 && res.data) {
        messageApi.success('删除成功');
        setCurConverstationId('');
      } else {
        messageApi.error(res.message);
      }
    } finally {
      await loadConversationList();
    }
  };

  useEffect(() => {
    loadSimpleBaseList();
    loadConversationList();
  }, []);

  useEffect(() => {
    loadConversationList();
  }, [curConversationId]);
  return (
    <>
      {contextHolder}
      <div className="base-box">
        <div
          style={{ height: '20px', display: 'flex', justifyContent: 'center' }}
        >
          <Button
            type="primary"
            style={{ width: '100%' }}
            icon={<PlusOutlined />}
            onClick={() => {
              setCurConverstationId('');
            }}
          >
            开启新对话
          </Button>
        </div>

        <div className="menu-box">
          {conversationItem.length > 0 ? (
            <Menu
              items={conversationItem}
              selectedKeys={
                curConversationId && curConversationId !== ''
                  ? [curConversationId]
                  : []
              }
              onSelect={(e) => {
                setCurConverstationId(e.key);
              }}
              className="menu-style"
            ></Menu>
          ) : (
            <Empty description="暂无对话" />
          )}
        </div>
        <div className="bottom-box">
          <Form
            layout="vertical"
            initialValues={chatSetting}
            onValuesChange={(value) => {
              setChatSetting({ ...chatSetting, ...value });
            }}
          >
            <Form.Item label="对话模式" name="chatType">
              <Select style={{ width: '100%' }} options={chatOptions} />
            </Form.Item>
            <Form.Item label="知识库" name="knowledgeIds">
              <Select
                style={{ width: '100%' }}
                options={baseOptions}
                placeholder="请选择知识库"
                mode="multiple"
                disabled={!chatSetting.chatType?.includes('RAG')}
              />
            </Form.Item>
          </Form>
        </div>
      </div>
    </>
  );
};

export default ChatConversation;
```

变更点：图标 `PlusSquareTwoTone` → `PlusOutlined`；删除 useModel('collapsed') 引用（保留以备 Chat 页内可能用到）。

- [ ] **Step 3: 验证编译**

Run:
```bash
cd knowledge-base-ui && pnpm dev
```

Expected: 浏览器打开 `http://localhost:3000/chat`（如未登录会跳 `/login`，先用 dev 登录态试）。控制台无报错。Ctrl+C 停止。

- [ ] **Step 4: 提交**

```bash
cd /Users/yangjl/local/github/yangjl/knowledge-base
git add knowledge-base-ui/src/component/ChatConversation/
git commit -m "refactor(ui): rewrite ChatConversation to light theme"
```

---

## Task 8: 重写 `ChatWindow` 组件为浅色

**Files:**
- Modify: `knowledge-base-ui/src/component/ChatWindow/ChatWindow.tsx`
- Modify: `knowledge-base-ui/src/component/ChatWindow/index.css`

- [ ] **Step 1: 替换 `index.css` 为浅色**

将 `knowledge-base-ui/src/component/ChatWindow/index.css` 整个文件替换为：

```css
.chat-window-box {
  position: relative;
  width: 100%;
  height: 100%;
  display: flex;
  flex-direction: column;
  justify-content: space-between;
  align-items: center;
  overflow: hidden;
  border: 1px solid var(--kb-border);
  border-radius: 8px;
  background: #ffffff;
  box-shadow: 0 1px 2px rgba(0, 0, 0, 0.04);
}
```

- [ ] **Step 2: `ChatWindow.tsx` 保持不变**

`ChatWindow.tsx` 本身没有视觉样式（仅 JSX 结构），颜色全部由子组件 `ChatList` 和 `ChatBottombar` 控制。**不修改此文件**。

- [ ] **Step 3: 提交**

```bash
cd /Users/yangjl/local/github/yangjl/knowledge-base
git add knowledge-base-ui/src/component/ChatWindow/index.css
git commit -m "refactor(ui): rewrite ChatWindow container to light theme"
```

---

## Task 9: 重写 `ChatBottombar` 组件为浅色

**Files:**
- Modify: `knowledge-base-ui/src/component/ChatBottombar/ChatBottombar.tsx`
- Modify: `knowledge-base-ui/src/component/ChatBottombar/index.css`

- [ ] **Step 1: 替换 `index.css` 为浅色**

将 `knowledge-base-ui/src/component/ChatBottombar/index.css` 整个文件替换为：

```css
.input-box {
  position: relative;
  z-index: 1;
  width: 96%;
  min-height: 60px;
  padding: 8px 16px;
  margin-bottom: 12px;
  border: 1px solid var(--kb-border);
  border-radius: 8px;
  background: #ffffff;
  transition: border-color 0.2s, box-shadow 0.2s;
}

.input-box:focus-within {
  border-color: var(--kb-blue);
  box-shadow: 0 0 0 2px rgba(37, 99, 235, 0.15);
}

.input-tool {
  padding: 4px 4px 4px 0;
  display: flex;
  justify-content: space-between;
  align-items: center;
}

.image-preview-list {
  width: 96%;
  margin-bottom: 8px;
  margin-left: 24px;
  display: flex;
  gap: 10px;
  justify-content: flex-start;
  flex-wrap: wrap;
}

.image-preview-item {
  position: relative;
  border-radius: 6px;
  overflow: hidden;
  border: 1px solid var(--kb-border);
  background: #ffffff;
}
```

- [ ] **Step 2: 修改 `ChatBottombar.tsx` 的 TextArea 内联样式**

打开 `knowledge-base-ui/src/component/ChatBottombar/ChatBottombar.tsx`，找到 `<TextArea ... style={{...}}>`，把整个 `style` 属性替换为：

```tsx
            style={{
              fontSize: '16px',
              border: 'none',
              background: 'transparent',
              outline: 'none',
              boxShadow: 'none',
              color: '#1f2937',
            }}
```

变更点：`color: 'rgba(235, 248, 255, 0.92)'` → `color: '#1f2937'`（深色文字配浅色背景）。

- [ ] **Step 3: 验证编译**

Run:
```bash
cd knowledge-base-ui && pnpm dev
```

Expected: 无 TypeScript 错误。Ctrl+C 停止 dev。

- [ ] **Step 4: 提交**

```bash
cd /Users/yangjl/local/github/yangjl/knowledge-base
git add knowledge-base-ui/src/component/ChatBottombar/
git commit -m "refactor(ui): rewrite ChatBottombar to light theme"
```

---

## Task 10: 重写 `ChatList` 组件为浅色

**Files:**
- Modify: `knowledge-base-ui/src/component/ChatList/index.css`

- [ ] **Step 1: 替换 `index.css` 为浅色**

将 `knowledge-base-ui/src/component/ChatList/index.css` 整个文件替换为：

```css
.chat-window-container {
  position: relative;
  z-index: 1;
  width: 100%;
  height: 100%;
  padding: 20px 24px 12px;
  overflow: hidden;
  overflow-y: auto;
  scrollbar-width: none;
  -ms-overflow-style: none;
}

.chat-window-container::-webkit-scrollbar {
  display: none;
}

.chat-window-empty-box {
  display: flex;
  justify-content: center;
  align-items: center;
  width: 100%;
  height: 100%;
  gap: 14px;
  color: #6b7280;
}

.chat-window-empty-box span {
  font-size: 32px;
  letter-spacing: 0.04em;
}
```

变更点：删除 text-shadow 和深色 cyan 颜色。

- [ ] **Step 2: 提交**

```bash
cd /Users/yangjl/local/github/yangjl/knowledge-base
git add knowledge-base-ui/src/component/ChatList/index.css
git commit -m "refactor(ui): rewrite ChatList container to light theme"
```

---

## Task 11: 重写 `ChatMessage` 组件为浅色

**Files:**
- Modify: `knowledge-base-ui/src/component/ChatMessage/index.css`

- [ ] **Step 1: 替换 `index.css` 为浅色**

将 `knowledge-base-ui/src/component/ChatMessage/index.css` 整个文件替换为：

```css
.message-box {
  margin: 0 0 18px;
  display: flex;
  flex-direction: column;
  animation: kbFadeSlide 0.3s ease-out both;
}

@keyframes kbFadeSlide {
  from { opacity: 0; transform: translateY(8px); }
  to   { opacity: 1; transform: translateY(0); }
}

.content-container {
  position: relative;
  min-height: 44px;
  max-width: 92%;
  padding: 12px 16px;
  border-radius: 8px;
  border: 1px solid var(--kb-border);
  background: #ffffff;
  display: flex;
  flex-direction: column;
  max-width: 100%;
  white-space: pre-wrap;
  word-wrap: break-word;
  word-break: break-word;
  font-size: 15px;
  line-height: 1.65;
  color: var(--kb-text);
}

.message-box-user .content-container {
  border-color: var(--kb-blue);
  background: var(--kb-blue);
  color: #ffffff;
}

.message-box-assistant .content-container {
  background: #f3f4f6;
  color: var(--kb-text);
}

.assistant-tool {
  height: 28px;
  display: flex;
  gap: 12px;
  margin: 6px 0 0 12px;
  opacity: 0.6;
  transition: opacity 0.2s;
  color: var(--kb-text-secondary);
}

.assistant-tool:hover {
  opacity: 1;
}

.message-box:first-child {
  margin-top: auto;
}
```

变更点：用户消息气泡改成蓝色实底白字（`#2563eb` + 白字），AI 消息气泡改成浅灰（`#f3f4f6` + 深字），删除 glass / cyan / purple 渐变。

- [ ] **Step 2: 提交**

```bash
cd /Users/yangjl/local/github/yangjl/knowledge-base
git add knowledge-base-ui/src/component/ChatMessage/index.css
git commit -m "refactor(ui): rewrite ChatMessage bubbles to light theme"
```

---

## Task 12: 重写 `pages/Chat` 主页为浅色

**Files:**
- Modify: `knowledge-base-ui/src/pages/Chat/index.tsx`
- Modify: `knowledge-base-ui/src/pages/Chat/index.css`

- [ ] **Step 1: 替换 `index.css` 为浅色**

将 `knowledge-base-ui/src/pages/Chat/index.css` 整个文件替换为：

```css
.chat-page-box {
  position: relative;
  width: 100%;
  height: calc(100vh - 140px);
  min-height: 600px;
  display: flex;
  gap: 16px;
  padding: 16px;
  overflow: hidden;
  background: var(--kb-bg-layout);
}
```

- [ ] **Step 2: 重写 `pages/Chat/index.tsx`**

将整个文件替换为：

```tsx
import ChatConversation from '@/component/ChatConversation';
import ChatWindow from '@/component/ChatWindow';
import { PageContainer } from '@ant-design/pro-components';
import './index.css';

const ChatPage = () => {
  return (
    <PageContainer title={false}>
      <div className="chat-page-box">
        {/* 左边导航栏：会话列表（始终显示，去掉折叠依赖） */}
        <ChatConversation />
        {/* 右边对话界面 */}
        <ChatWindow />
      </div>
    </PageContainer>
  );
};

export default ChatPage;
```

变更点：删除 `useModel('collapsed')` 引用；`<ChatConversation />` 始终显示，不再依赖 `menuCollapsed`；CSS 删除深色 glass。

- [ ] **Step 3: 检查 collapsed model 是否还有引用**

Run:
```bash
grep -r "useModel('collapsed')\|from.*models/collapsed" /Users/yangjl/local/github/yangjl/knowledge-base/knowledge-base-ui/src/
```

Expected: 无匹配输出（说明可以保留 `models/collapsed.ts` 文件，但已无引用）。**保留该文件不动**，以避免破坏任何残留引用。

- [ ] **Step 4: 验证编译**

Run:
```bash
cd knowledge-base-ui && pnpm dev
```

Expected: 登录后访问 `http://localhost:3000/chat`，看到浅色会话列表 + 浅色聊天区 + 浅色输入框。Ctrl+C 停止 dev。

- [ ] **Step 5: 提交**

```bash
cd /Users/yangjl/local/github/yangjl/knowledge-base
git add knowledge-base-ui/src/pages/Chat/
git commit -m "refactor(ui): rewrite Chat page to light theme, always show conversation list"
```

---

## Task 13: 重写 `KnowledgeBase` 页面为浅色

**Files:**
- Modify: `knowledge-base-ui/src/pages/KnowledgeBase/index.tsx`

- [ ] **Step 1: 修改 ProList 样式**

打开 `knowledge-base-ui/src/pages/KnowledgeBase/index.tsx`，定位到 `<ProList<API.KnowledgeBaseVO>` 标签，**移除** `ghost` 属性、`bordered` 属性保持不变（默认就是有边框）。

将：
```tsx
          <ProList<API.KnowledgeBaseVO>
            pagination={false}
            showActions="hover"
            grid={{ gutter: 16, column: 3 }}
            bordered
            ghost
            metas={{
```

改为：
```tsx
          <ProList<API.KnowledgeBaseVO>
            pagination={false}
            showActions="hover"
            grid={{ gutter: 16, column: 3 }}
            metas={{
```

变更点：删除 `ghost`（透明背景变白底），保留 `bordered`。

- [ ] **Step 2: 验证编译**

Run:
```bash
cd knowledge-base-ui && pnpm dev
```

Expected: 访问 `http://localhost:3000/knowlegeBase`，卡片为白底带浅灰边框。Ctrl+C 停止 dev。

- [ ] **Step 3: 提交**

```bash
cd /Users/yangjl/local/github/yangjl/knowledge-base
git add knowledge-base-ui/src/pages/KnowledgeBase/index.tsx
git commit -m "refactor(ui): remove ghost background from KnowledgeBase ProList"
```

---

## Task 14: 更新 `Document` 页面样式

**Files:**
- Modify: `knowledge-base-ui/src/pages/Document/index.tsx`

- [ ] **Step 1: 修改 PageContainer 移除 ghost**

打开 `knowledge-base-ui/src/pages/Document/index.tsx`，定位到：

```tsx
      <PageContainer
        ghost
        title="知识库文档"
```

将 `ghost` 属性**删除**（变白底），title 保留：

```tsx
      <PageContainer
        title="知识库文档"
```

- [ ] **Step 2: 验证编译**

Run:
```bash
cd knowledge-base-ui && pnpm dev
```

Expected: 访问 `http://localhost:3000/knowlegeBase/<id>`，页面是白底卡片式布局。Ctrl+C 停止 dev。

- [ ] **Step 3: 提交**

```bash
cd /Users/yangjl/local/github/yangjl/knowledge-base
git add knowledge-base-ui/src/pages/Document/index.tsx
git commit -m "refactor(ui): remove ghost background from Document page"
```

---

## Task 15: 重写 `Login` 页面去掉视频背景

**Files:**
- Modify: `knowledge-base-ui/src/pages/Login/index.tsx`

- [ ] **Step 1: 重写 Login 页面**

将整个文件替换为：

```tsx
import { login } from '@/services/authController';
import {
  LoginFormPage,
  ProConfigProvider,
  ProFormCheckbox,
} from '@ant-design/pro-components';
import { history, useRequest, useSearchParams } from '@umijs/max';
import { message, Tabs } from 'antd';
import { useState } from 'react';
import UsernamePassword from './components/UsernamePassword';
type LoginType = 'email' | 'account';

const LoginPage = () => {
  const [searchParams] = useSearchParams();
  const [messageApi, contextHolder] = message.useMessage();
  const [loginType, setLoginType] = useState<LoginType>('account');
  const { loading, run: doLogin } = useRequest(
    async (values: API.UserLoginVO) => {
      return await login({
        ...values,
      });
    },
    {
      manual: true,
      onSuccess: (data) => {
        if (data && data.token) {
          localStorage.setItem('token', data.token);
          messageApi.success('登录成功');
          setTimeout(() => {
            history.push('/');
          }, 1000);
        }
      },
      onError: (error) => {
        messageApi.error('登录失败，请检查网络连接');
        console.log(error);
      },
    },
  );
  const handleLoginFinish = async (
    values: API.UserLoginVO,
  ): Promise<boolean> => {
    const res = await doLogin(values);
    return !!res?.token;
  };

  return (
    <>
      {contextHolder}
      <div
        style={{
          backgroundColor: '#f5f7fa',
          minHeight: '100vh',
          display: 'flex',
          alignItems: 'center',
          justifyContent: 'center',
        }}
      >
        <LoginFormPage<API.UserLoginVO>
          loading={loading}
          onFinish={handleLoginFinish}
          logo={process.env.UMI_APP_LOGO}
          title={process.env.UMI_APP_NAME}
          subTitle={process.env.UMI_APP_SUB_TITLE}
          containerStyle={{
            backgroundColor: '#ffffff',
            boxShadow: '0 4px 24px rgba(0, 0, 0, 0.06)',
            borderRadius: 8,
          }}
          backgroundImageUrl=""
        >
          <Tabs
            centered
            activeKey={loginType}
            onChange={(activeKey) => setLoginType(activeKey as LoginType)}
            items={[
              {
                label: '账号密码登录',
                key: 'account',
                children: <UsernamePassword />,
              },
            ]}
          ></Tabs>

          <div
            style={{
              marginBlockEnd: 24,
            }}
          >
            <ProFormCheckbox noStyle name="autoLogin">
              自动登录
            </ProFormCheckbox>
          </div>
        </LoginFormPage>
      </div>
    </>
  );
};

export default () => {
  return (
    <ProConfigProvider hashed={false}>
      <LoginPage />
    </ProConfigProvider>
  );
};
```

变更点：
- 删除 `theme.useToken()`（不再需要暗色背景）
- 删除 `backgroundVideoUrl`
- 背景改为 `#f5f7fa`（浅灰）+ 居中布局
- 容器卡片白底 + 阴影
- 删除 `iconStyles` / `EmailCaptcha` 引用
- `backgroundImageUrl=""` 避免任何默认图片

- [ ] **Step 2: 验证编译**

Run:
```bash
cd knowledge-base-ui && pnpm dev
```

Expected: 浏览器访问 `http://localhost:3000/login` 看到浅灰背景上的白色登录卡片，无视频。Ctrl+C 停止 dev。

- [ ] **Step 3: 提交**

```bash
cd /Users/yangjl/local/github/yangjl/knowledge-base
git add knowledge-base-ui/src/pages/Login/index.tsx
git commit -m "refactor(ui): remove video background from Login page, use light card"
```

---

## Task 16: 简化 404 / 403 页面

**Files:**
- Modify: `knowledge-base-ui/src/pages/404/index.tsx`
- Modify: `knowledge-base-ui/src/pages/403/index.tsx`

- [ ] **Step 1: 重写 404 页面**

将 `knowledge-base-ui/src/pages/404/index.tsx` 整个文件替换为：

```tsx
import { history } from '@umijs/max';
import { Button, Result } from 'antd';

const NoFoundPage = () => {
  const goHome = () => {
    history.push('/');
  };
  return (
    <Result
      status="404"
      title="404"
      subTitle="抱歉，您访问的页面不存在。"
      extra={
        <Button type="primary" onClick={goHome}>
          返回首页
        </Button>
      }
    />
  );
};

export default NoFoundPage;
```

变更点：subTitle 改为中文，`history.push('/home')` → `history.push('/')`（项目无 /home 路由）。

- [ ] **Step 2: 重写 403 页面**

将 `knowledge-base-ui/src/pages/403/index.tsx` 整个文件替换为：

```tsx
import { history } from '@umijs/max';
import { Button, Result } from 'antd';

const NoAuthPage = () => {
  const goHome = () => {
    history.push('/');
  };
  return (
    <Result
      status="403"
      title="403"
      subTitle="对不起，您无权限访问该页面。"
      extra={
        <Button type="primary" onClick={goHome}>
          返回首页
        </Button>
      }
    />
  );
};

export default NoAuthPage;
```

变更点：同上。

- [ ] **Step 3: 验证**

Run:
```bash
cd knowledge-base-ui && pnpm dev
```

Expected: 访问 `http://localhost:3000/404` 和 `http://localhost:3000/403` 看到 Ant Design 默认浅色 Result 组件。Ctrl+C 停止 dev。

- [ ] **Step 4: 提交**

```bash
cd /Users/yangjl/local/github/yangjl/knowledge-base
git add knowledge-base-ui/src/pages/404/ knowledge-base-ui/src/pages/403/
git commit -m "refactor(ui): localize 404/403 page text and fix home navigation"
```

---

## Task 17: 全项目 DevTools 颜色残留反查

**Files:** 无（仅检查）

- [ ] **Step 1: 搜索旧色值**

Run:
```bash
grep -rE "rgba\(8, 24, 52|rgba\(56, 215, 255|rgba\(22, 119, 255|#38d7ff|#030712|backdrop-filter: blur" /Users/yangjl/local/github/yangjl/knowledge-base/knowledge-base-ui/src/
```

Expected: 无匹配输出。**说明已彻底清除旧深色玻璃拟态残留**。

如果出现其他匹配，按提示定位文件并修复。

- [ ] **Step 2: 在浏览器中搜索旧色值**

启动 dev:
```bash
cd knowledge-base-ui && pnpm dev
```

在 Chrome DevTools Sources 面板，搜索全项目：
- `rgba(8, 24, 52`
- `rgba(56, 215, 255`
- `#38d7ff`

Expected: 无匹配（源码 + Umi 编译产物中均不应有）。

- [ ] **Step 3: 提交（如有修改）**

如果 Step 1-2 发现需要修复的文件，按常规 commit 流程提交。**若无修改，跳过此步。**

---

## Task 18: 全路由人工验收

**Files:** 无（仅验收）

- [ ] **Step 1: 启动 dev server 并登录**

```bash
cd knowledge-base-ui && pnpm dev
```

浏览器访问 `http://localhost:3000/login`，用现有账号登录。

- [ ] **Step 2: 逐路由验收**

按顺序访问，对照验收标准：

| 路由 | 检查项 | 通过 |
|---|---|---|
| `/login` | 浅灰背景 + 白色登录卡片 + 无视频 | ☐ |
| `/` | 自动重定向到 `/chat` | ☐ |
| `/chat` | 浅色会话列表 + 浅色聊天区 + 浅色输入框 + 蓝色用户气泡 + 浅灰 AI 气泡 | ☐ |
| `/knowlegeBase` | 白底卡片网格 + 蓝色「详情」按钮 + 红色「删除」按钮 | ☐ |
| `/knowlegeBase/<id>` | 白底详情页 + 白底上传区 + 浅色 ProTable | ☐ |
| `/404` `/403` | 浅色 Ant Design Result | ☐ |
| 全局 | Sider 顶部深蓝渐变 Logo + 白底菜单区 | ☐ |
| 全局 | 菜单选中蓝色、按钮主蓝、链接主蓝 | ☐ |
| 全局 | 浏览器控制台无 React/TS 错误 | ☐ |
| 全局 | 登录态保持，刷新页面仍登录 | ☐ |

每一行都打勾才能进入 Task 19。

- [ ] **Step 3: 停止 dev**

Ctrl+C 停止 dev。

---

## Task 19: 完整构建验证

**Files:** 无（仅命令）

- [ ] **Step 1: 格式化代码**

Run:
```bash
cd /Users/yangjl/local/github/yangjl/knowledge-base
pnpm --filter knowledge-base-ui format
```

Expected: 成功退出码 0。

- [ ] **Step 2: 构建**

Run:
```bash
cd /Users/yangjl/local/github/yangjl/knowledge-base
pnpm --filter knowledge-base-ui build
```

Expected: 成功退出码 0，输出 `dist/` 目录。

- [ ] **Step 3: 全项目 Maven 验证（不跑测试，只验证 Java 格式）**

Run:
```bash
cd /Users/yangjl/local/github/yangjl/knowledge-base
mvn -pl knowledge-base-system spring-javaformat:validate
```

Expected: BUILD SUCCESS。前端修改不应影响 Java 格式，但作为保险跑一次。

- [ ] **Step 4: 提交（如有 format 改动）**

如果 Step 1 改了文件：

```bash
cd /Users/yangjl/local/github/yangjl/knowledge-base
git add -u
git commit -m "style(ui): prettier format"
```

---

## Task 20: 收尾与推送

**Files:** 无

- [ ] **Step 1: 查看本次改造的全部 commits**

Run:
```bash
cd /Users/yangjl/local/github/yangjl/knowledge-base
git log --oneline -25
```

Expected: 看到一连串本次改造的 commits（按 Task 1-19 顺序）。

- [ ] **Step 2: 检查无未提交改动**

Run:
```bash
cd /Users/yangjl/local/github/yangjl/knowledge-base
git status
```

Expected: 无未提交改动（除 docs/images/ 等已存在的 untracked 目录）。

- [ ] **Step 3: 推送到 origin（可选，待用户授权）**

⚠️ **本步骤需要用户授权后才执行**。如用户授权：

```bash
cd /Users/yangjl/local/github/yangjl/knowledge-base
git push origin main
```

---

## 失败回滚

如任何 Task 出现不可恢复的问题：

```bash
cd /Users/yangjl/local/github/yangjl/knowledge-base
git log --oneline -25   # 找到本次改造最早的 commit
git revert <earliest-commit-sha>..HEAD   # 整体回滚
```

或单文件回滚到改造前（每个 Task 都有独立 commit，单文件 `git checkout HEAD~N -- <file>` 即可）。
