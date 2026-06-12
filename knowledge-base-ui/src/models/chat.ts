import { useState } from 'react';
import { ChatSetting } from './types';

const useChat = () => {
  const [curConversationId, setCurConverstationId] = useState<string>('');
  const [chatSetting, setChatSetting] = useState<ChatSetting>({
    chatType: 'simpleRAG',
    knowledgeIds: ['c3ae48c5751e2b80a59b98bce4170064'],
  });

  return {
    curConversationId,
    setCurConverstationId,
    chatSetting,
    setChatSetting,
  };
};

export default useChat;
