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
