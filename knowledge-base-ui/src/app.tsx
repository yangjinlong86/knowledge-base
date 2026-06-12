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
    layout: 'side',
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
