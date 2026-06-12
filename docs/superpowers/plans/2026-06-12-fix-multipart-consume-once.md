# 修复 MultipartFile 在 transferTo 之后无法再读 实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 在 `OriginFileResourceServiceImpl` 入口一次性把 `MultipartFile` 读进 `byte[]`，Tika 与 `upload()` 私有助手都消费这份缓存，消除 `StandardMultipartFile.transferTo()`（move 语义）消费底层 temp 文件后再次读取抛 `IOException` 的隐患；同时让 chat 路径和知识库路径走相同的"先读 bytes"入口，避免 chat 路径未来踩同一个坑。

**Architecture:** 把 `private OriginFileResource upload(MultipartFile file, String bucketName)` 重构为 `private OriginFileResource upload(String originalFilename, byte[] content, String bucketName)`。两个公开方法 `uploadFile(MultipartFile)` 和 `uploadFile(MultipartFile, String)` 在第一行就 `file.getBytes()`（try/catch 抛 `BusinessException`），把 `bytes` 传给 helper 和 Tika。helper 内部用 `Files.write(tmpFile.toPath(), bytes)` 替代 `file.transferTo(tmpFile)`，MD5 也直接从 `bytes` 算。Spring `MockMultipartFile` 的 `transferTo` 是 copy 语义，所以现有 2 个 `MockMultipartFile`-based 测试本来就没法覆盖本 bug；新加一个嵌套测试 `TransferToConsumesMultipartFile` 模拟 `StandardMultipartFile` 的 move 行为。

**Tech Stack:** Spring Boot 3.5.7、Java 17、Spring `MultipartFile`、Servlet 6 `Part`、JUnit 5.10.3、Mockito 5.12.0。

**根因回顾：** Spring `StandardMultipartFile.transferTo(File)` 内部调用 `Part.write(filename)`，是 move 而非 copy。一旦 move 成功，原始 multipart temp 文件（Tomcat work 目录下）就被删除，`file.getInputStream()` 与 `file.getBytes()` 后续调用抛 `IOException`，且 `getMessage()` 只是返回那个 temp 路径。`MockMultipartFile` 是 in-memory 数组 + copy 语义，所以单测假象通过了；只有真实 HTTP multipart 走标准容器时才暴露。

---

## 文件改动清单

| 文件 | 改动 | 行号 |
|------|------|------|
| `knowledge-base-system/src/main/java/org/nodoer/system/service/ai/impl/OriginFileResourceServiceImpl.java` | 把 `private upload(MultipartFile, String)` 重构为 `upload(String, byte[], String)`；改 `uploadFile(MultipartFile)` 与 `uploadFile(MultipartFile, String)` 入口预读 bytes；Tika 改用 `ByteArrayResource(bytes)` | `94-99` (chat 入口) / `113-162` (knowledge 入口) / `181-210` (private 助手) |
| `knowledge-base-system/src/test/java/org/nodoer/system/service/ai/impl/OriginFileResourceServiceImplTest.java` | 加嵌套 `TransferToConsumesMultipartFile` helper；加 2 个新测试方法（知识库路径 TDD 红相 + chat 路径冒烟） | 新增（追加在已有 2 个 `@Test` 之后） |

**不涉及改动的文件：** `DocumentEntityServiceImpl`、`AIChatServiceImpl`、`OriginFileResourceController`、SQL、前端。

---

## Task 1：加失败测试 —— 知识库路径在 multipart 被消费后仍能完成向量化

**Files:**
- Modify: `knowledge-base-system/src/test/java/org/nodoer/system/service/ai/impl/OriginFileResourceServiceImplTest.java`

**目标：** 添加一个**模拟真实容器 multipart move 行为**的 `MultipartFile` 实现，并在新测试里调用 `service.uploadFile(file, "kb-3")`。当前实现会在 `this.upload()` 内部 `file.transferTo(tmpFile)` 之后再次 `file.getInputStream()` 失败，从而抛 `BusinessException` —— 这就是红灯。修复后入口先 `file.getBytes()` 缓存，避开了第二次读，灯转绿。

- [ ] **Step 1: 在测试类中添加嵌套 helper `TransferToConsumesMultipartFile`**

把以下嵌套类追加到 `OriginFileResourceServiceImplTest` 的类体末尾（即 `}` 之前）。同时把顶部 import 区域追加 `import org.springframework.web.multipart.MultipartFile;`：

```java
/**
 * 模拟 Spring StandardMultipartFile / Servlet Part 的 move 语义：
 * - transferTo(File) 之后，多部分原始 temp 文件被 move 走
 * - 之后再调 getInputStream() / getBytes() 抛 IOException，message 即 temp 路径
 * 用以覆盖真实容器场景下 OriginFileResourceServiceImpl#uploadFile 的
 * "MultipartFile 一次性消费"行为（MockMultipartFile 是 copy 语义，覆盖不到）。
 */
static class TransferToConsumesMultipartFile implements MultipartFile {

    private final String filename;

    private final String contentType;

    private final byte[] content;

    private boolean transferred = false;

    TransferToConsumesMultipartFile(String filename, String contentType, byte[] content) {
        this.filename = filename;
        this.contentType = contentType;
        this.content = content;
    }

    @Override
    public String getName() {
        return "file";
    }

    @Override
    public String getOriginalFilename() {
        return filename;
    }

    @Override
    public String getContentType() {
        return contentType;
    }

    @Override
    public boolean isEmpty() {
        return content.length == 0;
    }

    @Override
    public long getSize() {
        return content.length;
    }

    // 关键：transferTo 之后这两个方法必须抛 IOException，模拟 move 走 temp 后的真实行为
    @Override
    public byte[] getBytes() throws IOException {
        if (transferred) {
            throw new IOException("/private/var/folders/zz/zzz/upload_" + filename + ".tmp");
        }
        return content;
    }

    @Override
    public InputStream getInputStream() throws IOException {
        if (transferred) {
            throw new IOException("/private/var/folders/zz/zzz/upload_" + filename + ".tmp");
        }
        return new ByteArrayInputStream(content);
    }

    @Override
    public org.springframework.core.io.Resource getResource() {
        return new org.springframework.core.io.ByteArrayResource(content);
    }

    // 关键：transferTo 之后必须标记 transferred=true
    @Override
    public void transferTo(File dest) throws IOException, IllegalStateException {
        transferred = true;
        java.nio.file.Files.write(dest.toPath(), content);
    }

}
```

顶部 import 区域追加：

```java
import org.springframework.web.multipart.MultipartFile;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.InputStream;
```

> **为什么 mock helper 用 `static class` + 嵌套**：只在这个测试文件里用，没必要污染包级命名空间。`static` 让 JUnit 5 / Mockito 框架能正常实例化。

- [ ] **Step 2: 添加新测试 `uploadFile_shouldNotReReadMultipartAfterTransferTo`**

把以下方法追加到 `uploadFile_shouldPersistOriginFileResourceAndLinkResourceId` 之后（即类体末尾、`}` 之前）：

```java
@Test
void uploadFile_shouldNotReReadMultipartAfterTransferTo() throws Exception {
    SystemUser user = new SystemUser();
    user.setId(1L);
    SecurityContextHolder.getContext()
        .setAuthentication(new UsernamePasswordAuthenticationToken(user, null, List.of()));
    when(llmService.getVectorStore()).thenReturn(vectorStore);
    when(objectStoreService.uploadFile(any(java.io.File.class), anyString(), anyString()))
        .thenReturn("/minio/path/know");
    when(objectStoreService.getFileInfo(anyString(), anyString())).thenReturn(new StorageFile() {
        @Override public String getId() { return "origin-id-consumed"; }
        @Override public String getBucketName() { return "knowledge-file"; }
        @Override public String getObjectName() { return "1/consumed.md"; }
        @Override public String getContentType() { return "text/markdown"; }
        @Override public String getFileName() { return "consumed.md"; }
        @Override public String getPath() { return "/minio/path/know"; }
        @Override public Long getSize() { return 64L; }
        @Override public String getMd5() { return "deadbeef"; }
    });
    ArgumentCaptor<DocumentEntity> captor = ArgumentCaptor.forClass(DocumentEntity.class);
    when(documentEntityMapper.insert(captor.capture())).thenAnswer(invocation -> {
        DocumentEntity documentEntity = invocation.getArgument(0);
        documentEntity.setId(30L);
        return 1;
    });

    OriginFileResourceServiceImpl real = new OriginFileResourceServiceImpl(objectStoreService, documentEntityMapper,
            tokenTextSplitter, llmService);
    OriginFileResourceServiceImpl service = Mockito.spy(real);
    doReturn(true).when(service).saveOrUpdate(any(OriginFileResource.class));

    // 使用模拟真实容器 move 语义的 MultipartFile：transferTo 之后 getBytes/getInputStream 都会抛 IOException
    byte[] md = "# Hello\n\nworld".getBytes(StandardCharsets.UTF_8);
    TransferToConsumesMultipartFile file = new TransferToConsumesMultipartFile("consumed.md", "text/markdown", md);

    // 当前实现：service 内部 this.upload() 调 file.transferTo() 之后，Tika 路径再次 file.getInputStream() 会抛 IOException
    // 期望修复后：service 在入口一次性 file.getBytes()，Tika 与 upload 助手共用缓存，不再依赖已被 move 走的 MultipartFile
    service.uploadFile(file, "kb-3");

    // 修复后断言：documentEntity.resourceId 正确指向 MinIO 返回的 id
    DocumentEntity inserted = captor.getValue();
    assertEquals("origin-id-consumed", inserted.getResourceId(),
            "uploadFile 必须在入口预读 MultipartFile 字节，避免 Tika 路径在 transferTo 之后再次读取失败");
}
```

- [ ] **Step 3: 运行测试，验证红灯**

```bash
mvn -pl knowledge-base-system -am test -Dtest=OriginFileResourceServiceImplTest#uploadFile_shouldNotReReadMultipartAfterTransferTo -Dsurefire.failIfNoSpecifiedTests=false
```

Expected: FAIL。失败堆栈应类似：

```
java.io.IOException: /private/var/folders/zz/zzz/upload_consumed.md.tmp
  at org.springframework.web.multipart.MultipartFile.getBytes(...)
  ...
  at org.nodoer.system.service.ai.impl.OriginFileResourceServiceImpl.uploadFile(OriginFileResourceServiceImpl.java:133)
```

或被 catch 块包成 `BusinessException`。**关键信号**：失败位置在 `uploadFile(MultipartFile, String)` 第 133-134 行（`InputStream inputStream = file.getInputStream(); resource = new ByteArrayResource(inputStream.readAllBytes());`）。

> **如果失败信息不指向 `getInputStream()`**：停下来贴回现象，不要猜。可能是嵌套 helper 没生效，或 import 漏了。

---

## Task 2：实施修复 —— 重构 upload 助手 + 预读 bytes

**Files:**
- Modify: `knowledge-base-system/src/main/java/org/nodoer/system/service/ai/impl/OriginFileResourceServiceImpl.java`

- [ ] **Step 1: 把 `private upload(MultipartFile, String)` 重构为 `upload(String, byte[], String)`**

把第 181-210 行的整个 `private OriginFileResource upload(MultipartFile file, String bucketName)` 方法替换为：

```java
/**
 * 把字节流上传至 MinIO 并写入 origin_file_source 表。
 *
 * <p>
 * 接收 {@code byte[]} 而非 {@link MultipartFile}：Spring 的
 * {@code StandardMultipartFile.transferTo(File)} 内部是 move 而非 copy，
 * 一旦 move 成功，原始 multipart temp 文件就被删，调用方再读
 * {@code MultipartFile.getInputStream()} / {@code getBytes()} 会抛
 * {@code IOException}。所有调用方必须在入口预读 bytes，再传进来。
 * </p>
 * @param originalFilename 原始文件名（用于写入 origin_file_source.fileName 与 objectName 拼接）
 * @param content 文件字节流
 * @param bucketName MinIO 桶名
 * @return 写入数据库后的 OriginFileResource（含 id）
 */
private OriginFileResource upload(String originalFilename, byte[] content, String bucketName) {
    String objectName = objectNameWithUserId(originalFilename);
    String id = FileUtil.generatorFileId(bucketName, objectName);
    String newObjectName = String.format("%s/%s", objectName, id);
    String path;
    String md5;
    try {
        File tmpFile = FileUtil.createTempFile("know", "_" + originalFilename);
        // 直接把字节写到临时文件，绕开 MultipartFile.transferTo() 的 move 副作用
        Files.write(tmpFile.toPath(), content);
        md5 = FileUtil.md5(tmpFile);
        path = objectStoreService.uploadFile(tmpFile, bucketName, newObjectName);
    }
    catch (IOException e) {
        throw new BusinessException(CoreCode.SYSTEM_ERROR, e.getMessage());
    }
    StorageFile fileInfo = objectStoreService.getFileInfo(bucketName, newObjectName);
    OriginFileResource originFileResource = new OriginFileResource();
    originFileResource.setMd5(md5);
    originFileResource.setFileName(originalFilename);
    originFileResource.setPath(path);
    originFileResource.setId(fileInfo.getId());
    originFileResource.setBucketName(bucketName);
    originFileResource.setObjectName(newObjectName);
    // isImage 需要 contentType 才能判断；新签名不传 MultipartFile，固定 false。
    // 真正需要 isImage 的调用方（chat 路径）在 helper 返回后显式覆盖。
    originFileResource.setIsImage(false);
    originFileResource.setSize(fileInfo.getSize());
    originFileResource.setContentType(fileInfo.getContentType());
    this.saveOrUpdate(originFileResource);
    return originFileResource;
}
```

> **重要变更**：
> 1. `originalFilename`、`content` 是新参数；`MultipartFile` 不再传
> 2. `Files.write(tmpFile.toPath(), content)` 替代 `file.transferTo(tmpFile)`
> 3. `isImage` 字段：原实现 `file.getContentType().startsWith("image")`。新签名拿不到 contentType；先固定 `false`，下面 Step 2 的 chat 入口会显式覆盖为正确值（chat 路径需要 isImage 区分图片/其他）

- [ ] **Step 2: 更新 `uploadFile(MultipartFile)` (chat 路径) 入口预读 bytes**

把第 94-99 行的 `uploadFile(MultipartFile file)` 替换为：

```java
@Transactional(rollbackFor = Exception.class)
@Override
public String uploadFile(MultipartFile file) {
    // 入口一次性预读：StandardMultipartFile 的 transferTo() 是 move 语义，
    // 一次上传路径里只能在 helper 内部消费一次字节流。提前缓存到 byte[]。
    String originalFilename = file.getOriginalFilename();
    String contentType = file.getContentType();
    byte[] content;
    try {
        content = file.getBytes();
    }
    catch (IOException e) {
        throw new BusinessException(CoreCode.SYSTEM_ERROR, e.getMessage());
    }
    OriginFileResource upload = this.upload(originalFilename, content, CHAT_BUCKET_NAME);
    // chat 路径需要 isImage 区分（多模态聊天按 image / file 走不同处理）
    upload.setIsImage(contentType != null && contentType.startsWith("image"));
    this.saveOrUpdate(upload);
    return upload.getId();
}
```

- [ ] **Step 3: 更新 `uploadFile(MultipartFile, String)` (knowledge 路径) 入口预读 bytes**

把第 113-162 行的 `uploadFile(MultipartFile file, String knowledgeId)` 替换为：

```java
/**
 * 上传知识库文件并完成向量化入库。
 *
 * <p>
 * 该方法面向知识库文件上传场景：先创建文档实体记录，再通过 Tika 读取文件正文， 使用 {@link MarkdownAutoSplitter} 按
 * Markdown 结构切分文本，最后写入向量库。 MarkdownAutoSplitter 会尽量保留标题层级和表格结构，避免通用文本切分破坏 Markdown
 * 语义。
 * </p>
 *
 * <p>
 * 关键约束：{@link MultipartFile} 在 StandardMultipartFile 实现下，{@code transferTo()} 是 move 语义，
 * 一旦 move 成功原始 temp 文件就被删除，{@code getInputStream()} 再次调用会抛
 * {@code IOException}。本方法在入口一次性 {@code file.getBytes()} 把字节缓存到
 * {@code byte[]}，{@code upload()} 助手和 Tika 读取都消费这份缓存，不再依赖
 * 已被 move 走的 MultipartFile。
 * </p>
 * @param file 用户上传的原始文件
 * @param knowledgeId 目标知识库 ID
 * @return 数据库中的文档实体 ID
 */
@Transactional(rollbackFor = Exception.class)
@Override
public Long uploadFile(MultipartFile file, String knowledgeId) {
    // 0. 入口预读：缓存到 byte[] 之后才能既给 upload() 助手用、也给 Tika 用
    String originalFilename = file.getOriginalFilename();
    byte[] content;
    try {
        content = file.getBytes();
    }
    catch (IOException e) {
        throw new BusinessException(CoreCode.SYSTEM_ERROR, e.getMessage());
    }

    // 1. 上传至 MinIO 并写入 origin_file_source 表；返回的 id 必须回填到
    //    document_entity.resource_id，否则 DocumentEntityServiceImpl#transfer 在
    //    GET /document/list 时会因 selectById("") 返回 null 而 NPE。
    OriginFileResource upload = this.upload(originalFilename, content, KNOWLEDGE_BUCKET_NAME);

    // 2. 写入 document_entity，关联到刚刚上传的 OriginFileResource
    DocumentEntity documentEntity = new DocumentEntity();
    documentEntity.setFileName(originalFilename);
    documentEntity.setBaseId(knowledgeId);
    documentEntity.setPath(upload.getPath());
    documentEntity.setIsEmbedding(false);
    documentEntity.setResourceId(upload.getId());
    documentEntityMapper.insert(documentEntity);

    // 3. 向量化：Tika 直接读预读的 bytes（不再调 file.getInputStream()）
    Resource resource = new ByteArrayResource(content);
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

- [ ] **Step 4: 重新跑新测试，验证绿灯**

```bash
mvn -pl knowledge-base-system -am test -Dtest=OriginFileResourceServiceImplTest#uploadFile_shouldNotReReadMultipartAfterTransferTo -Dsurefire.failIfNoSpecifiedTests=false
```

Expected: `Tests run: 1, Failures: 0, Errors: 0, Skipped: 0`

如果失败：
- 失败信息提到 `getInputStream` 或 `IOException` → 你没在入口预读 `file.getBytes()`，回到 Step 2/3
- 失败信息提到 Mockito 异常 → spy/`saveOrUpdate` 模拟没生效
- 其它错误 → 贴回堆栈

---

## Task 3：加 chat 路径冒烟测试 + 跑全量 + 提交

**Files:**
- Modify: `knowledge-base-system/src/test/java/org/nodoer/system/service/ai/impl/OriginFileResourceServiceImplTest.java`

**目标：** chat 路径（`uploadFile(MultipartFile)`）本次没有 bug 暴露，但被同步重构了。补一个最小冒烟测试，验证 chat 路径在重构后仍能用预读 bytes 正确上传；防止有人未来给 chat 路径加 Tika 类二次消费时悄悄踩坑。

- [ ] **Step 1: 添加 chat 路径冒烟测试 `uploadFile_chat_shouldUploadWithPreReadBytes`**

把以下方法追加到上一个新测试之后（即类体末尾、`}` 之前）：

```java
@Test
void uploadFile_chat_shouldUploadWithPreReadBytes() throws Exception {
    when(objectStoreService.uploadFile(any(java.io.File.class), anyString(), anyString()))
        .thenReturn("/minio/path/chat");
    when(objectStoreService.getFileInfo(anyString(), anyString())).thenReturn(new StorageFile() {
        @Override public String getId() { return "origin-id-chat-1"; }
        @Override public String getBucketName() { return "origin-file"; }
        @Override public String getObjectName() { return "1/chat-1.png"; }
        @Override public String getContentType() { return "image/png"; }
        @Override public String getFileName() { return "chat-1.png"; }
        @Override public String getPath() { return "/minio/path/chat"; }
        @Override public Long getSize() { return 32L; }
        @Override public String getMd5() { return "cafebabe"; }
    });

    OriginFileResourceServiceImpl real = new OriginFileResourceServiceImpl(objectStoreService, documentEntityMapper,
            tokenTextSplitter, llmService);
    OriginFileResourceServiceImpl service = Mockito.spy(real);
    doReturn(true).when(service).saveOrUpdate(any(OriginFileResource.class));

    // 用 TransferToConsumesMultipartFile 验证 chat 路径也不依赖 transferTo 后的 MultipartFile
    byte[] pngBytes = new byte[] { (byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1a, '\n' };
    TransferToConsumesMultipartFile file = new TransferToConsumesMultipartFile("chat-1.png", "image/png", pngBytes);

    String id = service.uploadFile(file);

    assertEquals("origin-id-chat-1", id, "chat 路径必须返回 MinIO 返回的 OriginFileResource.id");
    ArgumentCaptor<OriginFileResource> captor = ArgumentCaptor.forClass(OriginFileResource.class);
    // 两次 saveOrUpdate 都被调用：第一次 helper 内部，第二次 chat 入口覆盖 isImage
    verify(service, Mockito.times(2)).saveOrUpdate(captor.capture());
    OriginFileResource lastSaved = captor.getValue();
    assertTrue(lastSaved.getIsImage(), "image/png 的文件 isImage 必须为 true");
}
```

- [ ] **Step 2: 跑全模块测试，确认无回归**

```bash
mvn -pl knowledge-base-system -am test
```

Expected: `Tests run: 14, Failures: 0, Errors: 0, Skipped: 0`（基线 12 + 本次新增 2）

如果某测试失败：
- 现有 2 个 `MockMultipartFile` 测试失败 → `MockMultipartFile` 也实现了 `getBytes()`/`getInputStream()`，**应该**仍能跑过；若失败，看 stack 是不是 import 漏了或 helper 签名拼错
- 新增 2 个 `TransferToConsumesMultipartFile` 测试失败 → 看断言和 mock 配置

- [ ] **Step 3: 编译验证 + spring-javaformat**

```bash
mvn -pl knowledge-base-system -am compile
```

Expected: `BUILD SUCCESS`。如果 `spring-javaformat:validate` 失败，IDE 自动格式化或跑 `mvn spring-javaformat:apply`。

- [ ] **Step 4: 提交**

分两个 commit，对应"测试先行 / 修复"两步：

```bash
# 第一个 commit：测试文件（红灯 + 后续转绿）
git add knowledge-base-system/src/test/java/org/nodoer/system/service/ai/impl/OriginFileResourceServiceImplTest.java
git commit -m "test(knowledge-upload): add transferTo-consumes-multipart test

新增嵌套 TransferToConsumesMultipartFile helper 模拟 StandardMultipartFile
的 move 语义（transferTo 之后 getBytes/getInputStream 抛 IOException），
并加 2 个测试：
- uploadFile_shouldNotReReadMultipartAfterTransferTo：知识库路径 TDD 红相
- uploadFile_chat_shouldUploadWithPreReadBytes：chat 路径冒烟

MockMultipartFile 是 copy 语义，覆盖不到真实容器下的 multipart 一次性
消费场景；这两个测试用自定义 MultipartFile 实现补齐。"

# 第二个 commit：生产代码修复
git add knowledge-base-system/src/main/java/org/nodoer/system/service/ai/impl/OriginFileResourceServiceImpl.java
git commit -m "fix(knowledge-upload): pre-read MultipartFile bytes to survive transferTo

Spring StandardMultipartFile 的 transferTo() 内部调用 Part.write()，
行为是 move 而非 copy。一旦 move 成功，原始 multipart temp 文件被
删，后续 file.getInputStream()/getBytes() 抛 IOException 且 message
只是那个已删除的 temp 路径。

修复后：
- private upload() 助手改为接收 (String filename, byte[] content, String
  bucketName)，用 Files.write(tmpFile.toPath(), content) 替代
  file.transferTo()；isImage 固定为 false
- 两个 uploadFile 入口在第一行 file.getBytes() 预读缓存，upload 助手
  与 Tika 共享同一份 byte[]
- chat 路径额外在 upload 助手返回后覆盖 isImage（chat 路径需要
  区分 image 与其他文件，给多模态聊天使用）

TDD 红→绿证据：uploadFile_shouldNotReReadMultipartAfterTransferTo 在
修复前因 Tika 路径的 file.getInputStream() IOException 失败；修复后
通过。chat 路径 uploadFile_chat_shouldUploadWithPreReadBytes 验证
isImage 字段被正确覆盖。"
```

---

## 验证清单（提交后手动跑）

| 步骤 | 命令 | 预期 |
|------|------|------|
| 启动后端 | `mvn -pl knowledge-base-system -am spring-boot:run` | 启动日志看到 `Started SystemApp` |
| 启动前端 + 登录 | `cd knowledge-base-ui && pnpm dev`，浏览器开 `:3000` | 跳到登录页 |
| 上传图片到聊天 | `/chat` → 选图片上传 | 200 OK，返回 origin-id 形式字符串；后端日志看到 `Created bucket` + `File uploaded` 之后没有 IOException |
| 上传文档到知识库 | `/knowlegeBase` → 选 .md 文件上传 | 200 OK + 返回 documentId；后端日志看到 `Started SystemApp` + `PgVectorStore: ... Is empty: false` + 文档向量化成功 |
| 列表查询 | `/knowlegeBase/{id}` 列表 | 不再 NPE，文档正常列出 |
| 多模态聊天引用上传过的图 | `/chat` 引用刚才上传的图片发消息 | 能正常拉取（多模态 `OriginFileResourceServiceImpl.fromResourceId` 路径不变） |

---

## 风险与缓解

| 风险 | 缓解 |
|------|------|
| `byte[]` 在内存里，100 MB 文件占用 ~100 MB 堆 | 项目 `application.yml` 已限定 `max-file-size: 100MB`，单次上传可接受；如未来需要流式，可换成 `InputStream` 接口 + 临时文件 + 显式 `MultipartFile.getInputStream()`，但本计划不在范围 |
| `isImage` 字段从 `file.getContentType().startsWith("image")` 变成由调用方显式设置 | chat 路径在 `uploadFile(MultipartFile)` 末尾覆盖 `isImage`；知识库路径暂时不设（kb 文件很少是图片，未来需要可在 knowledge 入口加） |
| 既有 `tokenTextSplitter` 字段依然死代码 | 已知问题，本计划不动 |
| `Mockito.spy` + `doReturn(true).when(saveOrUpdate)` 仍然绕开 `ServiceImpl` 的 mapper 依赖 | 已是项目里既定的"风险已记录"模式 |
| Tika 现在从 `ByteArrayResource(bytes)` 读，原来是 `ByteArrayResource(inputStream.readAllBytes())` | 行为等价；不再依赖 InputStream，路径更短 |

---

## 后续工作（不在本次范围）

- `tokenTextSplitter` 死字段删除
- `@Transactional` + MinIO 异构资源事务边界（孤儿对象风险）：把 MinIO 上传挪到 `TransactionSynchronization#afterCommit` 钩子
- chat 路径的多模态 `Media` 构建可以走"先存到 MinIO 再生成 presigned URL"模式，绕过 `FileUtil.downloadToTempFile` 重复下载
- 知识库路径的 `isImage` 字段同步设置（当前固定为 false）
- 测试 helper `TransferToConsumesMultipartFile` 提取为可复用工具类（如果未来其它 upload 测试也需要）
- 上一轮 review 标记的 2 条 minor（`StorageFile` 匿名类抽取、移除 `Mockito.lenient()`）
