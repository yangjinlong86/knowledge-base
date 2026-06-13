package org.nodoer.system.service.ai.impl;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.nodoer.core.common.CoreCode;
import org.nodoer.core.exception.BusinessException;
import org.nodoer.system.controller.vo.ChatMessageVO;
import org.nodoer.system.controller.vo.ChatRequestVO;
import org.nodoer.system.memory.DatabaseChatMemory;
import org.nodoer.system.model.entity.user.SystemUser;
import org.nodoer.system.model.enums.ChatType;
import org.nodoer.system.service.ai.*;
import org.nodoer.system.utils.SecurityFrameworkUtil;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.client.advisor.SimpleLoggerAdvisor;
import org.springframework.ai.chat.client.advisor.vectorstore.QuestionAnswerAdvisor;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.chat.prompt.PromptTemplate;
import org.springframework.ai.content.Media;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

import java.util.HashMap;
import java.util.List;

import static org.nodoer.system.constant.AppConstant.*;

/**
 * AI 对话服务实现：负责把上层传入的对话请求路由到具体的对话通路（普通 / RAG / 多模态 / 多模态 RAG）， 并以 {@link Flux} 形式返回 SSE
 * 流式响应。
 *
 * <p>
 * <b>整体职责</b>：
 * <ul>
 * <li>从 {@link LLMService} 拿到对应场景的 {@link ChatModel}（在 {@code llm.yml} 中按场景独立配置）。</li>
 * <li>构造 {@link ChatClient}，按需挂载三类 advisor：日志、对话记忆、向量检索（RAG）。</li>
 * <li>把请求中的多模态附件（资源 id）转换成 Spring AI 的 {@link Media} 挂到 {@link UserMessage} 上。</li>
 * <li>向量检索的过滤条件由 {@link #buildBaseAccessFilter(List)} 拼装，限制检索到当前会话允许访问的知识库。</li>
 * </ul>
 *
 * <p>
 * <b>调用入口</b>：{@code AIChatController#unifyChat} → {@link #unifyChat(ChatRequestVO)} → 按
 * {@link ChatType} 分发到下面四个方法。返回的 {@link Flux} 会被 controller 直接写到
 * {@code text/event-stream} 响应体里。
 *
 * <p>
 * <b>历史消息持久化</b>：所有对话通路都会挂 {@link MessageChatMemoryAdvisor}（绑定
 * {@link DatabaseChatMemory}）， 由它在每一轮请求/响应前后自动调用 {@code chat_message} 表的读写。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AIChatServiceImpl implements AIChatService {

	/** LLM / 向量库的统一门面。这里只取需要的 ChatModel 与 VectorStore，不关心模型 builder 细节。 */
	private final LLMService llmService;

	/** 多模态附件服务：把前端传上来的 resourceIds 还原成可投喂给 LLM 的 {@link Media} 列表。 */
	private final OriginFileResourceService originFileResourceService;

	/**
	 * 自定义的 Spring AI {@code ChatMemory} 实现，把对话历史落库到 {@code chat_message} 表。 通过
	 * {@link MessageChatMemoryAdvisor} 与 {@link ChatClient} 串联使用。
	 */
	private final DatabaseChatMemory databaseChatMemory;

	/**
	 * RAG 系统提示词模板。{@code prompt/RAG.txt} 中包含一个占位符（默认 {@code question_answer_context}）， 由
	 * {@link QuestionAnswerAdvisor} 在运行时注入向量检索得到的文档片段。
	 */
	@Value("classpath:prompt/RAG.txt")
	private Resource ragPromptResource;

	/**
	 * 普通对话（无 RAG、无多模态）。
	 *
	 * <p>
	 * 流程：
	 * <ol>
	 * <li>从 {@link LLMService} 取 {@code chat.simple.*} 配置对应的通用 ChatModel。</li>
	 * <li>把 conversationId 写到 message metadata，{@link DatabaseChatMemory#add}
	 * 会用它来确定写哪一行。</li>
	 * <li>构造 {@link ChatClient}，挂载 {@link MessageChatMemoryAdvisor}：advisor 会先
	 * {@code get(conversationId)} 把历史读出来塞进 prompt，再在响应结束后把 user / assistant 两条消息持久化。</li>
	 * <li>{@code stream().chatResponse()} 返回完整 {@link ChatResponse} 的 Flux，便于 SSE
	 * 增量下发。</li>
	 * </ol>
	 */
	@Override
	public Flux<ChatResponse> simpleChat(ChatMessageVO chatMessageVO) {
		// 通用对话：走 llm.yml chat.simple.* 配置的 OpenAI 协议模型
		ChatModel chatModel = llmService.getChatModel();
		// 构建消息元数据：conversationId 必须随消息一起带下去，DatabaseChatMemory.add() 会从 metadata 里读它
		HashMap<String, Object> params = new HashMap<>();
		params.put(CHAT_CONVERSATION_NAME, chatMessageVO.getConversationId());
		UserMessage userMessage = UserMessage.builder().text(chatMessageVO.getContent()).metadata(params).build();
		ChatClient chatClient = ChatClient.builder(chatModel).build();
		return chatClient.prompt(Prompt.builder().messages(userMessage).build())
			// 对话记忆 advisor：自动读历史 + 自动落库；conversationId 是分组键
			.advisors(MessageChatMemoryAdvisor.builder(databaseChatMemory)
				.conversationId(chatMessageVO.getConversationId())
				.build())
			.stream()
			.chatResponse();
	}

	/**
	 * 多模态对话：在普通对话的基础上挂载一组 {@link Media}（图片 / 文档）。
	 *
	 * <p>
	 * 关键点：
	 * <ul>
	 * <li>使用 {@code chat.multimodal.*} 模型（视觉/多模态能力的模型，例如 GPT-4o、qwen-vl 等）。</li>
	 * <li>{@code resourceIds} 通过 {@link OriginFileResourceService#fromResourceId(List)}
	 * 转成 {@link Media}： 该方法内部会从 MinIO 拉文件、嗅探 mime 类型，再封装为 {@link Media}。</li>
	 * <li>{@code CHAT_MEDIAS} 也写进 metadata，是为了让 {@link DatabaseChatMemory#add} 把
	 * {@code resourceIds} 落库到 {@code chat_message.resource_ids}（下一轮历史回放时还能恢复出
	 * Media）。</li>
	 * </ul>
	 */
	@Override
	public Flux<ChatResponse> multimodalChat(ChatMessageVO chatMessageVO) {
		// 多模态对话：走 llm.yml chat.multimodal.* 配置的 OpenAI 协议模型
		ChatModel chatModel = llmService.getMultimodalChatModel();
		List<String> resourceIds = chatMessageVO.getResourceIds();
		ChatClient chatClient = ChatClient.builder(chatModel).build();
		// 把 conversationId 与 resourceIds 都放进 metadata，DatabaseChatMemory 落库时会读这两个 key
		HashMap<String, Object> params = new HashMap<>();
		params.put(CHAT_CONVERSATION_NAME, chatMessageVO.getConversationId());
		params.put(CHAT_MEDIAS, chatMessageVO.getResourceIds());
		UserMessage.Builder userMessageBuilder = UserMessage.builder()
			.text(chatMessageVO.getContent())
			.metadata(params);
		// 仅当本轮带了附件才需要把字节内容拉下来挂上去；纯文本场景直接跳过
		if (resourceIds != null && !resourceIds.isEmpty()) {
			List<Media> medias = originFileResourceService.fromResourceId(resourceIds);
			userMessageBuilder.media(medias.toArray(new Media[0]));
		}
		UserMessage userMessage = userMessageBuilder.build();

		return chatClient.prompt(Prompt.builder().messages(userMessage).build())
			.advisors(
					// 简单日志 advisor：把组装后的 prompt / 响应打印到日志，便于排查多模态问题
					new SimpleLoggerAdvisor(),
					// 对话记忆 advisor：与普通对话语义相同，区别是历史里也会带上 resource_ids
					MessageChatMemoryAdvisor.builder(databaseChatMemory)
						.conversationId(chatMessageVO.getConversationId())
						.build())
			.stream()
			.chatResponse();
	}

	/**
	 * 简单 RAG 对话：在普通对话基础上加一层向量检索（基于 pgvector）。
	 *
	 * <p>
	 * 关键设计：
	 * <ul>
	 * <li><b>检索范围隔离</b>：通过 {@link #buildBaseAccessFilter(List)} 生成
	 * {@code knowledge_base_id in [...]} 过滤表达式，防止跨知识库泄漏。</li>
	 * <li><b>topK</b>：固定为 {@link org.nodoer.system.constant.AppConstant#RAG_TOP_K}（默认 5），
	 * 取前 5 段最相关的文档片段塞进 RAG 模板。</li>
	 * <li><b>advisor 顺序非常关键</b>：{@link MessageChatMemoryAdvisor} 必须排在
	 * {@link QuestionAnswerAdvisor} <i>前面</i>。原因见下方注释 —— RAG advisor 会重写 user 消息文本，
	 * 把整段指令模板 + 检索结果拼到 user.text，如果记忆 advisor 在它后面跑，落库的就是被污染过的消息， 之后的多轮对话就会出现 "答非所问 /
	 * 历史里全是 RAG 模板" 的怪问题。</li>
	 * </ul>
	 */
	@Override
	public Flux<ChatResponse> simpleRAGChat(ChatMessageVO chatMessageVO, List<String> baseIds) {
		// RAG 对话同样使用通用对话模型；检索通路由下方 QuestionAnswerAdvisor 接管
		ChatModel chatModel = llmService.getChatModel();
		log.info("[DEBUG-BUG] simpleRAGChat enter convId={}, userText='{}', baseIds={}",
				chatMessageVO.getConversationId(), chatMessageVO.getContent(), baseIds);
		// 构造客户端 + RAG 提示词模板（占位符由 QuestionAnswerAdvisor 在运行时填入检索文档）
		ChatClient chatClient = ChatClient.builder(chatModel).build();
		PromptTemplate template = new PromptTemplate(ragPromptResource);

		// 向量检索请求：以用户原始问题为 query，限定知识库范围
		SearchRequest searchRequest = SearchRequest.builder()
			.topK(RAG_TOP_K)
			.query(chatMessageVO.getContent())
			.filterExpression(buildBaseAccessFilter(baseIds))
			.build();

		// 元数据只带 conversationId，RAG 路径不涉及多模态附件
		HashMap<String, Object> params = new HashMap<>();
		params.put(CHAT_CONVERSATION_NAME, chatMessageVO.getConversationId());
		UserMessage userMessage = UserMessage.builder().text(chatMessageVO.getContent()).metadata(params).build();

		return chatClient.prompt(Prompt.builder().messages(userMessage).build())
			.advisors(new SimpleLoggerAdvisor(),
					// 顺序很关键：MessageChatMemoryAdvisor 必须在 QuestionAnswerAdvisor 之前跑，
					// 否则它存进 chat_message 的就是被 RAG 模板增强过的 user 消息（augmentUserMessage 会把整段
					// 指令模板 + 检索文档追加到 user.text），导致后续轮次的历史里出现 RAG 污染、用户问题被模板
					// 稀释、LLM 倾向延续上一轮 assistant 而不是用 RAG 文档（Q2 答非所问就是这个原因）。
					MessageChatMemoryAdvisor.builder(databaseChatMemory)
						.conversationId(chatMessageVO.getConversationId())
						.build(),
					// QA advisor：在 prompt 真正发出去之前执行向量检索，把结果按 RAG 模板注入
					QuestionAnswerAdvisor.builder(llmService.getVectorStore())
						.promptTemplate(template)
						.searchRequest(searchRequest)
						.build())

			.stream()
			.chatResponse();
	}

	/**
	 * 多模态 RAG 对话。
	 *
	 * <p>
	 * <b>当前未实现</b>：返回 {@code null}。多模态场景下既要做向量检索又要带图，需要解决 "图片 query 如何 embed"、 "RAG
	 * 文档块要不要附图" 等问题，目前业务上还没有诉求，先留位。controller / 前端在调用前必须保证 chatType 不会落到这条分支。
	 */
	@Override
	public Flux<ChatResponse> multimodalRAGChat(ChatMessageVO chatMessageVO, List<String> baseIds) {
		return null;
	}

	/**
	 * 统一对话入口：根据 {@link ChatRequestVO#getChatType()} 把请求分发到上面四个具体方法之一。
	 *
	 * <p>
	 * 这里直接用 {@link BeanUtils#copyProperties} 把 {@link ChatRequestVO} 拷成
	 * {@link ChatMessageVO}， 因为下游方法只需要 {@code conversationId / content / resourceIds}
	 * 这几个字段。{@code knowledgeIds} 是 RAG 专属， 单独从 ChatRequestVO 上取。
	 *
	 * <p>
	 * 未匹配上的 chatType（包括前端传错和 {@link ChatType#UNKNOWN}）会抛 {@link BusinessException}， 由
	 * {@code GlobalExceptionHandler} 转成统一的错误响应。
	 */
	@Override
	public Flux<ChatResponse> unifyChat(ChatRequestVO chatRequestVO) {
		String chatType = chatRequestVO.getChatType();
		ChatMessageVO chatMessageVO = new ChatMessageVO();
		BeanUtils.copyProperties(chatRequestVO, chatMessageVO);
		ChatType type = ChatType.parse(chatType);
		return switch (type) {
			case SIMPLE -> this.simpleChat(chatMessageVO);
			case SIMPLE_RAG -> this.simpleRAGChat(chatMessageVO, chatRequestVO.getKnowledgeIds());
			case MULTIMODAL -> this.multimodalChat(chatMessageVO);
			case MULTIMODAL_RAG -> this.multimodalRAGChat(chatMessageVO, chatRequestVO.getKnowledgeIds());
			default -> throw new BusinessException(CoreCode.PARAMS_ERROR, "未知的对话类型");
		};
	}

	/**
	 * 拼装向量检索的 filter 表达式，限制只能命中给定的知识库 ID 集合。
	 *
	 * <p>
	 * 表达式形如：{@code knowledge_base_id in ["id1","id2"]}，会被 Spring AI 翻译成 pgvector 的 SQL
	 * where。 文档入库时（{@link OriginFileResourceServiceImpl#uploadFile(MultipartFile, String)
	 * uploadFile}) 在 metadata 里写了
	 * {@code user_id / knowledge_base_id / document_id}，这里就是查那一份 metadata。
	 *
	 * <p>
	 * <b>边界处理</b>：当传入的知识库列表为空时，故意构造一个不可能命中的占位值
	 * {@code knowledge_base_id in ["___empty___"]}，从而强制返回空结果集。这样上层逻辑无需写空判断，
	 * QuestionAnswerAdvisor 拿到 0 篇文档时仍会按 RAG 模板正常发 prompt，模型会回答 "未找到相关资料"。
	 *
	 * <p>
	 * <b>注意</b>：方法签名里有 {@code SystemUser user = ... getLoginUser()}，目前只为日志/未来扩展保留，
	 * 真正的访问校验在 controller 层完成（前端只能传当前用户能看到的 knowledgeIds）。
	 */
	// meta ==> { "user_id"、"knowledge_base_id"、"document_id"}
	private String buildBaseAccessFilter(List<String> knowledgeBaseIds) {
		SystemUser user = SecurityFrameworkUtil.getLoginUser();

		// 没有任何知识库 ID 时，返回一个不可能命中的占位过滤，等价于 "禁止检索任何文档"
		if (knowledgeBaseIds == null || knowledgeBaseIds.isEmpty()) {
			return "knowledge_base_id in [\"___empty___\"]"; // 不让查询任何知识库
		}
		// 拼接 in 列表：knowledge_base_id in ["id1","id2",...]
		StringBuilder sb = new StringBuilder();
		sb.append("knowledge_base_id in [");
		for (int i = 0; i < knowledgeBaseIds.size(); i++) {
			if (i != 0) {
				sb.append(",");
			}
			sb.append("\"").append(knowledgeBaseIds.get(i)).append("\"");
		}
		sb.append("]");
		log.info("Vector Search Filter SQL: {}", sb);
		log.info("Vector Search Filter Parameter: {}", knowledgeBaseIds);
		return sb.toString();
	}

}
