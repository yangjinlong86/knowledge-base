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
