package org.nodoer.system.service.ai.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.nodoer.core.common.CoreCode;
import org.nodoer.core.exception.BusinessException;
import org.nodoer.core.service.objectstore.ObjectStoreService;
import org.nodoer.core.service.objectstore.StorageFile;
import org.nodoer.core.utils.FileUtil;
import org.nodoer.system.controller.vo.ResourceVO;
import org.nodoer.system.mapper.DocumentEntityMapper;
import org.nodoer.system.mapper.OriginFileResourceMapper;
import org.nodoer.system.model.entity.ai.DocumentEntity;
import org.nodoer.system.model.entity.ai.OriginFileResource;
import org.nodoer.system.model.entity.user.SystemUser;
import org.nodoer.system.service.ai.LLMService;
import org.nodoer.system.service.ai.OriginFileResourceService;
import org.nodoer.system.utils.MarkdownAutoSplitter;
import org.nodoer.system.utils.SecurityFrameworkUtil;
import org.springframework.ai.content.Media;
import org.springframework.ai.document.Document;
import org.springframework.ai.reader.tika.TikaDocumentReader;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.MimeTypeUtils;
import org.springframework.web.multipart.MultipartFile;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * 原始文件资源 Service 实现：负责文件落 MinIO + 入库 origin_file_source 表，
 * 并支撑两条业务路径——多模态对话附件、知识库文档（含向量化入库）。
 *
 * <p>
 * 关键设计点：
 * <ul>
 * <li>所有上传路径必须先在入口 {@link MultipartFile#getBytes()} 把字节缓存到 {@code byte[]}， 因为 Spring 的
 * {@code StandardMultipartFile.transferTo} 是 move 语义，原始 temp 文件被搬走后 就没法再读
 * {@code getInputStream()}。{@link #upload(String, byte[], String)} 助手就是为此而抽出的。</li>
 * <li>{@link #fromResourceId(List)} 会反向把资源 id 拉成 Media，是多模态对话恢复历史 + 当轮附件的关键转换器。</li>
 * <li>知识库上传路径里使用 {@link MarkdownAutoSplitter} 替代通用 TokenTextSplitter，目的是保留 Markdown
 * 标题层级与表格结构，避免一段表格被切分到不同 chunk。</li>
 * </ul>
 *
 * @author pgthinker
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OriginFileResourceServiceImpl extends ServiceImpl<OriginFileResourceMapper, OriginFileResource>
		implements OriginFileResourceService {

	/** 多模态对话附件存放的 MinIO 桶名（图片、零碎文件等）。 */
	public static final String CHAT_BUCKET_NAME = "origin-file";

	/** 知识库文档存放的 MinIO 桶名（会被 Tika 解析后向量化）。 */
	public static final String KNOWLEDGE_BUCKET_NAME = "knowledge-file";

	/** MinIO 客户端封装（在 core 模块定义，按 ObjectStoreService 接口注入）。 */
	private final ObjectStoreService objectStoreService;

	/** document_entity 表的 mapper。知识库文档与原始文件资源是一对一关系。 */
	private final DocumentEntityMapper documentEntityMapper;

	/** Spring AI 自带的 token 切分器；本类已改用 MarkdownAutoSplitter，但保留依赖供后续切分策略对比。 */
	private final TokenTextSplitter tokenTextSplitter;

	/** LLM / 向量库门面，主要用于拿 VectorStore 做文档入库。 */
	private final LLMService llmService;

	/**
	 * 把资源 id 列表还原为 Spring AI 的 {@link Media}：拉 MinIO 临时下载链接 → 下载到本地临时文件 → 嗅探 mime → 读为
	 * ByteArrayResource。
	 *
	 * <p>
	 * 用于两种场景：
	 * <ul>
	 * <li>当轮多模态消息：{@code AIChatServiceImpl#multimodalChat} 把当前用户上传的 resourceIds 转成 Media
	 * 挂到 prompt。</li>
	 * <li>历史多模态消息：{@code ChatMessageServiceImpl#toMessage} 把历史消息中的 resourceIds 还原回 Media，
	 * 让 LLM 在多轮对话中仍能"看到"早前传过的图。</li>
	 * </ul>
	 *
	 * <p>
	 * 这里通过临时下载文件再读字节，而不是把 MinIO 流直接传给 Media —— 是为了让 mime 嗅探（基于文件头）在本地完成， 同时避免 Spring AI
	 * 在序列化时反复打开远程流。
	 */
	@Override
	public List<Media> fromResourceId(List<String> resourceIds) {
		if (resourceIds == null || resourceIds.isEmpty()) {
			return List.of();
		}
		List<OriginFileResource> originFileResources = this.listByIds(resourceIds);
		List<Media> medias = originFileResources.stream().map(item -> {
			// 1. 取一个 MinIO 临时签名链接（带过期时间），避免裸暴露内部地址
			String fileUrl = objectStoreService.getTmpFileUrl(item.getBucketName(), item.getObjectName());
			// 2. 推断后缀（用于本地临时文件的扩展名，便于工具按扩展名识别）
			String[] split = item.getFileName().split("\\.");
			String suffix = split[split.length - 1];
			try {
				// 3. 下载到本地 temp 目录
				File file = FileUtil.downloadToTempFile(fileUrl, "chat_", suffix);
				// 4. 通过文件头嗅探真实 mime 类型（不要相信用户上传时声明的 contentType）
				String mimeType = FileUtil.detectMimeType(file);
				// 5. 全文读为 ByteArrayResource，封装成 Spring AI 的 Media
				return Media.builder()
					.data(new ByteArrayResource(Files.readAllBytes(Path.of(file.getPath()))))
					.mimeType(MimeTypeUtils.parseMimeType(mimeType))
					.build();
			}
			catch (Exception e) {
				throw new BusinessException(CoreCode.SYSTEM_ERROR, e.getMessage());
			}
		}).toList();
		return medias;
	}

	/**
	 * 上传对话附件（多模态对话路径）。
	 *
	 * <p>
	 * 流程：入口预读字节 → 委托给 {@link #upload(String, byte[], String)} 落 MinIO + 入库 → 根据
	 * contentType 判定 isImage（前端区分缩略图 / 通用文件图标） → 二次保存。
	 */
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
	 * 关键约束：{@link MultipartFile} 在 StandardMultipartFile 实现下，{@code transferTo()} 是 move
	 * 语义， 一旦 move 成功原始 temp 文件就被删除，{@code getInputStream()} 再次调用会抛
	 * {@code IOException}。本方法在入口一次性 {@code file.getBytes()} 把字节缓存到
	 * {@code byte[]}，{@code upload()} 助手和 Tika 读取都消费这份缓存，不再依赖 已被 move 走的 MultipartFile。
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
		// document_entity.resource_id，否则 DocumentEntityServiceImpl#transfer 在
		// GET /document/list 时会因 selectById("") 返回 null 而 NPE。
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

	/**
	 * 把资源 id 列表转换为前端可直接渲染的 {@link ResourceVO}（包含临时下载链接 + 文件名 + mime 类型）。
	 *
	 * <p>
	 * 与 {@link #fromResourceId(List)} 的区别：
	 * <ul>
	 * <li>{@code fromResourceId} 面向 LLM —— 真的把字节读出来、封装成 Media；</li>
	 * <li>{@code resourcesFromIds} 面向前端 UI —— 只暴露一个临时签名链接，让浏览器自己去 MinIO 拉。</li>
	 * </ul>
	 * 用于历史消息卡片中的附件预览、附件下载按钮等。
	 */
	@Override
	public List<ResourceVO> resourcesFromIds(List<String> resourceIds) {
		if (resourceIds == null || resourceIds.isEmpty()) {
			return List.of();
		}
		return resourceIds.stream().map(item -> {
			ResourceVO resourceVO = new ResourceVO();
			OriginFileResource originFileResource = this.getById(item);
			resourceVO.setResourceId(item);
			resourceVO.setFileType(originFileResource.getContentType());
			resourceVO.setFileName(originFileResource.getFileName());
			// 给前端一个 MinIO 预签名 URL（带过期），不直接暴露内部 endpoint
			resourceVO.setPath(objectStoreService.getTmpFileUrl(originFileResource.getBucketName(),
					originFileResource.getObjectName()));
			return resourceVO;
		}).toList();
	}

	/**
	 * 把字节流上传至 MinIO 并写入 origin_file_source 表。
	 *
	 * <p>
	 * 接收 {@code byte[]} 而非 {@link MultipartFile}：Spring 的
	 * {@code StandardMultipartFile.transferTo(File)} 内部是 move 而非 copy， 一旦 move 成功，原始
	 * multipart temp 文件就被删，调用方再读 {@code MultipartFile.getInputStream()} /
	 * {@code MultipartFile.getBytes()} 会抛 {@code IOException}。所有调用方必须在入口预读 bytes，再传进来。
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

	/**
	 * 拼装 MinIO 内的 objectName，前缀加上当前登录用户的 ID 做隔离。
	 *
	 * <p>
	 * 形如：{@code {userId}/{uuidNoDash}-{originalFilename}}。 加 UUID 是防止同用户重名覆盖；用 userId
	 * 做一级目录方便按用户审计 / 按用户清理。
	 */
	private String objectNameWithUserId(String filename) {
		SystemUser loginUser = SecurityFrameworkUtil.getLoginUser();
		return loginUser.getId() + "/" + UUID.randomUUID().toString().replace("-", "") + "-" + filename;
	}

}
