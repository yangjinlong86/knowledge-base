# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## gstack
Use /browse from gstack for all web browsing. Never use mcp__claude-in-chrome__* tools.
Available skills: /office-hours, /plan-ceo-review, /plan-eng-review, /plan-design-review,
/design-consultation, /review, /ship, /land-and-deploy, /canary, /benchmark, /browse,
/qa, /qa-only, /design-review, /setup-browser-cookies, /setup-deploy, /retro,
/investigate, /document-release, /codex, /cso, /autoplan, /careful, /freeze, /guard,
/unfreeze, /gstack-upgrade.
If gstack skills aren't working, run `cd .claude/skills/gstack && ./setup` to build the binary and register skills.

## Project Summary
A personal AI knowledge-base / RAG chat system. Backend is Spring Boot + Spring AI 1.0; frontend is React + Umi.js (Max). Documents are embedded (bge-m3 via Ollama by default) and stored in PostgreSQL with the pgvector extension. Object storage (uploaded images, multimodal attachments) lives in MinIO. The LLM endpoint is OpenAI-compatible, intended to be routed through [One-API](https://github.com/songquanpeng/one-api).

Upstream repo: https://github.com/NingNing0111/knowledge-base (older Vue frontend lives on branch `0.8-vue`).

## Tech Stack
- Java 17, Maven (revision-pinned via `${revision}` property), Spring Boot 3.5.7
- Spring AI 1.0.0 (OpenAI starter, Ollama starter, pgvector vector store, Tika document reader, JDBC chat-memory snapshot)
- MyBatis-Plus 3.5.15
- Spring Security + JWT (jjwt 0.11.5)
- MinIO 8.5.9 (Java client) for object storage
- Knife4j 4.4.0 + springdoc for OpenAPI / Swagger UI
- Apache Tika / PDFBox / Tabula / POI for document parsing
- Frontend: `@umijs/max` 4.4, Ant Design 5 (dark theme), React 18, TypeScript, pnpm

## Common Commands
All commands are run from the repo root.

Backend (Maven):
- Build all modules: `mvn clean install -DskipTests`
- Compile only: `mvn compile`
- Run all tests: `mvn test`
- Run a single test class: `mvn -pl knowledge-base-system test -Dtest=MarkdownAutoSplitterTest`
- Run a single test method: `mvn -pl knowledge-base-system test -Dtest=MarkdownAutoSplitterTest#methodName`
- Run the Spring Boot app (for dev): run `org.nodoer.system.SystemApp` from your IDE, or `mvn -pl knowledge-base-system spring-boot:run`. Server listens on `:8788` with context path `/api` (Swagger at `/api/doc.html`, OpenAPI JSON at `/api/v3/api-docs/default`).
- Code style check: enforced automatically by `spring-javaformat-maven-plugin` during the `validate` phase — any `mvn` build will fail if formatting is off. Run `mvn spring-javaformat:validate` to check, or fix via your IDE's Spring Java Format plugin.

Frontend (`knowledge-base-ui/`):
- Install: `pnpm install`
- Dev server: `pnpm dev` (or `pnpm start`) — Umi dev server on `:3000`, proxying `/api` to `http://localhost:8788/api`.
- Build: `pnpm build`
- Format: `pnpm format` (Prettier, runs in lint-staged via husky)
- Regenerate OpenAPI client: `pnpm openapi` (fetches schema from the running backend; see `openAPI.schemaPath` in `.umirc.ts`).

Database:
- Apply schema: `psql -f sql/postgresql/init.sql` (creates `system_local_user`, `system_role`, etc. and enables the `vector` extension). MyBatis-Plus tables for chat / knowledge-base are created via the pgvector auto-init (`spring.ai.vectorstore.pgvector.initialize-schema: true`).
- Default dev creds in `application-dev.yml`: `pguser` / `123456` on `jdbc:postgresql://localhost:5432/pgtest`.
- The project expects a `docker-compose.yml` in `env/` for MinIO + pgvector (referenced in README; not present in this tree — create it locally if missing).

## Architecture (Maven modules)
```
knowledge-base/                  (parent POM — packaging pom)
├── knowledge-base-bom/          (centralized dependencyManagement; Spring AI / MyBatis-Plus / MinIO / JWT / Tika versions)
├── knowledge-base-core/         (shared library: BaseResponse/ResultUtils, GlobalExceptionHandler, BusinessException, BaseEntity, PageParam/PageResult, FileUtil, ObjectStoreService interface)
└── knowledge-base-system/       (runnable Spring Boot app; entry point org.nodoer.system.SystemApp)
    └── knowledge-base-ui/       (Umi.js frontend — sibling of the backend modules, not wired into the Maven build)
```

`knowledge-base-system` depends on `knowledge-base-core` and on `spring-ai-alibaba-core` 1.0.0.4 in addition to Spring AI. The bom is imported via `scope=import` from both downstream modules so version alignment is automatic.

### Package layout inside `knowledge-base-system`
```
org.nodoer.system
├── SystemApp                 — @SpringBootApplication entry point
├── constant/                 — AppConstant (CHAT_CONVERSATION_NAME, CHAT_MEDIAS, CHAT_MAX_LENGTH=20, RAG_TOP_K=5)
├── config/                   — AppConfig, MinioProperties, MybatisPlusConfig
├── config/web/               — SecurityConfig, SecurityProperties (JWT secret/salt, allow-list, admin-init, default password)
├── controller/               — REST controllers; AIChatController streams SSE (TEXT_EVENT_STREAM_VALUE)
├── controller/vo/            — Request/response DTOs (ChatRequestVO, ChatMessageVO, KnowledgeBaseVO, DocumentVO, ResourceVO, AuthVO, UserLoginVO, SimpleBaseVO, ChatConversationVO)
├── mapper/                   — MyBatis-Plus mappers (one per entity; XML in resources/mapper for user/role/permission)
├── memory/DatabaseChatMemory — implements org.springframework.ai.chat.memory.ChatMemory, persists messages in `chat_message` table
├── model/entity/ai/          — ChatConversation, ChatMessage, KnowledgeBase, DocumentEntity, OriginFileResource
├── model/entity/user/        — SystemUser, SystemRole, SystemPermission, SystemUserRole, SystemRolePermission
├── model/enums/              — ChatType (SIMPLE, SIMPLE_RAG, MULTIMODAL, MULTIMODAL_RAG — dispatched in AIChatServiceImpl#unifyChat)
├── objectstore/service/      — MinIOService implements core ObjectStoreService
├── security/                 — JwtService, JwtAuthenticationFilter, UserDetailsServiceImpl
├── service/ai/ (+impl/)      — AIChatService, LLMService, ChatMessageService, ChatConversationService, KnowledgeBaseService, DocumentEntityService, OriginFileResourceService
├── service/system/ (+impl/)  — SystemUserService, SystemRoleService, SystemPermissionService, AuthService
└── utils/                    — MarkdownAutoSplitter (covered by MarkdownAutoSplitterTest), SecurityFrameworkUtil
```

Resources under `knowledge-base-system/src/main/resources/`:
- `application.yml` — base config (port 8788, context `/api`, HikariCP, multipart 100MB, security defaults, Knife4j + springdoc).
- `application-dev.yml` — dev-only settings (imports `llm.yml`, OpenAI base-url pointing to local Ollama at `:11434`, pgvector table name `vector_store_bge_m3` with `dimensions: 1024`, MinIO config).
- `llm.yml` — chat model configs (`chat.simple`, `chat.long`, `chat.multimodal`) and `embedding`. **This file is the only one that should be customized per developer** — README notes it should be copied to `llm-dev.yml` (which is git-ignored); `application-dev.yml` then imports it.
- `prompt/RAG.txt` — RAG system prompt template loaded with `@Value("classpath:prompt/RAG.txt")` in `AIChatServiceImpl`.
- `mapper/*.xml` — MyBatis-Plus XML mappings for the user/role/permission tables.

## Key Architectural Patterns
- **Layered structure**: `controller` → `service` (+ `service.impl`) → `mapper` → entity. Service implementations extend `ServiceImpl<Mapper, Entity>` from MyBatis-Plus.
- **Unified response envelope**: every controller returns `BaseResponse<T>` (built via `ResultUtils.success/error` from `knowledge-base-core`). Cross-cutting errors are handled by `org.nodoer.core.exception.GlobalExceptionHandler` (`BusinessException` and `RuntimeException`).
- **Chat dispatch**: `AIChatController` exposes `POST /api/ai/chat/unify` and returns a `Flux<Generation>` (SSE). `AIChatServiceImpl#unifyChat` switches on `ChatType` to one of `simpleChat`, `simpleRAGChat`, `multimodalChat`, `multimodalRAGChat`. RAG uses `QuestionAnswerAdvisor` with a custom `SearchRequest` whose `filterExpression` restricts results to a user-supplied set of knowledge-base IDs.
- **Persistent chat memory**: `DatabaseChatMemory` is a `@Service` implementing Spring AI's `ChatMemory`; it reads/writes the `chat_message` table keyed by `conversationId` and capped at `CHAT_MAX_LENGTH = 20` messages. Messages with multimodal attachments are flagged via `hasMedia` + `resourceIds`.
- **Knowledge-base separation**: documents are embedded into pgvector with metadata containing `knowledge_base_id`; the filter expression `knowledge_base_id in ["<id>", ...]` is built in `AIChatServiceImpl#buildBaseAccessFilter`. Empty input is intentionally turned into a no-match filter (`["___empty___"]`).
- **Multimodal attachments**: uploaded media are stored in MinIO; `OriginFileResourceService.fromResourceId(resourceIds)` converts resource ids back to Spring AI `Media` objects attached to the `UserMessage`.
- **Auth**: stateless JWT. `SecurityConfig` registers `JwtAuthenticationFilter` before the standard auth filter; `SecurityProperties.allow-list` whitelists `/auth/login`, `/ai/chat/**`, swagger, etc. The `admin-init: true` flag bootstraps a default admin on first startup. Login helper: `org.nodoer.system.utils.SecurityFrameworkUtil.getLoginUser()`.

## Configuration Notes
- LLM / embedding endpoints live in `llm.yml` (or a per-developer `llm-dev.yml` override). README example points the `embedding` model at a local Ollama running `bge-m3`; the `chat.*` entries point at an OpenAI-compatible gateway. Do not commit real API keys.
- pgvector table name (`vector_store_bge_m3`) and `dimensions: 1024` must match the embedding model. If you switch the embedding model, update both.
- `spring.ai.openai.base-url` and `spring.ai.openai.api-key` in `application-dev.yml` are dev defaults for the OpenAI starter; `knowledge-base-system` itself pulls in `spring-ai-starter-model-ollama` (and `spring-ai-alibaba-core`), so the runtime chat model is wired through `LLMService` rather than these defaults.
- Lombok 1.18.42 is pinned via the `maven-compiler-plugin` `annotationProcessorPaths` in both `core` and `system` modules — keep that in sync if upgrading.

## Tests
- Test framework: JUnit 5 (`junit-jupiter` 5.10.3) + Mockito 5.12.0, both inherited from the parent POM.
- Existing tests: `knowledge-base-system/src/test/java/org/nodoer/system/utils/MarkdownAutoSplitterTest` and `.../service/ai/impl/OriginFileResourceServiceImplTest`. These are the templates for new tests.

## Frontend Notes (`knowledge-base-ui/`)
- Umi config: `.umirc.ts`. Routes: `/login`, `/chat` (default after `/`), `/knowlegeBase` (note the misspelling — keep it for back-compat), `/knowlegeBase/:knowledgeBaseId`, and a 404 catch-all.
- Ant Design is configured dark-mode by default with a custom cyan/blue theme (see `antd.theme.token` in `.umirc.ts`).
- `proxy./api` forwards to `http://localhost:8788/api`. The backend context path is `/api`, so the proxy strips it — keep both halves in sync.
- OpenAPI types are auto-generated from the running backend (`openAPI.schemaPath` in `.umirc.ts`); restart the backend before running `pnpm openapi` if routes have changed.
- `.env` is git-ignored; copy `.env.example` to `.env` and adjust `UMI_APP_BASE_URL` if the backend lives elsewhere. `UMI_DEV_SERVER_COMPRESS=none` must stay set — gzip on the dev server breaks SSE.

## Conventions
- Java code uses tabs (matches the existing files in `knowledge-base-system/`). The Spring Java Format plugin is the source of truth — don't manually reformat; let the plugin or your IDE handle it.
- Lombok is used pervasively (`@Slf4j`, `@RequiredArgsConstructor`, `@Data`); don't introduce POJO boilerplate.
- New entities should extend `org.nodoer.core.pojo.BaseEntity` (id, create_time, update_time, deleted, creator, updater conventions) so MyBatis-Plus optimistic-style fields stay consistent.
- Custom business errors should throw `BusinessException(Code, message)` rather than returning error responses manually — let `GlobalExceptionHandler` do the conversion.
- Frontend code is Prettier-formatted; husky + lint-staged run `prettier --write` on commit.
- **代码注释必须使用中文**：生成或修改 Java / TypeScript / YAML 等任何代码时，必须为类、方法、关键字段、关键逻辑分支补充详细的中文注释（Javadoc 风格的 `/** */` 或行内 `//`）。注释要解释“为什么这么做”和“做了什么”，不要只是把代码翻译一遍。
