# knowledge-base

一款基于 [RAG 技术](https://www.promptingguide.ai/zh/techniques/rag) 实现的个人知识库 AI 问答系统，采用 Spring AI 1.0 + React + Umi.js 构建，适配 OpenAI 接口，可搭配 [One-API](https://github.com/songquanpeng/one-api) 统一调用大语言模型。

🌟 **本项目重在介绍 Spring AI 与 RAG 技术的实现，可作为学习项目参考。**

- 前端：React + Umi.js (Max)
- 后端：Spring Boot 3.5 + Spring AI 1.0
- 向量库：PostgreSQL + pgvector
- 对象存储：MinIO

## 功能特性

- 使用 Spring AI 1.0 实现；
- 自定义 `ChatMessage` 并实现 `DatabaseChatMemory`，对话历史持久化到数据库；
- 基于 `QuestionAnswerAdvisor` + 自定义提示词模板构建 RAG 上下文；
- 通过自定义向量查询条件实现**知识库分离**，支持 RAG 对话时指定多个知识库；
- 多模态对话时将附件存储到 MinIO，按资源 id 动态构建 `Media` 注入到 `UserMessage`；
- SSE 流式输出（`POST /api/ai/chat/unify`）。

## 功能进度

### 后端

- [x] 对话附件上传接口（多模态）
- [x] `DatabaseChatMemory`（消息入库）
- [x] 知识库增删改查
- [x] 知识库附件上传接口（文档入库 + 元信息记录 `knowledge_base_id`）
- [x] 知识库下附件文档删查
- [x] 对话信息接口（创建 / 查询对话）
- [x] 简单对话
- [x] 多模态简单对话
- [x] 非多模态 RAG 对话（指定多个知识库）
- [ ] 多模态 RAG 对话
- [ ] 文档内图片提取 + 多模态描述入库

### 前端

- [x] 对话界面
- [x] 知识库管理界面
- [x] 知识库下附件管理界面

## 项目结构

```
knowledge-base/                  Maven 父项目
├── knowledge-base-bom/          依赖版本管理（BOM）
├── knowledge-base-core/         公共模块：统一响应、全局异常、对象存储抽象、基础实体
├── knowledge-base-system/       Spring Boot 启动模块（入口 org.nodoer.system.SystemApp）
└── knowledge-base-ui/           Umi.js 前端（独立于 Maven 构建）
```

## 环境要求

- **Node.js** v18+
- **JDK** 17
- **Maven** 3.8+
- **PostgreSQL** 14+（需安装 `pgvector` 扩展）
- **MinIO**（对象存储）
- **Ollama**（可选，本地跑 bge-m3 嵌入模型；也可以走远程 OpenAI 兼容接口）

## 数据库初始化

```bash
# 创建数据库后执行：
psql -U pguser -d pgtest -f sql/postgresql/init.sql
```

初始化脚本会创建 `vector` 扩展以及 `system_local_user`、`system_role` 等基础表；`chat_*`、`knowledge_base` 等业务表由 MyBatis-Plus + pgvector 自动初始化。

默认开发环境账号：`pguser` / `123456`，连接 `jdbc:postgresql://localhost:5432/pgtest`。

## 启动后端

1. 修改配置文件：
   - `knowledge-base-system/src/main/resources/application.yml`
   - `knowledge-base-system/src/main/resources/llm.yml`（或新增 `llm-dev.yml`）

   `application.yml` 中的 `spring.config.import` 指向 `classpath:llm-dev.yml`（被 `.gitignore` 忽略），请将实际配置写到该文件，或在提交时改回 `llm.yml`：

   ```yaml
   spring:
     config:
       import: classpath:llm-dev.yml
   ```

2. 在 IDE 中运行 `org.nodoer.system.SystemApp`，或执行：

   ```bash
   mvn -pl knowledge-base-system -am spring-boot:run
   ```

   服务默认监听 `:8788`，前缀 `/api`，OpenAPI 文档见 `http://localhost:8788/api/doc.html`。

## 启动前端

```bash
cd knowledge-base-ui
npm install
npm run dev
```

启动后访问 `http://localhost:3000`。前端通过 `/api` 代理转发到后端 `http://localhost:8788/api`，启动前请确保后端已运行。

### 常用脚本

| 命令 | 说明 |
| --- | --- |
| `npm run dev` | 启动开发服务器（同时也是 `npm start`） |
| `npm run build` | 生产构建 |
| `npm run format` | Prettier 格式化 |
| `npm run openapi` | 从运行中的后端拉取 OpenAPI 文档并生成前端类型/请求方法 |

## 配置说明

`llm.yml`（`llm-dev.yml`）集中管理模型配置，字段含义：

```yaml
chat:
  simple:        # 通用对话模型
    base-url: https://api.example.com
    api-key: sk-xxx
    model: deepseek-v3-250324
  long:          # 超长文本对话模型
    base-url: https://api.example.com
    api-key: sk-xxx
    model: Baichuan2-Turbo-192k
  multimodal:    # 多模态对话模型
    base-url: https://api.example.com
    api-key: sk-xxx
    model: gpt-4o-mini
embedding:
  base-url: http://localhost:11434  # Ollama 本地
  model: bge-m3
```

> pgvector 表名（`application-dev.yml` 中 `vector_store_bge_m3`）和 `dimensions: 1024` 需与嵌入模型保持一致；切换 embedding 模型时同步修改。

## 演示

界面截图见 `docs/images/`。

## 许可证

本项目仅供学习交流使用。
