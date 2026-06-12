# 双模型支持设计文档：OpenAI（Chat）+ Ollama（Embedding）

- **日期**：2026-06-12
- **作者**：协作产出（用户：yangjl）
- **状态**：待实施

## 1. 背景

当前 `knowledge-base-system` 模块只引入了 `spring-ai-starter-model-ollama`，`spring-ai-starter-model-openai` 在 `pom.xml` 中被注释。同时 `LLMServiceImpl` 中三个 ChatModel 的工厂方法（simple / long / multimodal）也被整段注释，`AIChatServiceImpl` 里所有调用处都被改成 `ChatModel chatModel = null;` —— 这意味着 chat 通路实际上是不可用的，只有 embedding 通路（Ollama 本地 `bge-m3`）在工作。

用户希望让本项目**同时兼容 OpenAI 和 Ollama 两种模型**：
- **Embedding**：继续使用本地 Ollama 上的向量模型 `bge-m3`
- **Chat**：使用 OpenAI 协议（通过 [One-API](https://github.com/songquanpeng/one-api) 路由到任意 OpenAI 兼容后端，如 DeepSeek、Baichuan、GPT-4o）

要求**同时**引入两个 starter：`spring-ai-starter-model-openai` 与 `spring-ai-starter-model-ollama`，并在它们共存的情况下让应用正常启动且 chat / embedding 通路都可用。

## 2. 目标与非目标

### 目标
- 让 `spring-ai-starter-model-openai` 与 `spring-ai-starter-model-ollama` 在同一应用中共存且无 Bean 冲突
- 恢复 `LLMServiceImpl` 中三个 ChatModel（simple / long / multimodal）的可用性
- 修复 `AIChatServiceImpl` 中 `ChatModel chatModel = null` 的破损调用
- 保留 `llm.yml` 现有结构（simple / long / multimodal 三套独立 chat 配置 + 一套 embedding 配置）
- 应用启动后 chat 通路与 embedding 通路均可独立工作

### 非目标
- **不**重构 `llm.yml`、`AIChatService` 接口、ChatType 枚举或前端代码
- **不**引入 ChatModel 缓存 / 池化（按需 build 即可，先保持简单）
- **不**新增单元测试（本次改动以 Bean wiring 与配置为主，无可测的纯逻辑）
- **不**做 chat 通路的功能验证：API key 尚未配置，留待用户后续手工验证

## 3. 关键决策

| # | 决策 | 选项 | 选定方案 | 理由 |
|---|------|------|---------|------|
| 1 | 模型装配方式 | A. 全部手动构建 / B. 部分自动 + 部分手动 / C. 完全自动配置 | **A. 全部手动构建** | 沿用项目原有模式；三个独立 chat 配置无法用 Spring AI 自动配置表达；彻底避免 Bean 冲突 |
| 2 | long 模型是否保留 | A. 三个都保留 / B. 只留实际用到的 | **A. 三个都保留** | 留作扩展，代价极小 |
| 3 | `application-dev.yml` 中的 `spring.ai.openai.*` 历史配置 | A. 删除 / B. 置空保留 / C. 保留不动 | **A. 直接删除** | 该段配置已无组件读取，留着会误导后续维护 |
| 4 | 如何禁用 starter 自动装配 | A. 配置文件属性禁用 / B. `@SpringBootApplication(exclude=...)` | **A. 配置文件属性禁用** | 改动小、意图清晰、不会因 Spring AI 版本升级时类名变化而失效 |

## 4. 架构

整体策略：**两个 starter 同时引入，但禁用它们对 ChatModel / EmbeddingModel 的自动装配，所有模型由 `LLMServiceImpl` 用 builder 手动构建，配置全部从 `llm.yml` 读取**。

```
                         ┌─────────────────────────────┐
                         │ application.yml             │
                         │ spring.ai.model.chat=none   │
                         │ spring.ai.model.embedding=  │
                         │              none           │
                         └──────────────┬──────────────┘
                                        │ 禁用自动装配
                                        ▼
┌──────────────────────┐    ┌──────────────────────────────┐
│ llm.yml              │    │ LLMServiceImpl               │
│ chat.simple.*        │───▶│ getChatModel()           ────┼──▶ OpenAiChatModel（One-API）
│ chat.long.*          │───▶│ getLongContextChatModel()────┼──▶ OpenAiChatModel（One-API）
│ chat.multimodal.*    │───▶│ getMultimodalModel()     ────┼──▶ OpenAiChatModel（One-API）
│ embedding.*          │───▶│ getEmbeddingModel()      ────┼──▶ OllamaEmbeddingModel
└──────────────────────┘    │ getVectorStore()         ────┼──▶ PgVectorStore（用 embedding）
                            └──────────────────────────────┘
                                        ▲
                                        │
                            ┌───────────┴────────────┐
                            │ AIChatServiceImpl      │
                            │ simpleChat /            │
                            │ simpleRAGChat /         │
                            │ multimodalChat 调用上面 │
                            └────────────────────────┘
```

辅助 Bean（`OpenAiApi`、`OllamaApi`、`PgVectorStoreAutoConfiguration` 等）由 starter 自动装配照常工作 —— 我们只是关掉了 ChatModel / EmbeddingModel 这一层。

## 5. 涉及的文件与改动清单

| 文件 | 改动 |
|------|------|
| `knowledge-base-system/pom.xml` | 取消 `spring-ai-starter-model-openai` 依赖的注释 |
| `knowledge-base-system/src/main/resources/application.yml` | 新增 `spring.ai.model.chat=none` 与 `spring.ai.model.embedding=none` |
| `knowledge-base-system/src/main/resources/application-dev.yml` | 删除 `spring.ai.openai` 段（历史遗留，已无组件读取） |
| `knowledge-base-system/src/main/resources/llm.yml` | 保持不变（结构已满足需求） |
| `knowledge-base-system/src/main/java/org/nodoer/system/service/ai/LLMService.java` | 取消三个 ChatModel getter 接口方法的注释 |
| `knowledge-base-system/src/main/java/org/nodoer/system/service/ai/impl/LLMServiceImpl.java` | 恢复三个 ChatModel 的 builder 实现（基于 `OpenAiApi` + `OpenAiChatOptions`）；补充详细中文注释 |
| `knowledge-base-system/src/main/java/org/nodoer/system/service/ai/impl/AIChatServiceImpl.java` | 将三处 `ChatModel chatModel = null;` 改为调用对应的 `llmService.getXxxChatModel()`；补充中文注释 |

**不涉及改动的文件**：`AIChatController`、其他 Service、前端 React 代码、SQL 初始化脚本。

## 6. 关键实现细节

### 6.1 禁用自动装配（`application.yml`）

```yaml
spring:
  ai:
    model:
      chat: none        # 禁止所有 starter 自动装配 ChatModel Bean，由 LLMServiceImpl 手动 build
      embedding: none   # 禁止所有 starter 自动装配 EmbeddingModel Bean，由 LLMServiceImpl 手动 build
```

Spring AI 1.0 通过 `spring.ai.model.chat` 与 `spring.ai.model.embedding` 控制使用哪个 provider 的自动配置。设为 `none` 后两个 starter 的对应自动配置均不触发，但 `OpenAiApi`、`OllamaApi`、`PgVectorStoreAutoConfiguration` 等辅助 Bean 仍然可用。

### 6.2 `LLMServiceImpl` 中三个 ChatModel 的构建

```java
@Override
public ChatModel getChatModel() {
    // 通用对话模型：经 One-API 路由到 OpenAI 兼容后端
    OpenAiApi api = OpenAiApi.builder()
        .baseUrl(simpleBaseUrl)
        .apiKey(simpleApiKey)
        .build();
    return OpenAiChatModel.builder()
        .openAiApi(api)
        .defaultOptions(OpenAiChatOptions.builder().model(simpleModel).build())
        .build();
}
// getLongContextChatModel / getMultimodalModel 同构，只是读取不同的 @Value 字段
```

`getEmbeddingModel()` 保持现状（继续用 `OllamaEmbeddingModel` 手动 build，`base-url` 与 `model` 从 `embedding.*` 读取）。`getVectorStore()` 不变。

### 6.3 `AIChatServiceImpl` 解开 `null`

替换三处 `ChatModel chatModel = null;`：

| 方法 | 替换为 |
|------|--------|
| `simpleChat` | `ChatModel chatModel = llmService.getChatModel();` |
| `simpleRAGChat` | `ChatModel chatModel = llmService.getChatModel();` |
| `multimodalChat` | `ChatModel chatModel = llmService.getMultimodalModel();` |

`multimodalRAGChat` 目前仍返回 `null`（本次设计**不**触碰这一遗留 TODO，超出范围）。

每次请求都新建 `ChatModel` 实例的开销很小（只是 builder 配置；底层 HTTP client 在 `OpenAiApi` 内部惰性创建）。**不引入缓存**，保持简单 —— 后续若有性能需求再单独优化。

### 6.4 代码注释要求

按 `CLAUDE.md` 约定，本次新增 / 修改的 Java 代码必须配套详细的中文注释：
- 三个 ChatModel getter 的 Javadoc 要说明用途、配置来源（`llm.yml` 的哪个 section）、为什么手动 build 而非依赖自动装配
- `application.yml` 新增的两行属性要有中文行内 `#` 注释，说明为什么禁用
- `AIChatServiceImpl` 三处替换要说明改用 `llmService.getXxxChatModel()` 的原因

## 7. 错误处理

- 不新增任何错误处理代码 —— Spring AI 内部已有 HTTP 错误处理，`GlobalExceptionHandler` 会兜底
- 配置缺失（如 `chat.simple.api-key` 没填）时，Spring 的 `@Value` 注入会在启动时直接抛 `IllegalArgumentException`，让应用 fail-fast，无需额外校验
- 两个 starter 的辅助 Bean（`OpenAiApi`、`OllamaApi`）自动装配若有问题，也会在启动期暴露，不会潜伏到运行时

## 8. 验证策略

- **构建验证**：`mvn clean compile` —— 确认两个 starter 共存无编译 / Bean 冲突
- **启动验证**：`mvn -pl knowledge-base-system spring-boot:run` —— 确认应用启动无 Bean 冲突错误；`LLMServiceImpl` 三个 ChatModel getter 不抛异常即可
- **功能验证**：**暂跳过**。chat model 的 API key 尚未配置，待用户后续填好 `llm.yml`（或 `llm-dev.yml`）后再手工验证：
  - 前端启动命令：`npm run dev`
  - 前端端口：`8000`（不是默认的 3000）
  - 验证步骤可用 gstack：`/browse http://localhost:8000`，登录后在 `/chat` 发简单消息验证 OpenAI chat；上传文档到 `/knowlegeBase` 验证 Ollama embedding；再回 `/chat` 做一次 RAG 提问验证联动

## 9. 风险与缓解

| 风险 | 影响 | 缓解 |
|------|------|------|
| Spring AI 1.0 的 `spring.ai.model.*=none` 属性命名在未来版本变更 | 自动装配可能再次触发，导致 Bean 冲突 | 该属性是 Spring AI 1.0 官方文档列出的标准做法；升级 Spring AI 时同步检查 |
| `OpenAiApi`/`OllamaApi` 等辅助 Bean 与自动装配后的 ChatModel 强耦合，被一起跳过 | 手动 build 时找不到所需依赖 | `LLMServiceImpl` 不依赖这些辅助 Bean，自己 `OpenAiApi.builder()` 直接构建；已验证不受影响 |
| 用户后续真把 chat 也切到本地 Ollama | 现有手动 build 只支持 OpenAI 协议 | 不在本次范围；届时给 `LLMServiceImpl` 加一个分支 / 配置即可 |

## 10. 后续工作（不在本次范围）

- `multimodalRAGChat` 目前直接返回 `null`，属遗留 TODO，由后续单独立项
- ChatModel 实例复用 / 缓存（如有性能需求）
- `llm-dev.yml` 模板的 README 说明同步更新
