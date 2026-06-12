package org.nodoer.system.service.ai;

import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.VectorStore;

/**
 * LLM 与向量化能力的统一入口。
 *
 * <p>
 * 本项目同时引入了 spring-ai-starter-model-openai 与 spring-ai-starter-model-ollama 两个 starter，
 * 但通过 application.yml 中的 {@code spring.ai.model.chat=none} /
 * {@code spring.ai.model.embedding=none} 关闭了它们的自动装配。 所有 ChatModel / EmbeddingModel 都由实现类
 * (LLMServiceImpl) 在运行期按需 builder 构建， 配置来源是 classpath 下的 {@code llm.yml}（开发环境可用
 * {@code llm-dev.yml} 覆盖）。
 * </p>
 *
 * <p>
 * 设计文档：docs/superpowers/specs/2026-06-12-dual-model-openai-ollama-design.md
 * </p>
 */
public interface LLMService {

	/**
	 * 获取通用对话模型。 对应 llm.yml 中 {@code chat.simple.*} 配置，走 OpenAI 协议（经 One-API 路由）。
	 * @return 新构建的 ChatModel 实例
	 */
	ChatModel getChatModel();

	/**
	 * 获取超长上下文对话模型。 对应 llm.yml 中 {@code chat.long.*} 配置，走 OpenAI 协议（经 One-API 路由）。 为
	 * 长上下文场景预留的扩展位点。
	 * @return 新构建的 ChatModel 实例
	 */
	ChatModel getLongContextChatModel();

	/**
	 * 获取多模态对话模型。 对应 llm.yml 中 {@code chat.multimodal.*} 配置，走 OpenAI 协议（经 One-API 路由）。 由
	 * AIChatServiceImpl#multimodalChat 使用。
	 * @return 新构建的 ChatModel 实例
	 */
	ChatModel getMultimodalChatModel();

	/**
	 * 获取向量化（embedding）模型。 对应 llm.yml 中 {@code embedding.*} 配置，走本地 Ollama（默认 bge-m3）。
	 * @return 新构建的 EmbeddingModel 实例
	 */
	EmbeddingModel getEmbeddingModel();

	/**
	 * 获取向量存储对象（pgvector）。 内部使用 {@link #getEmbeddingModel()} 作为 embedding 提供方。
	 * @return PgVectorStore 实例
	 */
	VectorStore getVectorStore();

}
