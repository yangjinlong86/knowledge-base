package org.nodoer.system.service.ai;

import com.baomidou.mybatisplus.extension.service.IService;
import org.nodoer.system.model.entity.ai.ChatMessage;
import org.springframework.ai.chat.messages.Message;

import java.util.List;

/**
 * 对话消息服务：负责 {@code chat_message} 表实体与 Spring AI {@link Message} 之间的双向转换。
 *
 * <p>
 * 主要使用方是 {@link org.nodoer.system.memory.DatabaseChatMemory}：
 * <ul>
 * <li>{@link #toMessage(List)}：从数据库读历史时，把行记录还原成 user/system/assistant Message， 同时把
 * resourceIds 还原成 {@link org.springframework.ai.content.Media}（多模态附件）。</li>
 * <li>{@link #fromMessage(List)}：当前未实现，预留给"从 Spring AI Message 反向构造 ChatMessage"的场景。</li>
 * </ul>
 */
public interface ChatMessageService extends IService<ChatMessage> {

	/**
	 * 把数据库中的 ChatMessage 实体转换成 Spring AI 的 {@link Message}。
	 *
	 * <p>
	 * 实现会按 {@code messageNo} 升序排序后再转换，保证回灌给 LLM 的历史顺序与原始对话顺序一致。 多模态消息（{@code role=user}
	 * 且带 resourceIds）会顺带把 Media 还原回去。
	 * @param chatMessages 待转换的实体列表
	 * @return Spring AI 的 Message 列表（顺序与原始对话保持一致）
	 */
	List<Message> toMessage(List<ChatMessage> chatMessages);

	/**
	 * 反向转换：Spring AI Message → ChatMessage 实体。当前未实现，返回空列表。
	 * @param messages 待转换的 Message
	 * @return ChatMessage 列表
	 */
	List<ChatMessage> fromMessage(List<Message> messages);

}
