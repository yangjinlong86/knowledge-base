package org.nodoer.system.config;

import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.ollama.OllamaEmbeddingModel;
import org.springframework.ai.ollama.api.OllamaApi;
import org.springframework.ai.ollama.api.OllamaOptions;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 集中构建并暴露 4 个 AI 模型 Bean。
 *
 * <p>
 * 设计动机：
 * </p>
 * <ul>
 * <li>项目同时引入 spring-ai-starter-model-openai 与 spring-ai-starter-model-ollama，并通过
 * {@code spring.ai.model.*=none} 关闭所有自动装配（避免 Bean 冲突）。</li>
 * <li>所有 ChatModel / EmbeddingModel 都需要 builder 手构建，配置全部来自 {@code llm.yml}（开发环境可用
 * {@code llm-dev.yml} 覆盖）。</li>
 * <li>原 LLMServiceImpl 内部 build 的模型没有暴露成容器 bean，导致 {@code PgVectorStoreAutoConfiguration}
 * 在启动期 找不到 EmbeddingModel 注入目标。本类显式用 {@code @Bean} 暴露，让 Spring 容器能正确注入到 pgvector
 * 等下游消费者。</li>
 * <li>LLMServiceImpl 改为注入这 4 个 bean（门面模式），自身不再持有 @Value 配置字段。</li>
 * </ul>
 *
 * <p>
 * 设计文档：docs/superpowers/specs/2026-06-12-dual-model-openai-ollama-design.md
 * </p>
 */
@Configuration
public class LLMConfig {

	// ====== chat.simple.* ：通用对话模型配置 ======

	@Value("${chat.simple.base-url}")
	private String simpleBaseUrl;

	@Value("${chat.simple.api-key}")
	private String simpleApiKey;

	@Value("${chat.simple.model}")
	private String simpleModel;

	// ====== chat.long.* ：超长上下文对话模型配置 ======

	@Value("${chat.long.base-url}")
	private String longBaseUrl;

	@Value("${chat.long.api-key}")
	private String longApiKey;

	@Value("${chat.long.model}")
	private String longModel;

	// ====== chat.multimodal.* ：多模态对话模型配置 ======

	@Value("${chat.multimodal.base-url}")
	private String multimodalBaseUrl;

	@Value("${chat.multimodal.api-key}")
	private String multimodalApiKey;

	@Value("${chat.multimodal.model}")
	private String multimodalModel;

	// ====== embedding.* ：向量化模型配置（本地 Ollama） ======

	@Value("${embedding.base-url}")
	private String embeddingBaseUrl;

	@Value("${embedding.model}")
	private String embeddingModel;

	/**
	 * 通用对话模型 Bean。 走 OpenAI 协议（经 One-API 路由），配置来自 {@code llm.yml} 的
	 * {@code chat.simple.*}。
	 */
	@Bean
	public ChatModel simpleChatModel() {
		return buildOpenAiChatModel(simpleBaseUrl, simpleApiKey, simpleModel);
	}

	/**
	 * 超长上下文对话模型 Bean。 走 OpenAI 协议，配置来自 {@code chat.long.*}。 为长上下文场景预留的扩展位点。
	 */
	@Bean
	public ChatModel longContextChatModel() {
		return buildOpenAiChatModel(longBaseUrl, longApiKey, longModel);
	}

	/**
	 * 多模态对话模型 Bean。 走 OpenAI 协议，配置来自 {@code chat.multimodal.*}。 由
	 * AIChatServiceImpl#multimodalChat 使用。
	 */
	@Bean
	public ChatModel multimodalChatModel() {
		return buildOpenAiChatModel(multimodalBaseUrl, multimodalApiKey, multimodalModel);
	}

	/**
	 * 向量化模型 Bean。 走本地 Ollama（默认 bge-m3），配置来自 {@code embedding.*}。 也供 PgVectorStore
	 * 自动装配注入。
	 */
	@Bean
	public EmbeddingModel embeddingModel() {
		OllamaApi ollamaApi = OllamaApi.builder().baseUrl(embeddingBaseUrl).build();
		return OllamaEmbeddingModel.builder()
			.ollamaApi(ollamaApi)
			.defaultOptions(OllamaOptions.builder().model(embeddingModel).build())
			.build();
	}

	/**
	 * 构建一个走 OpenAI 协议（经 One-API 路由）的 ChatModel。 三个 ChatModel Bean 都委托到这里，差异只剩 base-url /
	 * api-key / model 三组配置。
	 * @param baseUrl OpenAI 兼容端点
	 * @param apiKey 对应端点的 API key
	 * @param model 要调用的具体模型名
	 * @return 新构建的 {@link OpenAiChatModel} 实例
	 */
	private OpenAiChatModel buildOpenAiChatModel(String baseUrl, String apiKey, String model) {
		OpenAiApi openAiApi = OpenAiApi.builder().baseUrl(baseUrl).apiKey(apiKey).build();
		return OpenAiChatModel.builder()
			.openAiApi(openAiApi)
			.defaultOptions(OpenAiChatOptions.builder().model(model).build())
			.build();
	}

}
