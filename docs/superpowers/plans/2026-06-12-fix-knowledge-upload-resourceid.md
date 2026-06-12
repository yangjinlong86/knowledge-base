# 修复知识库文件上传未写入 OriginFileResource 导致的列表 NPE 实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 让 `OriginFileResourceServiceImpl#uploadFile(MultipartFile, String)` 真正把文件上传到 MinIO 并写入 `origin_file_source` 行，把返回的 id 回填到 `document_entity.resource_id`，修复 `DocumentEntityServiceImpl#transfer()` 在 `GET /document/list` 时抛 NPE 的问题。

**Architecture:** 单文件行为修复。还原先前被注释掉的 `this.upload(file, KNOWLEDGE_BUCKET_NAME)` 调用，把返回的 `OriginFileResource` 的 id 写入 `DocumentEntity.resourceId`；Tika 读取仍然直接从 `MultipartFile` 拿字节流（保留目前的简化路径，不再从 MinIO 拉文件）。整体改动局限在 `OriginFileResourceServiceImpl` 一个方法 + 它的单元测试。

**Tech Stack:** Spring Boot 3.5.7、Java 17、Spring AI 1.0、MyBatis-Plus 3.5.15、JUnit 5.10.3、Mockito 5.12.0、Apache Tika、MinIO（`ObjectStoreService` 抽象）。

**根因回顾：** 详见 `/Users/yangjl/.claude/projects/-Users-yangjl-local-github-yangjl-knowledge-base/memory/...` 对话历史中的分析。`origin_file_source` 表里没有对应行，导致 `selectById(resourceId)` 返回 `null`，紧接着 `originFileResource.getBucketName()` 抛 NPE。

---

## 文件改动清单

| 文件 | 改动 | 行号 |
|------|------|------|
| `knowledge-base-system/src/main/java/org/nodoer/system/service/ai/impl/OriginFileResourceServiceImpl.java` | 还原 `this.upload(file, KNOWLEDGE_BUCKET_NAME)`；写入 `documentEntity.setResourceId(upload.getId())`；同步修改 path 等字段；调整后续 Tika 读取逻辑 | `113-159` |
| `knowledge-base-system/src/test/java/org/nodoer/system/service/ai/impl/OriginFileResourceServiceImplTest.java` | 给现有测试补 MinIO mock；新增 `uploadFile_shouldPersistOriginFileResourceAndLinkResourceId` 测试 | `53-87` |

**不涉及改动的文件：** `DocumentEntityServiceImpl`、`AIChatServiceImpl`、`OriginFileResourceController`、SQL、前端。

---

## Task 1：加失败测试 + 修补现有测试的 MinIO mock

**Files:**
- Modify: `knowledge-base-system/src/test/java/org/nodoer/system/service/ai/impl/OriginFileResourceServiceImplTest.java`

**目标：** 现有测试 `uploadFile_shouldUseMarkdownAutoSplitter_whenVectorizingMarkdown` 修复后会在 `objectStoreService.getFileInfo(...).getId()` 处 NPE（因为 mock 没配）。我们先给它补 MinIO mock；再加一个**专门断言 `resourceId` 被正确设置**的新测试。这两个测试目前都会失败（前者 NPE，后者断言 mismatch），正好形成"红 → 绿"的 TDD 循环。

- [ ] **Step 1: 给现有测试补 MinIO mock**

打开 `OriginFileResourceServiceImplTest.java`，把 `uploadFile_shouldUseMarkdownAutoSplitter_whenVectorizingMarkdown`（第 53-87 行）改成下面这版。改动要点：在 `when(llmService.getVectorStore()).thenReturn(vectorStore);` 之后追加两条 `when(...)` 模拟 MinIO 上传与 `getFileInfo` 的返回；测试文件顶部 import 区域补几个静态导入。

完整新版本（直接整段替换现有方法）：

```java
@Test
void uploadFile_shouldUseMarkdownAutoSplitter_whenVectorizingMarkdown() throws Exception {
    SystemUser user = new SystemUser();
    user.setId(1L);
    SecurityContextHolder.getContext()
        .setAuthentication(new UsernamePasswordAuthenticationToken(user, null, List.of()));
    when(llmService.getVectorStore()).thenReturn(vectorStore);
    // 修复后 service 会调 MinIO 上传 + getFileInfo，必须先 mock
    when(objectStoreService.uploadFile(any(java.io.File.class), anyString(), anyString()))
        .thenReturn("/minio/path/know");
    when(objectStoreService.getFileInfo(anyString(), anyString())).thenReturn(new StorageFile() {
        @Override public String getId() { return "origin-id-existing"; }
        @Override public String getBucketName() { return "knowledge-file"; }
        @Override public String getObjectName() { return "1/abc-test.md"; }
        @Override public String getContentType() { return "text/markdown"; }
        @Override public String getFileName() { return "test.md"; }
        @Override public String getPath() { return "/minio/path/know"; }
        @Override public Long getSize() { return 64L; }
        @Override public String getMd5() { return "deadbeef"; }
    });
    when(documentEntityMapper.insert(any(DocumentEntity.class))).thenAnswer(invocation -> {
        DocumentEntity documentEntity = invocation.getArgument(0);
        documentEntity.setId(10L);
        return 1;
    });

    OriginFileResourceServiceImpl real = new OriginFileResourceServiceImpl(objectStoreService,
            documentEntityMapper, tokenTextSplitter, llmService);
    OriginFileResourceServiceImpl service = Mockito.spy(real);
    // 屏蔽 saveOrUpdate 走数据库（受 MyBatis-Plus ServiceImpl 控制，没有 OriginFileResourceMapper 注入）
    doNothing().when(service).saveOrUpdate(any(OriginFileResource.class));

    String markdown = "# Overview\n\n" + "| Name | Age |\n" + "|------|-----|\n" + "| A    | 20  |"
        + "| B    | 30  |";
    MockMultipartFile file = new MockMultipartFile("file", "test.md", "text/markdown",
            markdown.getBytes(StandardCharsets.UTF_8));

    service.uploadFile(file, "kb-1");

    ArgumentCaptor<List<Document>> documentsCaptor = ArgumentCaptor.forClass(List.class);
    verify(vectorStore).accept(documentsCaptor.capture());
    List<Document> documents = documentsCaptor.getValue();
    assertTrue(documents.stream().anyMatch(document -> "Overview".equals(document.getMetadata().get("heading"))));
    assertTrue(documents.stream()
        .anyMatch(document -> document.getText().contains("| Name | Age |")
                && document.getText().contains("| B    | 30  |")));
    Document document = documents.get(0);
    assertEquals(1L, document.getMetadata().get("user_id"));
    assertEquals("kb-1", document.getMetadata().get("knowledge_base_id"));
    assertEquals(10L, document.getMetadata().get("document_id"));
    verify(documentEntityMapper).insert(any(DocumentEntity.class));
}
```

把现有顶部 import 区域替换为：

```java
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import org.nodoer.core.service.objectstore.ObjectStoreService;
import org.nodoer.core.service.objectstore.StorageFile;
import org.nodoer.system.mapper.DocumentEntityMapper;
import org.nodoer.system.model.entity.ai.DocumentEntity;
import org.nodoer.system.model.entity.ai.OriginFileResource;
import org.nodoer.system.model.entity.user.SystemUser;
import org.nodoer.system.service.ai.LLMService;
import org.springframework.ai.document.Document;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
```

注意：
- 现有 import 区域里有 `import org.nodoer.core.service.objectstore.ObjectStoreService;`（第 3 行）和 `import org.nodoer.system.service.ai.LLMService;`（第 7 行）等。新版用**完整重排的 import 块**整段替换即可，避免漏改。
- 如果 import `org.nodoer.core.service.objectstore.ObjectStoreService` 重复出现，整合到顶部一次。

- [ ] **Step 2: 添加新测试 `uploadFile_shouldPersistOriginFileResourceAndLinkResourceId`**

把新测试方法追加到第 1 步修改后的 `uploadFile_shouldUseMarkdownAutoSplitter_whenVectorizingMarkdown` **之后**（即类体末尾、`}` 之前）。完整代码：

```java
@Test
void uploadFile_shouldPersistOriginFileResourceAndLinkResourceId() throws Exception {
    SystemUser user = new SystemUser();
    user.setId(1L);
    SecurityContextHolder.getContext()
        .setAuthentication(new UsernamePasswordAuthenticationToken(user, null, List.of()));
    when(llmService.getVectorStore()).thenReturn(vectorStore);
    when(objectStoreService.uploadFile(any(java.io.File.class), anyString(), anyString()))
        .thenReturn("/minio/path/know");
    when(objectStoreService.getFileInfo(anyString(), anyString())).thenReturn(new StorageFile() {
        @Override public String getId() { return "origin-id-123"; }
        @Override public String getBucketName() { return "knowledge-file"; }
        @Override public String getObjectName() { return "1/xyz-test.md"; }
        @Override public String getContentType() { return "text/markdown"; }
        @Override public String getFileName() { return "test.md"; }
        @Override public String getPath() { return "/minio/path/know"; }
        @Override public Long getSize() { return 64L; }
        @Override public String getMd5() { return "deadbeef"; }
    });
    ArgumentCaptor<DocumentEntity> captor = ArgumentCaptor.forClass(DocumentEntity.class);
    when(documentEntityMapper.insert(captor.capture())).thenAnswer(invocation -> {
        DocumentEntity documentEntity = invocation.getArgument(0);
        documentEntity.setId(20L);
        return 1;
    });

    OriginFileResourceServiceImpl real = new OriginFileResourceServiceImpl(objectStoreService,
            documentEntityMapper, tokenTextSplitter, llmService);
    OriginFileResourceServiceImpl service = Mockito.spy(real);
    doNothing().when(service).saveOrUpdate(any(OriginFileResource.class));

    String markdown = "# Hello\n\nworld";
    MockMultipartFile file = new MockMultipartFile("file", "test.md", "text/markdown",
            markdown.getBytes(StandardCharsets.UTF_8));

    service.uploadFile(file, "kb-2");

    DocumentEntity inserted = captor.getValue();
    assertEquals("origin-id-123", inserted.getResourceId(),
            "uploadFile 必须把 MinIO 上传后的 OriginFileResource.id 写入 document_entity.resource_id，否则 GET /document/list 会 NPE");
    verify(service).saveOrUpdate(any(OriginFileResource.class));
    verify(objectStoreService).uploadFile(any(java.io.File.class), anyString(), anyString());
    verify(objectStoreService).getFileInfo(anyString(), anyString());
}
```

- [ ] **Step 3: 编译并运行测试，验证都失败（红灯）**

```bash
mvn -pl knowledge-base-system -am test -Dtest=OriginFileResourceServiceImplTest
```

Expected: `Tests run: 2, Failures: 2, Errors: 0`
- 现有测试 `uploadFile_shouldUseMarkdownAutoSplitter_whenVectorizingMarkdown` 失败原因：NPE（`objectStoreService.getFileInfo(...)` 未被调用前的早期路径，但更要命的是当前 `documentEntity.resourceId == ""`，NPE 在 `getId` 调用上 —— 实际上当前实现根本没调 `getFileInfo`，所以这个测试**当前其实是绿的**。这一步可能在本地显示"全绿"。）

如果出现这种情况，**不要慌**：保留两个测试，继续到 Task 2 实施修复，修复后这个测试会因 NPE 失败。然后我们在 Task 2 之后再跑一次确认变绿。
- 新测试 `uploadFile_shouldPersistOriginFileResourceAndLinkResourceId` 失败原因：`AssertionFailedError`，`inserted.getResourceId() == ""` 不等于 `"origin-id-123"`。

> **红灯预期的现实情况**：因为现有测试在当前实现下"侥幸通过"，新测试在当前实现下"断言失败"，**至少一个新测试会失败**。这已经满足 TDD 红灯的要求。

---

## Task 2：实施修复

**Files:**
- Modify: `knowledge-base-system/src/main/java/org/nodoer/system/service/ai/impl/OriginFileResourceServiceImpl.java:113-159`

- [ ] **Step 1: 修改 `uploadFile(MultipartFile, String)`**

把 `OriginFileResourceServiceImpl.java` 第 113-159 行的整个 `uploadFile(MultipartFile file, String knowledgeId)` 方法替换为：

```java
@Transactional(rollbackFor = Exception.class)
@Override
public Long uploadFile(MultipartFile file, String knowledgeId) {
    // 1. 先上传文件至 MinIO 并写入 origin_file_source 表；返回的 id 必须回填到
    //    document_entity.resource_id，否则 DocumentEntityServiceImpl#transfer 在
    //    GET /document/list 时会因 selectById("") 返回 null 而 NPE。
    OriginFileResource upload = this.upload(file, KNOWLEDGE_BUCKET_NAME);

    // 2. 写入 document_entity，关联到刚刚上传的 OriginFileResource
    DocumentEntity documentEntity = new DocumentEntity();
    documentEntity.setFileName(file.getOriginalFilename());
    documentEntity.setBaseId(knowledgeId);
    documentEntity.setPath(upload.getPath());
    documentEntity.setIsEmbedding(false);
    documentEntity.setResourceId(upload.getId());
    documentEntityMapper.insert(documentEntity);

    // 3. 向量化：Tika 读取 MultipartFile 字节流（保留简化路径，不再从 MinIO 拉文件）
    Resource resource;
    try {
        InputStream inputStream = file.getInputStream();
        resource = new ByteArrayResource(inputStream.readAllBytes());
    }
    catch (IOException e) {
        throw new BusinessException(CoreCode.SYSTEM_ERROR, e.getMessage());
    }
    // 使用 Apache Tika 从原始文件中提取正文文本
    TikaDocumentReader tikaDocumentReader = new TikaDocumentReader(resource);
    List<Document> rawDocumentList = tikaDocumentReader.read();
    // 使用 MarkdownAutoSplitter 替代通用的 TokenTextSplitter，按 Markdown 标题/表格结构切分，
    // 避免将表格或同标题下的内容打散到不同数据块中
    MarkdownAutoSplitter markdownSplitter = new MarkdownAutoSplitter();
    List<Document> splitDocumentList = markdownSplitter
        .split(rawDocumentList.stream().map(Document::getText).collect(Collectors.joining("\n\n")));
    // 为每个数据块补充用户、知识库和文档关联元数据，然后批量写入向量数据库
    List<Document> hasMetaDocumentList = splitDocumentList.stream().map(item -> {
        Map<String, Object> metadata = new HashMap<>(item.getMetadata());
        metadata.put("user_id", SecurityFrameworkUtil.getCurrUserId());
        metadata.put("knowledge_base_id", knowledgeId);
        metadata.put("document_id", documentEntity.getId());
        return new Document(item.getText(), metadata);
    }).toList();
    VectorStore vectorStore = llmService.getVectorStore();
    vectorStore.accept(hasMetaDocumentList);

    // 4. 更新
    documentEntity.setIsEmbedding(true);
    documentEntityMapper.updateById(documentEntity);
    return documentEntity.getId();
}
```

> **注释规范提醒（按 `CLAUDE.md` 约定）**：本次新增/修改的 Java 代码必须配详细中文注释。上方代码块里的中文 `//` 行已经覆盖"为什么这么做"和"做了什么"。Javadoc 已经存在（`uploadFile` 方法原本就有），无需重复。

- [ ] **Step 2: 重新跑测试，验证绿灯**

```bash
mvn -pl knowledge-base-system -am test -Dtest=OriginFileResourceServiceImplTest
```

Expected: `Tests run: 2, Failures: 0, Errors: 0, Skipped: 0`

如果失败：
- `NullPointerException` 来自 `objectStoreService.getFileInfo(...)` → 确认 Step 1 的 mock 是否正确添加
- `AssertionFailedError` 来自 `inserted.getResourceId() == ""` → 确认 Step 1 的 `documentEntity.setResourceId(upload.getId())` 是否被应用

- [ ] **Step 3: 跑全模块测试，确认没有破坏其它测试**

```bash
mvn -pl knowledge-base-system -am test
```

Expected: `Tests run: 12, Failures: 0, Errors: 0, Skipped: 0`（基线 11 个 + 本次新增 1 个：`MarkdownAutoSplitterTest` 10 个 + `OriginFileResourceServiceImplTest` 修复后 2 个）

如果其它测试失败，**STOP** 并回到 Task 2 Step 1 检查 `documentEntity` 字段改动是否影响 `transfer` 等下游消费者。

- [ ] **Step 4: 编译验证**

```bash
mvn -pl knowledge-base-system -am compile
```

Expected: `BUILD SUCCESS`（同时 `spring-javaformat-maven-plugin` 应自动格式化；如果格式不匹配会被 `validate` 阶段拒绝，按报错调整缩进/换行即可。）

- [ ] **Step 5: 提交**

```bash
git add knowledge-base-system/src/main/java/org/nodoer/system/service/ai/impl/OriginFileResourceServiceImpl.java \
        knowledge-base-system/src/test/java/org/nodoer/system/service/ai/impl/OriginFileResourceServiceImplTest.java
git commit -m "fix(knowledge-upload): persist OriginFileResource and link document_entity.resource_id

DocumentEntityServiceImpl#transfer 在 GET /document/list 时抛 NPE，根因是
OriginFileResourceServiceImpl#uploadFile(MultipartFile, String) 跳过了
this.upload(file, KNOWLEDGE_BUCKET_NAME)，导致 origin_file_source 表里没有
对应行，document_entity.resource_id 写死成空串。

还原上传调用并把返回的 OriginFileResource.id 回填到 resourceId，恢复
MinIO 原始文件持久化、列表预览、文件下载、多模态聊天附图等下游消费者。
Tika 仍直接从 MultipartFile 读取字节流（保留原简化路径）。"
```

---

## 验证清单（提交后手动跑）

| 步骤 | 命令 | 预期 |
|------|------|------|
| 启动后端 | `mvn -pl knowledge-base-system -am spring-boot:run` | 启动日志看到 `Started SystemApp` |
| 登录前端 | `cd knowledge-base-ui && pnpm dev`，浏览器开 `:3000` | 跳到登录页 |
| 上传文档 | 选知识库 → 上传一个 `.md` 文件 | 200 OK + 返回 documentId |
| 看数据库 | `psql -c 'select id, file_name, resource_id from document_entity order by id desc limit 5;'` | 资源上传过的 `resource_id` **非空**（形如 `origin-id-xxx`），与 `origin_file_source.id` 对得上 |
| 看 MinIO | MinIO 控制台 `knowledge-file` 桶 | 看到对应对象 |
| 列文档 | 浏览器回到文档列表页 | 不再 NPE，能看到文件名 + 预览 URL |
| 重新上传老数据后看 list | 找一个 NPE 之前上传的"孤儿" `document_entity` | 仍然会 NPE —— 修复**只对修复后新上传的文档有效**（旧数据的 resource_id="" 是历史遗留，按需手动回填或重新上传） |

---

## 风险与缓解

| 风险 | 缓解 |
|------|------|
| 修复后 `uploadFile` 调 MinIO 多一次 IO + 一次 DB 写 | 上传本来就该持久化原始文件，下游 4 处消费者需要它；这是恢复正确行为而非额外开销 |
| 旧"孤儿" `document_entity` 记录（resource_id=""）依然会让 list 抛 NPE | 列在验证清单里写明；如需回填旧数据可另起一项任务 |
| `tokenTextSplitter` 字段依然是死代码（生产用 `MarkdownAutoSplitter`） | 已知问题，本次**不**在范围内 |
| `OriginFileResourceServiceImplTest` 缺 `@Mock private OriginFileResourceMapper originFileResourceMapper;`，通过 `Mockito.spy` + `doNothing().when(service).saveOrUpdate(...)` 绕过 | 干净、聚焦，不引入额外测试基础设施 |

---

## 后续工作（不在本次范围）

- 旧"孤儿" `document_entity` 记录的回填脚本（可写一个 SQL + 一次性 Java 工具）
- `tokenTextSplitter` 字段清理（删除或真正接进 `MarkdownAutoSplitter` 之外的兜底路径）
- `multimodalRAGChat` 仍然返回 `null`（来自上一轮遗留）
