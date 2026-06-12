package org.nodoer.system.service.ai.impl;

import lombok.RequiredArgsConstructor;
import org.nodoer.system.service.ai.LLMService;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.ollama.OllamaEmbeddingModel;
import org.springframework.ai.ollama.api.OllamaApi;
import org.springframework.ai.ollama.api.OllamaOptions;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.pgvector.PgVectorStore;
import org.springframework.ai.vectorstore.pgvector.autoconfigure.PgVectorStoreProperties;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * {@link LLMService} 的默认实现。
 *
 * <p>
 * 设计要点：
 * </p>
 * <ol>
 * <li>三个 ChatModel（simple / long / multimodal）统一走 OpenAI 协议，经
 * <a href="https://github.com/songquanpeng/one-api">One-API</a> 路由到任意 OpenAI 兼容后端， 配置分别来自
 * {@code llm.yml} 的 {@code chat.simple.*} / {@code chat.long.*} /
 * {@code chat.multimodal.*}。</li>
 * <li>EmbeddingModel 走本地 Ollama（默认 {@code bge-m3}），配置来自 {@code embedding.*}。</li>
 * <li>所有模型都"按需构建"——每次调用 getter 都新建一个实例。 builder 本身开销很小，且底层 HTTP client 在 OpenAiApi /
 * OllamaApi 内部惰性创建；如未来出现性能瓶颈再考虑缓存。 注意 {@link #getVectorStore()} 内部会顺带重建
 * {@link EmbeddingModel} 与 {@link PgVectorStore}，重建 PgVectorStore 不便宜（持有
 * JdbcTemplate、初始化阶段会建表）， 调用方应自行缓存（{@code AIChatServiceImpl} 是 {@code @Service} 单例，理想做法是把
 * VectorStore 提升为字段）。</li>
 * <li>之所以全部手动 build 而不是依赖 starter 自动装配： 项目里有三套独立的 chat 配置（base-url / api-key / model 各异），
 * Spring AI 的自动装配每个 provider 只能产出一个 ChatModel Bean，表达不了这种 "1 个 provider × 多套配置" 的需求。 因此在
 * application.yml 里把 chat / embedding 的自动装配关掉（{@code spring.ai.model.chat=none} 与
 * {@code spring.ai.model.embedding=none}），手工接管。</li>
 * </ol>
 */
@Service
@RequiredArgsConstructor
public class LLMServiceImpl implements LLMService {

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

	/** PgVectorStore 需要的 JdbcTemplate，由 Spring Boot 自动注入。 */
	private final JdbcTemplate jdbcTemplate;

	/** pgvector 自动装配读到的属性（表名 / 维度 / 距离类型等），转交给手动 build 的 PgVectorStore。 */
	private final PgVectorStoreProperties pgVectorStoreProperties;

	@Override
	public ChatModel getChatModel() {
		return buildOpenAiChatModel(simpleBaseUrl, simpleApiKey, simpleModel);
	}

	@Override
	public ChatModel getLongContextChatModel() {
		return buildOpenAiChatModel(longBaseUrl, longApiKey, longModel);
	}

	@Override
	public ChatModel getMultimodalChatModel() {
		return buildOpenAiChatModel(multimodalBaseUrl, multimodalApiKey, multimodalModel);
	}

	/**
	 * 构建一个走 OpenAI 协议（经 One-API 路由）的 ChatModel。 三个 ChatModel getter 都委托到这里，差异只剩 base-url
	 * / api-key / model 三组配置。
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

	@Override
	public EmbeddingModel getEmbeddingModel() {
		// 向量化模型走本地 Ollama，bge-m3（1024 维）与 pgvector 表 vector_store_bge_m3 对齐
		OllamaApi ollamaApi = OllamaApi.builder().baseUrl(embeddingBaseUrl).build();
		return OllamaEmbeddingModel.builder()
			.ollamaApi(ollamaApi)
			.defaultOptions(OllamaOptions.builder().model(embeddingModel).build())
			.build();
	}

	@Override
	public VectorStore getVectorStore() {
		// 用上面手动构建的 EmbeddingModel 作为 embedding 提供方，其它参数沿用 pgvector 自动装配读到的属性
		// 调用方应自行缓存此返回值，避免每次请求都重建（见类 Javadoc 第 3 条）
		return PgVectorStore.builder(jdbcTemplate, this.getEmbeddingModel())
			.initializeSchema(pgVectorStoreProperties.isInitializeSchema())
			.dimensions(pgVectorStoreProperties.getDimensions())
			.distanceType(pgVectorStoreProperties.getDistanceType())
			.indexType(pgVectorStoreProperties.getIndexType())
			.maxDocumentBatchSize(pgVectorStoreProperties.getMaxDocumentBatchSize())
			.schemaName(pgVectorStoreProperties.getSchemaName())
			.vectorTableName(pgVectorStoreProperties.getTableName())
			.removeExistingVectorStoreTable(pgVectorStoreProperties.isRemoveExistingVectorStoreTable())
			.idType(pgVectorStoreProperties.getIdType())
			.vectorTableValidationsEnabled(pgVectorStoreProperties.isSchemaValidation())
			.build();
	}

}
