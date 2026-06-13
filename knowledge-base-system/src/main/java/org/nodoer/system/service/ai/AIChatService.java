package org.nodoer.system.service.ai;

import org.nodoer.system.controller.vo.ChatMessageVO;
import org.nodoer.system.controller.vo.ChatRequestVO;
import org.nodoer.system.model.entity.ai.ChatMessage;
import org.springframework.ai.chat.model.ChatResponse;
import reactor.core.publisher.Flux;

import java.util.List;

/**
 * AI 对话服务接口。
 *
 * <p>
 * 对外只暴露 5 个方法：4 种具体对话通路（普通 / 普通 RAG / 多模态 / 多模态 RAG）以及一个聚合分发的统一入口
 * {@link #unifyChat(ChatRequestVO)}。Controller 层只调用 {@link #unifyChat}，前 4 个方法是为了让分发逻辑
 * 更清晰、也方便被单测直接覆盖。
 *
 * <p>
 * 所有方法都返回 {@link Flux}<{@link ChatResponse}> 的流式结果，由 {@code AIChatController} 直接以 SSE
 * 形式回传给前端，做到边生成边渲染。
 *
 * @see org.nodoer.system.service.ai.impl.AIChatServiceImpl 实现细节与流程注释
 */
public interface AIChatService {

	/**
	 * 普通对话（不带向量检索、不带多模态附件）。
	 * @param chatMessageVO 用户消息（必须带 conversationId、content）
	 * @return 流式 ChatResponse，由 SSE 直推给前端
	 */
	Flux<ChatResponse> simpleChat(ChatMessageVO chatMessageVO);

	/**
	 * 多模态对话：附带图片/文档等 {@link org.springframework.ai.content.Media}。
	 * @param chatMessageVO 用户消息，{@code resourceIds} 必须能在 origin_file_source 表里查到
	 * @return 流式 ChatResponse
	 */
	Flux<ChatResponse> multimodalChat(ChatMessageVO chatMessageVO);

	/**
	 * 普通 RAG 对话：先在 pgvector 中按 {@code baseIds} 限定的知识库检索 topK 文档，再喂给 LLM。
	 * @param chatMessageVO 用户消息
	 * @param baseIds 允许检索的知识库 ID 列表；为空时强制返回 0 文档
	 * @return 流式 ChatResponse
	 */
	Flux<ChatResponse> simpleRAGChat(ChatMessageVO chatMessageVO, List<String> baseIds);

	/**
	 * 多模态 RAG 对话。
	 *
	 * <p>
	 * <b>注意</b>：当前实现返回 {@code null}，仅留位。详见
	 * {@link org.nodoer.system.service.ai.impl.AIChatServiceImpl#multimodalRAGChat}。
	 * @param chatMessageVO 用户消息（含附件）
	 * @param baseIds 允许检索的知识库 ID 列表
	 * @return 暂未实现，返回 {@code null}
	 */
	Flux<ChatResponse> multimodalRAGChat(ChatMessageVO chatMessageVO, List<String> baseIds);

	/**
	 * 统一对话分发入口。Controller 唯一调用点。
	 *
	 * <p>
	 * 根据 {@link ChatRequestVO#getChatType()} 选择上面 4 个具体方法之一；类型映射见
	 * {@link org.nodoer.system.model.enums.ChatType}。
	 * @param chatRequestVO 完整请求体
	 * @return 流式 ChatResponse
	 */
	Flux<ChatResponse> unifyChat(ChatRequestVO chatRequestVO);

}
