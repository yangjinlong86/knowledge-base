import { defineConfig } from '@umijs/max';

export default defineConfig({
  plugins: ['@umijs/max-plugin-openapi'],
  antd: {
    dark: true,
    theme: {
      token: {
        colorPrimary: '#38d7ff',
        colorInfo: '#1677ff',
        colorSuccess: '#21f6bc',
        colorWarning: '#f7c948',
        colorError: '#ff4d7d',
        colorBgBase: '#030712',
        colorBgContainer: 'rgba(8, 24, 52, 0.78)',
        colorBgElevated: 'rgba(10, 32, 68, 0.92)',
        colorBorder: 'rgba(75, 205, 255, 0.28)',
        colorText: 'rgba(242, 250, 255, 0.96)',
        colorTextSecondary: 'rgba(200, 230, 255, 0.78)',
        colorTextTertiary: 'rgba(170, 211, 255, 0.68)',
        colorTextQuaternary: 'rgba(145, 190, 235, 0.56)',
        borderRadius: 14,
        wireframe: false,
      },
      components: {
        Button: {
          borderRadius: 18,
          controlHeight: 36,
        },
        Menu: {
          itemBg: 'transparent',
          itemColor: 'rgba(218, 240, 255, 0.84)',
          itemHoverBg: 'rgba(56, 215, 255, 0.12)',
          itemHoverColor: '#ffffff',
          itemSelectedBg: 'rgba(56, 215, 255, 0.24)',
          itemSelectedColor: '#ffffff',
          itemActiveBg: 'rgba(56, 215, 255, 0.16)',
        },
        Select: {
          optionSelectedBg: 'rgba(56, 215, 255, 0.22)',
          optionSelectedColor: '#ffffff',
          optionActiveBg: 'rgba(56, 215, 255, 0.12)',
        },
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
    },
    // {
    //   path: '/home',
    //   name: '首页',
    //   title: '首页',
    //   component: '@/pages/Home',
    //   icon: 'HomeOutlined',
    // },
    {
      path: '/chat',
      name: 'AI对话',
      title: 'AI对话',
      component: '@/pages/Chat',
      icon: 'RobotOutlined',
    },
    {
      path: '/knowlegeBase',
      name: '知识库',
      title: '知识库',
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

    { path: '/*', component: '@/pages/404' },
  ],
  npmClient: 'pnpm',
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
      schemaPath: `http://localhost:8788/api/v3/api-docs/default`, // openapi 接口地址
      mock: false,
      apiPrefix() {
        return "'/api'";
      },
    },
  ],
});
