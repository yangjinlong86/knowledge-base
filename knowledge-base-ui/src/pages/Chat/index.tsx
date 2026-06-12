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
