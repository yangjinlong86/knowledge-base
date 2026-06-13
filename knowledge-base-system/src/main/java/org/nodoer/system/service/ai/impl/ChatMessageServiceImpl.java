package org.nodoer.system.service.ai.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.nodoer.core.common.CoreCode;
import org.nodoer.core.exception.BusinessException;
import org.nodoer.system.mapper.ChatMessageMapper;
import org.nodoer.system.model.entity.ai.ChatMessage;
import org.nodoer.system.service.ai.ChatMessageService;
import org.nodoer.system.service.ai.OriginFileResourceService;
import org.springframework.ai.chat.messages.*;
import org.springframework.stereotype.Service;

import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * 对话消息服务实现：表 {@code chat_message} 与 Spring AI {@link Message} 之间的转换器。
 *
 * <p>
 * 配合 {@link org.nodoer.system.memory.DatabaseChatMemory} 使用：当 ChatMemory 把行记录读出来后， 由
 * {@link #toMessage(List)} 还原成 Spring AI 能识别的消息对象（包括多模态 Media），再回灌进 LLM 上下文。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ChatMessageServiceImpl extends ServiceImpl<ChatMessageMapper, ChatMessage> implements ChatMessageService {

	/** 多模态附件服务：仅在消息含 resourceIds 时使用，把资源 id 还原为 Media（含字节内容 + mime 类型）。 */
	private final OriginFileResourceService originFileResourceService;

	/**
	 * ChatMessage → Spring AI Message。
	 *
	 * <p>
	 * 步骤：
	 * <ol>
	 * <li>按 {@code messageNo} 升序排序，保证 user → assistant → user → assistant ... 的轮序。</li>
	 * <li>根据 {@code role} 字段（user / system / assistant）分别构造对应的 Message 子类。</li>
	 * <li>对 user 与 assistant 消息，调用 {@link OriginFileResourceService#fromResourceId(List)}
	 * 把 resourceIds 还原回 Media 列表挂上去；纯文本消息时该方法直接返回空列表。</li>
	 * <li>未识别的 role 抛 {@link BusinessException}，避免脏数据被静默忽略。</li>
	 * </ol>
	 *
	 * <p>
	 * 这里 user 消息使用了 {@code mutate().media(...)}：Spring AI 的 UserMessage 是不可变对象， mutate
	 * 会基于已有实例派生一个新实例并替换 media 字段。assistant 消息则因为构造函数本身支持 media 参数，直接传入。
	 */
	@Override
	public List<Message> toMessage(List<ChatMessage> chatMessages) {
		// 按 messageNo 从低到高排序，保证回灌顺序与原始对话顺序一致
		chatMessages.sort(Comparator.comparingInt(ChatMessage::getMessageNo));

		return chatMessages.stream().map(chatMessage -> {
			String role = chatMessage.getRole().toLowerCase();

			// 根据角色构造对应类型的 Spring AI Message
			Message message = switch (role) {
				case "user" -> {
					// user 消息：先用文本构造，再 mutate 进 Media（mutate 不修改原对象，返回新对象）
					UserMessage userMessage = new UserMessage(chatMessage.getContent());
					userMessage.mutate().media(originFileResourceService.fromResourceId(chatMessage.getResourceIds()));
					yield userMessage;
				}
				case "system" -> new SystemMessage(chatMessage.getContent());
				case "assistant" ->
					// assistant 消息构造器签名：text / properties / toolCalls / media
					new AssistantMessage(chatMessage.getContent(), Map.of(), List.of(),
							originFileResourceService.fromResourceId(chatMessage.getResourceIds()));
				default -> throw new BusinessException(CoreCode.SYSTEM_ERROR, "未知消息类型");
			};
			return message;
		}).toList();
	}

	/**
	 * Spring AI Message → ChatMessage。当前未实现（DatabaseChatMemory.add 直接构造实体，不依赖此方法）。
	 */
	@Override
	public List<ChatMessage> fromMessage(List<Message> messages) {
		return List.of();
	}

}
