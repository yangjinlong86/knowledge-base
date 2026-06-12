# 双模型支持（OpenAI Chat + Ollama Embedding）实施计划

> **给执行代理：** 必须使用的子技能：superpowers:subagent-driven-development（推荐）或 superpowers:executing-plans，按任务逐步执行本计划。所有步骤使用复选框（`- [ ]`）语法跟踪进度。

**目标：** 让 `knowledge-base-system` 同时引入 `spring-ai-starter-model-openai` 与 `spring-ai-starter-model-ollama`，由 `LLMServiceImpl` 手动 build 三个 OpenAI 兼容 ChatModel 与一个 Ollama EmbeddingModel，恢复 chat 通路的可用性。

**架构：** 两个 starter 在 `application.yml` 中通过 `spring.ai.model.chat=none` / `spring.ai.model.embedding=none` 关闭自动装配，全部模型由 `LLMServiceImpl` 用 builder 手动构建，配置从 `llm.yml` 读取。`AIChatServiceImpl` 三处 `ChatModel = null` 替换为实际调用。

**技术栈：** Java 17、Spring Boot 3.5.7、Spring AI 1.0.0（OpenAI + Ollama starter）、Maven。代码遵循 `CLAUDE.md` 约定：tab 缩进、Spring Java Format、详细中文注释。

**设计文档：** `docs/superpowers/specs/2026-06-12-dual-model-openai-ollama-design.md`

---

## 前置准备

- 工作目录：`/Users/yangjl/local/github/yangjl/knowledge-base`
- 当前分支：`main`
- 已有未提交改动：`knowledge-base-system/src/main/resources/application-dev.yml` 中 `url` 改为 `pgtest`（与本计划无关，保持不动）
- 本计划**不**写新单元测试（设计文档已说明：本次以 Bean wiring 与配置为主，无可测纯逻辑）；验证以 `mvn clean compile` 与应用启动为主
- 注释要求：所有新增/修改的 Java、YAML 文件按 `CLAUDE.md` 添加详细中文注释

---

## Task 1：在 `pom.xml` 中取消 OpenAI starter 注释

**Files:**
- Modify: `knowledge-base-system/pom.xml:46-49`

- [ ] **Step 1: 阅读现状**

Read `knowledge-base-system/pom.xml` 第 45-53 行，确认当前 OpenAI starter 是注释状态，Ollama starter 是激活状态。

- [ ] **Step 2: 修改 pom.xml**

将以下注释块：

```xml
        <!-- AI -->
<!--        <dependency>-->
<!--            <groupId>org.springframework.ai</groupId>-->
<!--            <artifactId>spring-ai-starter-model-openai</artifactId>-->
<!--        </dependency>-->
        <dependency>
            <groupId>org.springframework.ai</groupId>
            <artifactId>spring-ai-starter-model-ollama</artifactId>
        </dependency>
```

替换为：

```xml
        <!-- AI：同时引入 OpenAI 与 Ollama 两个 starter -->
        <!-- chat 通路走 OpenAI 协议（经 One-API 路由），embedding 通路走本地 Ollama -->
        <dependency>
            <groupId>org.springframework.ai</groupId>
            <artifactId>spring-ai-starter-model-openai</artifactId>
        </dependency>
        <dependency>
            <groupId>org.springframework.ai</groupId>
            <artifactId>spring-ai-starter-model-ollama</artifactId>
        </dependency>
```

- [ ] **Step 3: 验证依赖能解析**

> **注意**：根 pom 用 `${revision}` 机制管理版本号，但仓库目前未配置 `flatten-maven-plugin`（项目既有缺陷，与本计划无关），因此任何 `mvn -pl knowledge-base-system <goal>` 形式命令都会因无法解析父 pom 而失败。所有针对 `knowledge-base-system` 模块的命令必须加 `-am`（also-make）让 Maven 先构建上游模块。

运行：
```bash
mvn -pl knowledge-base-system -am dependency:resolve -q
```
期望：命令成功退出，无错误；日志中能看到 `spring-ai-starter-model-openai` 与 `spring-ai-starter-model-ollama` 两个 artifact 同时被解析。

- [ ] **Step 4: 提交**

```bash
git add knowledge-base-system/pom.xml
git commit -m "build: enable spring-ai-starter-model-openai alongside ollama"
```

---

## Task 2：在 `application.yml` 中禁用 chat / embedding 自动装配

**Files:**
- Modify: `knowledge-base-system/src/main/resources/application.yml`

> **背景**：两个 starter 同时存在时，各自会试图自动装配 `ChatModel` / `EmbeddingModel` 导致冲突。我们让 `LLMServiceImpl` 手动 build 全部模型，所以两边的自动装配都关掉。

- [ ] **Step 1: 阅读现状**

Read `knowledge-base-system/src/main/resources/application.yml` 全文，定位 `spring:` 顶级节点（约第 6 行起）。当前 `spring:` 节点下只有 `profiles`、`application`、`datasource`、`servlet` 四个子项。

- [ ] **Step 2: 修改 application.yml**

将 `spring:` 节点扩展，在 `application:` 子项之后、`datasource:` 之前插入 `ai:` 子节点。具体地，将：

```yaml
spring:
  profiles:
    active: dev
  application:
    name: system-app

  datasource:
```

替换为：

```yaml
spring:
  profiles:
    active: dev
  application:
    name: system-app

  # Spring AI 自动装配开关：
  # 项目同时引入了 spring-ai-starter-model-openai 与 spring-ai-starter-model-ollama，
  # 两个 starter 默认都会尝试装配各自的 ChatModel / EmbeddingModel Bean，从而引发冲突。
  # 本项目所有 ChatModel / EmbeddingModel 都由 LLMServiceImpl 用 builder 手动构建（配置从 llm.yml 读），
  # 因此在这里统一关闭自动装配。OpenAiApi / OllamaApi / PgVectorStore 等辅助 Bean 不受影响，仍可使用。
  ai:
    model:
      chat: none
      embedding: none

  datasource:
```

- [ ] **Step 3: 提交**

```bash
git add knowledge-base-system/src/main/resources/application.yml
git commit -m "config: disable spring-ai chat/embedding auto-config (manual wiring)"
```

---

## Task 3：删除 `application-dev.yml` 中无用的 `spring.ai.openai` 段

**Files:**
- Modify: `knowledge-base-system/src/main/resources/application-dev.yml:6-9`

> **背景**：该段配置 (`base-url: http://localhost:11434, api-key: 123`) 是 OpenAI starter 被注释时期残留的历史配置，目前无任何组件读取，且端口指向 Ollama 而非真实 OpenAI 端点，留着会误导后续维护。设计文档第 5 节已明确删除。

- [ ] **Step 1: 阅读现状**

Read `knowledge-base-system/src/main/resources/application-dev.yml` 全文。确认 `spring.ai.openai` 段位于 `vectorstore.pgvector` 之前。注意：本文件当前有一个未提交改动（`url` 由 `knowledge_db` 改为 `pgtest`）—— 该改动与本任务无关，不要还原。

- [ ] **Step 2: 删除 spring.ai.openai 段**

将：

```yaml
spring:
  config:
    import: classpath:llm.yml
  ai:
    openai:
      base-url: http://localhost:11434
      api-key: 123
    vectorstore:
      pgvector:
```

替换为：

```yaml
spring:
  config:
    import: classpath:llm.yml
  ai:
    # 注意：本项目 chat / embedding 模型全部由 LLMServiceImpl 手动 build（配置来自 llm.yml），
    # 因此这里不再设置 spring.ai.openai.* 与 spring.ai.ollama.*。
    # pgvector 仍然依赖 Spring AI 自动装配，所以保留 vectorstore.pgvector 配置。
    vectorstore:
      pgvector:
```

- [ ] **Step 3: 提交（与已有 url 改动一起）**

```bash
git add knowledge-base-system/src/main/resources/application-dev.yml
git commit -m "config: drop unused spring.ai.openai dev defaults"
```

---

## Task 4：在 `LLMService` 接口中恢复三个 ChatModel getter

**Files:**
- Modify: `knowledge-base-system/src/main/java/org/nodoer/system/service/ai/LLMService.java`

- [ ] **Step 1: 阅读现状**

Read `knowledge-base-system/src/main/java/org/nodoer/system/service/ai/LLMService.java`。确认三个 ChatModel getter 当前是被 `//` 注释掉的。

- [ ] **Step 2: 替换文件全文**

将文件内容整体替换为：

```java
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
	 * 获取超长上下文对话模型。 对应 llm.yml 中 {@code chat.long.*} 配置，走 OpenAI 协议（经 One-API 路由）。
	 * 当前 AIChatServiceImpl 暂未调用，仅作为扩展位点保留。
	 * @return 新构建的 ChatModel 实例
	 */
	ChatModel getLongContextChatModel();

	/**
	 * 获取多模态对话模型。 对应 llm.yml 中 {@code chat.multimodal.*} 配置，走 OpenAI 协议（经 One-API 路由）。
	 * 由 AIChatServiceImpl#multimodalChat 使用。
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
```

- [ ] **Step 3: 提交**

```bash
git add knowledge-base-system/src/main/java/org/nodoer/system/service/ai/LLMService.java
git commit -m "feat(llm): restore three ChatModel getters in LLMService"
```

---

## Task 5：在 `LLMServiceImpl` 中实现三个 ChatModel builder

**Files:**
- Modify: `knowledge-base-system/src/main/java/org/nodoer/system/service/ai/impl/LLMServiceImpl.java`

- [ ] **Step 1: 阅读现状**

Read `knowledge-base-system/src/main/java/org/nodoer/system/service/ai/impl/LLMServiceImpl.java`。确认：
- 三个 OpenAI ChatModel 实现段当前是被注释掉的（约第 70-99 行）
- 三组 OpenAI 配置的 `@Value` 字段已存在（`simpleBaseUrl`/`simpleApiKey`/`simpleModel` 等）
- 顶部 OpenAI 相关 import 被注释（约第 11-13 行）

- [ ] **Step 2: 替换文件全文**

将文件内容整体替换为：

```java
package org.nodoer.system.service.ai.impl;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
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
 * <a href="https://github.com/songquanpeng/one-api">One-API</a> 路由到任意 OpenAI 兼容后端，
 * 配置分别来自 {@code llm.yml} 的 {@code chat.simple.*} / {@code chat.long.*} /
 * {@code chat.multimodal.*}。</li>
 * <li>EmbeddingModel 走本地 Ollama（默认 {@code bge-m3}），配置来自 {@code embedding.*}。</li>
 * <li>所有模型都"按需构建"——每次调用 getter 都新建一个实例。 builder 本身开销很小，且底层 HTTP client 在 OpenAiApi /
 * OllamaApi 内部惰性创建；如未来出现性能瓶颈再考虑缓存。</li>
 * <li>之所以全部手动 build 而不是依赖 starter 自动装配： 项目里有三套独立的 chat 配置（base-url / api-key / model 各异），
 * Spring AI 的自动装配每个 provider 只能产出一个 ChatModel Bean，表达不了这种 "1 个 provider × 多套配置"
 * 的需求。 因此在 application.yml 里把 chat / embedding 的自动装配关掉（{@code spring.ai.model.chat=none}
 * 与 {@code spring.ai.model.embedding=none}），手工接管。</li>
 * </ol>
 */
@Service
@Slf4j
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
		// 通用对话模型：走 OpenAI 协议，base-url / api-key / model 全来自 llm.yml 的 chat.simple.*
		OpenAiApi openAiApi = OpenAiApi.builder().baseUrl(simpleBaseUrl).apiKey(simpleApiKey).build();
		return OpenAiChatModel.builder()
			.openAiApi(openAiApi)
			.defaultOptions(OpenAiChatOptions.builder().model(simpleModel).build())
			.build();
	}

	@Override
	public ChatModel getLongContextChatModel() {
		// 超长上下文对话模型：同样走 OpenAI 协议，配置来自 chat.long.*
		OpenAiApi openAiApi = OpenAiApi.builder().baseUrl(longBaseUrl).apiKey(longApiKey).build();
		return OpenAiChatModel.builder()
			.openAiApi(openAiApi)
			.defaultOptions(OpenAiChatOptions.builder().model(longModel).build())
			.build();
	}

	@Override
	public ChatModel getMultimodalChatModel() {
		// 多模态对话模型：同样走 OpenAI 协议，配置来自 chat.multimodal.*
		OpenAiApi openAiApi = OpenAiApi.builder().baseUrl(multimodalBaseUrl).apiKey(multimodalApiKey).build();
		return OpenAiChatModel.builder()
			.openAiApi(openAiApi)
			.defaultOptions(OpenAiChatOptions.builder().model(multimodalModel).build())
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
```

- [ ] **Step 3: 编译验证**

运行：
```bash
mvn -pl knowledge-base-system compile -q
```
期望：编译成功，无错误。如果 `OpenAiChatModel` / `OpenAiApi` / `OpenAiChatOptions` import 报红，说明 Task 1 的 pom 改动未生效，回到 Task 1 检查。

- [ ] **Step 4: 提交**

```bash
git add knowledge-base-system/src/main/java/org/nodoer/system/service/ai/impl/LLMServiceImpl.java
git commit -m "feat(llm): build three OpenAI ChatModels manually from llm.yml"
```

---

## Task 6：在 `AIChatServiceImpl` 中替换三处 `ChatModel = null`

**Files:**
- Modify: `knowledge-base-system/src/main/java/org/nodoer/system/service/ai/impl/AIChatServiceImpl.java:60,76,102`

- [ ] **Step 1: 阅读现状**

Read `knowledge-base-system/src/main/java/org/nodoer/system/service/ai/impl/AIChatServiceImpl.java`，确认：
- `simpleChat` 第 60 行：`ChatModel chatModel = null;// llmService.getChatModel();`
- `multimodalChat` 第 76 行：`ChatModel chatModel = null;// llmService.getMultimodalChatModel();`
- `simpleRAGChat` 第 102 行：`ChatModel chatModel = null;// llmService.getChatModel();`

- [ ] **Step 2: 替换第 60 行（simpleChat）**

将：
```java
		ChatModel chatModel = null;// llmService.getChatModel();
```
替换为：
```java
		// 通用对话：走 llm.yml chat.simple.* 配置的 OpenAI 协议模型
		ChatModel chatModel = llmService.getChatModel();
```

- [ ] **Step 3: 替换第 76 行（multimodalChat）**

将：
```java
		ChatModel chatModel = null;// llmService.getMultimodalChatModel();
```
替换为：
```java
		// 多模态对话：走 llm.yml chat.multimodal.* 配置的 OpenAI 协议模型
		ChatModel chatModel = llmService.getMultimodalChatModel();
```

- [ ] **Step 4: 替换第 102 行（simpleRAGChat）**

将：
```java
		ChatModel chatModel = null;// llmService.getChatModel();
```
替换为：
```java
		// RAG 对话同样使用通用对话模型；检索通路由下方 QuestionAnswerAdvisor 接管
		ChatModel chatModel = llmService.getChatModel();
```

- [ ] **Step 5: 编译验证**

运行：
```bash
mvn -pl knowledge-base-system compile -q
```
期望：编译成功，无错误。

- [ ] **Step 6: 提交**

```bash
git add knowledge-base-system/src/main/java/org/nodoer/system/service/ai/impl/AIChatServiceImpl.java
git commit -m "fix(chat): wire ChatModels from LLMService instead of null"
```

> **注意**：`multimodalRAGChat` 方法仍返回 `null`，这是一个遗留 TODO，不在本次范围。

---

## Task 7：整体构建验证

**Files:** 无（仅运行命令）

- [ ] **Step 1: 全量编译**

运行：
```bash
mvn clean compile
```
期望：所有模块编译成功 (`BUILD SUCCESS`)。如果失败：
- 若是 Spring Java Format 错误，按报错提示让 IDE 的 Spring Java Format 插件 reformat 受影响文件
- 若是符号找不到，检查 Task 1 是否成功取消 OpenAI starter 的注释

- [ ] **Step 2: 跑现有测试，确认无回归**

运行：
```bash
mvn -pl knowledge-base-system test
```
期望：所有现有测试通过（`MarkdownAutoSplitterTest`、`OriginFileResourceServiceImplTest`）。

---

## Task 8：启动验证

**Files:** 无（仅运行命令）

> **前提**：`llm.yml`（或 `llm-dev.yml`）中所有 `chat.*` 与 `embedding.*` 字段必须有值。本地 PostgreSQL（pgvector）需可达。**chat 的 API key 真假不重要**，只要字段非空即可通过启动期的 `@Value` 注入。

- [ ] **Step 1: 启动应用**

运行：
```bash
mvn -pl knowledge-base-system spring-boot:run
```

- [ ] **Step 2: 观察日志**

期望：
- 看到 `Started SystemApp in X seconds`
- 没有 `NoSuchBeanDefinitionException`、`BeanCreationException`、`ConflictingBeanDefinitionException` 等异常
- 没有 `Multiple beans of type ChatModel` 之类的冲突错误

如果启动失败：
- 若提示 `@Value` 注入失败（如 `Could not resolve placeholder 'chat.simple.api-key'`），检查 `llm.yml` 字段是否齐全
- 若提示 ChatModel / EmbeddingModel Bean 冲突，检查 Task 2 是否在 `application.yml` 正确加上 `spring.ai.model.chat=none` / `embedding=none`
- 若提示找不到 `PgVectorStoreProperties`，说明 pgvector 自动装配未生效，检查 `application-dev.yml` 中 `spring.ai.vectorstore.pgvector` 段是否仍存在

- [ ] **Step 3: 停止应用**

`Ctrl+C` 终止进程。

> **功能验证（chat / embedding / RAG 通路）暂跳过** —— 待用户配置好真实的 chat API key 后，自行用 `npm run dev`（端口 8000）启动前端，通过 gstack `/browse http://localhost:8000` 走完整链路。

---

## 自查（writing-plans 技能要求）

### 1. 设计文档覆盖

| 设计文档章节 | 对应 Task |
|---|---|
| §5 pom.xml 改动 | Task 1 |
| §5 application.yml 改动 | Task 2 |
| §5 application-dev.yml 改动 | Task 3 |
| §5 LLMService 接口改动 | Task 4 |
| §5 LLMServiceImpl 改动 / §6.2 | Task 5 |
| §5 AIChatServiceImpl 改动 / §6.3 | Task 6 |
| §8 构建验证 + 启动验证 | Task 7 + Task 8 |
| §8 功能验证（gstack） | 显式跳过（API key 未就绪） |
| §6.4 中文注释 | Task 2/4/5/6 内嵌 |
| §5 `llm.yml` 不动 | 计划中无对应 Task（无改动） |

无 Spec 遗漏。

### 2. 占位符扫描

无 TBD / TODO / "implement later" / "similar to Task N"。每个 step 都给出了完整代码或确切命令。`multimodalRAGChat` 的 `return null` 是设计文档第 10 节显式划出的"后续工作"。

### 3. 类型一致性

- `LLMService` 接口的 4 个 getter 签名（Task 4）与 `LLMServiceImpl` 的实现（Task 5）一致
- `AIChatServiceImpl` 调用的方法名（`getChatModel` / `getMultimodalChatModel`，Task 6）与接口定义一致
- `OpenAiApi`、`OpenAiChatModel`、`OpenAiChatOptions`、`OllamaApi`、`OllamaEmbeddingModel`、`OllamaOptions` 包路径在 Task 5 与现有代码一致
