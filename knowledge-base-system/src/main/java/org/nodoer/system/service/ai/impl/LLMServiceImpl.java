package org.nodoer.system.service.ai.impl;

import org.nodoer.system.service.ai.LLMService;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.pgvector.PgVectorStore;
import org.springframework.ai.vectorstore.pgvector.autoconfigure.PgVectorStoreProperties;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * {@link LLMService} 的默认实现（门面模式）。
 *
 * <p>
 * 本类本身不再持有任何模型 builder 代码：4 个模型 Bean（{@code simpleChatModel} /
 * {@code longContextChatModel} / {@code multimodalChatModel} / {@code embeddingModel}）由
 * {@code LLMConfig} 构建并暴露， 本类通过 {@code @Qualifier} 注入并直接转发。 这种"配置与门面分离"的结构让
 * {@code PgVectorStore} 等下游消费者也能 拿到自动注册的 {@code EmbeddingModel} bean，避免之前的
 * {@code UnsatisfiedDependencyException}。
 * </p>
 *
 * <p>
 * 构造器显式编写的原因：Lombok 的 {@code @RequiredArgsConstructor} 不会把字段上的 {@code @Qualifier}
 * 透传到生成的构造器 参数，导致 Spring 看到 3 个 {@code ChatModel} Bean 注入歧义。这里直接写构造器并把 {@code @Qualifier}
 * 标在参数上。
 * </p>
 *
 * <p>
 * 设计文档：docs/superpowers/specs/2026-06-12-dual-model-openai-ollama-design.md
 * </p>
 */
@Service
public class LLMServiceImpl implements LLMService {

	private final ChatModel simpleChatModel;

	private final ChatModel longContextChatModel;

	private final ChatModel multimodalChatModel;

	private final EmbeddingModel embeddingModel;

	private final JdbcTemplate jdbcTemplate;

	private final PgVectorStoreProperties pgVectorStoreProperties;

	/**
	 * 显式构造器，{@code @Qualifier} 标在参数上以避免 3 个 ChatModel Bean 的注入歧义。
	 * @param simpleChatModel 通用对话模型（{@code LLMConfig#simpleChatModel}）
	 * @param longContextChatModel 超长上下文对话模型（{@code LLMConfig#longContextChatModel}）
	 * @param multimodalChatModel 多模态对话模型（{@code LLMConfig#multimodalChatModel}）
	 * @param embeddingModel 向量化模型（{@code LLMConfig#embeddingModel}，同时供 pgvector 自动装配注入）
	 * @param jdbcTemplate 由 Spring Boot 自动装配
	 * @param pgVectorStoreProperties pgvector 自动装配读到的属性
	 */
	public LLMServiceImpl(@Qualifier("simpleChatModel") ChatModel simpleChatModel,
			@Qualifier("longContextChatModel") ChatModel longContextChatModel,
			@Qualifier("multimodalChatModel") ChatModel multimodalChatModel,
			@Qualifier("embeddingModel") EmbeddingModel embeddingModel, JdbcTemplate jdbcTemplate,
			PgVectorStoreProperties pgVectorStoreProperties) {
		this.simpleChatModel = simpleChatModel;
		this.longContextChatModel = longContextChatModel;
		this.multimodalChatModel = multimodalChatModel;
		this.embeddingModel = embeddingModel;
		this.jdbcTemplate = jdbcTemplate;
		this.pgVectorStoreProperties = pgVectorStoreProperties;
	}

	@Override
	public ChatModel getChatModel() {
		return simpleChatModel;
	}

	@Override
	public ChatModel getLongContextChatModel() {
		return longContextChatModel;
	}

	@Override
	public ChatModel getMultimodalChatModel() {
		return multimodalChatModel;
	}

	@Override
	public EmbeddingModel getEmbeddingModel() {
		return embeddingModel;
	}

	@Override
	public VectorStore getVectorStore() {
		// 用注入的 EmbeddingModel 作为 embedding 提供方，其它参数沿用 pgvector 自动装配读到的属性
		return PgVectorStore.builder(jdbcTemplate, this.embeddingModel)
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
