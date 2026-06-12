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
      name: '知识库管理',
      routes: [
        {
          path: '/',
          redirect: '/chat',
        },
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
