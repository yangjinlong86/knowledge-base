package org.nodoer.system.memory;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.nodoer.system.constant.AppConstant;
import org.nodoer.system.mapper.ChatConversationMapper;
import org.nodoer.system.mapper.ChatMessageMapper;
import org.nodoer.system.model.entity.ai.ChatMessage;
import org.nodoer.system.service.ai.ChatMessageService;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.content.Content;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;

import static org.nodoer.system.constant.AppConstant.CHAT_MAX_LENGTH;
import static org.springframework.ai.chat.messages.AbstractMessage.MESSAGE_TYPE;

/**
 * 自定义的 Spring AI {@link ChatMemory} 实现：把对话历史持久化到 PostgreSQL 的 {@code chat_message} 表， 让
 * {@link org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor}
 * 能够跨进程、跨会话恢复历史。
 *
 * <p>
 * <b>角色定位</b>：在 Spring AI 的 ChatClient 链路中，{@code MessageChatMemoryAdvisor} 会在每一轮请求前调用
 * {@link #get(String)} 读历史，在响应结束后调用 {@link #add(String, List)} 把 user 与 assistant
 * 两条消息一起落库。 本类就是这两个钩子的实现。
 *
 * <p>
 * <b>窗口大小</b>：{@link #maxMessages} 直接复用 {@link AppConstant#CHAT_MAX_LENGTH}（默认 20 条）。
 * 超过窗口的旧消息不会从库里删，只是在读取时不再被加载到 prompt。
 *
 * <p>
 * <b>多模态支持</b>：消息 metadata 里如果带 {@code CHAT_MEDIAS}（resourceIds 列表），会被一并写入
 * {@code chat_message.has_media} 与 {@code chat_message.resource_ids}，下次 {@code get()} 时
 * {@link ChatMessageService#toMessage(List)} 会借此还原 {@code Media} 列表。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DatabaseChatMemory implements ChatMemory {

	/** MyBatis-Plus 直接操作 chat_message 表（增删查）。 */
	private final ChatMessageMapper chatMessageMapper;

	/** 用于把 ChatMessage 实体转换回 Spring AI 的 Message（含多模态 Media 还原）。 */
	private final ChatMessageService chatMessageService;

	/** 窗口大小（最多回灌多少条历史给 LLM）。固定 20，超出部分不参与 prompt 构建。 */
	private final int maxMessages = CHAT_MAX_LENGTH;

	/**
	 * 读历史：按 {@code conversationId} 取出最早的 N 条，按时间升序返回。
	 *
	 * <p>
	 * MessageChatMemoryAdvisor 拿到这些 Message 后，会原样插入 prompt 的开头（在系统提示词之后、当前 user 消息之前），
	 * 模型由此感知"上下文"。这里用 {@code orderByAsc(create_time) + LIMIT} 取前 N 条，等价于"取最早的一段历史"，
	 * 而不是滑动窗口的"最近 N 条"——这是当前实现的一个特点，后续如果需要"滑动窗口最近 20 条"再调整。
	 */
	@Override
	public List<Message> get(String conversationId) {
		LambdaQueryWrapper<ChatMessage> qw = new LambdaQueryWrapper<>();
		qw.eq(ChatMessage::getConversationId, conversationId);
		qw.orderByAsc(ChatMessage::getCreateTime);
		qw.last(" LIMIT " + maxMessages);
		List<ChatMessage> chatMessages = chatMessageMapper.selectList(qw);
		// 调试日志：打印本次回灌给 LLM 的历史摘要（每条仅截前 30 字符），便于排查 RAG 历史污染等问题
		log.info("[DEBUG-BUG] DatabaseChatMemory.get() convId={}, count={}, history={}", conversationId,
				chatMessages.size(),
				chatMessages.stream()
					.map(m -> "[" + m.getMessageNo() + "," + m.getRole() + ","
							+ (m.getContent() == null ? "null"
									: m.getContent().substring(0, Math.min(30, m.getContent().length())))
							+ "]")
					.toList());
		// ChatMessage 实体 → Spring AI Message（user/system/assistant + 还原 Media）
		return chatMessageService.toMessage(chatMessages);
	}

	/**
	 * 写历史：把本轮的 user 消息与 assistant 消息一起持久化到 {@code chat_message} 表。
	 *
	 * <p>
	 * Spring AI 的约定：advisor 会在响应结束后一次性调用
	 * {@code add(conversationId, [userMsg, assistantMsg])}， 因此入参 {@code messages} 通常长度为
	 * 2，索引 0 是 user，索引 1 是 assistant。
	 *
	 * <p>
	 * 这里给每条消息分配 {@code messageNo = i + 1}（从 1 开始的轮内序号）；真正全局的"第几轮"由数据库 create_time 排序决定。
	 *
	 * <p>
	 * 元数据来源：
	 * <ul>
	 * <li>{@link org.springframework.ai.chat.messages.AbstractMessage#MESSAGE_TYPE} 由
	 * Spring AI 内部写入， 取值是 user / system / assistant 等枚举名。</li>
	 * <li>{@link AppConstant#CHAT_MEDIAS} 是本项目自定义，只有多模态对话路径会塞 resourceIds 进来。</li>
	 * </ul>
	 *
	 * <p>
	 * 事务保护：批量 insert 出错时整体回滚，避免出现 "user 落库成功、assistant 失败" 的半成品历史。
	 */
	@Transactional(rollbackFor = Exception.class)
	@Override
	public void add(String conversationId, List<Message> messages) {
		// log.info("Save Meta:{}", messages.stream().map(Content::getMetadata).toList());
		log.info("[DEBUG-BUG] DatabaseChatMemory.add() convId={}, messages.size()={}, types={}", conversationId,
				messages.size(), messages.stream().map(m -> m.getMetadata().get(MESSAGE_TYPE).toString()).toList());
		ArrayList<ChatMessage> chatMessageList = new ArrayList<>();
		for (int i = 0; i < messages.size(); i++) {
			Message message = messages.get(i);
			ChatMessage chatMessage = new ChatMessage();
			chatMessage.setConversationId(conversationId);
			// 轮内顺序号：user=1, assistant=2（Spring AI 通常一次性提交这两条）
			chatMessage.setMessageNo(i + 1);
			chatMessage.setContent(message.getText());
			// 角色：从 Spring AI 内部 metadata 读出 USER / ASSISTANT / SYSTEM
			chatMessage.setRole(message.getMetadata().get(MESSAGE_TYPE).toString());
			// 多模态附件：仅当 user 消息带了 CHAT_MEDIAS 才会有值
			List<String> resourceIds = (List) message.getMetadata().get(AppConstant.CHAT_MEDIAS);
			if (resourceIds != null && !resourceIds.isEmpty()) {
				chatMessage.setHasMedia(true);
				chatMessage.setResourceIds(resourceIds);
			}
			else {
				// 显式置空：避免历史回放时把上一轮的 resourceIds 带过来
				chatMessage.setHasMedia(false);
				chatMessage.setResourceIds(List.of());
			}
			chatMessageList.add(chatMessage);
		}
		// MyBatis-Plus 的 insert(List) 会走 batch 拼装，单语句多 values
		chatMessageMapper.insert(chatMessageList);

	}

	/**
	 * 清空指定会话的全部历史。
	 *
	 * <p>
	 * 仅在用户主动"删除会话/重置上下文"等管理类操作时调用，普通对话路径不会触发。物理删除（非软删除）， 因为 Spring AI 的 ChatMemory 接口语义就是
	 * "from now on this conversation has no history"。
	 */
	@Transactional(rollbackFor = Exception.class)
	@Override
	public void clear(String conversationId) {
		LambdaQueryWrapper<ChatMessage> qw = new LambdaQueryWrapper<>();
		qw.eq(ChatMessage::getConversationId, conversationId);
		chatMessageMapper.delete(qw);
	}

}
