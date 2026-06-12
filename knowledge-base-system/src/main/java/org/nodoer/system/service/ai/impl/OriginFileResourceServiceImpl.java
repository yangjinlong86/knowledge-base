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
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * @author pgthinker
 * @description 针对表【origin_file_source(存储原始文件资源的表)】的数据库操作Service实现
 * @createDate 2025-04-08 04:47:02
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OriginFileResourceServiceImpl extends ServiceImpl<OriginFileResourceMapper, OriginFileResource>
		implements OriginFileResourceService {

	public static final String CHAT_BUCKET_NAME = "origin-file";

	public static final String KNOWLEDGE_BUCKET_NAME = "knowledge-file";

	private final ObjectStoreService objectStoreService;

	private final DocumentEntityMapper documentEntityMapper;

	private final TokenTextSplitter tokenTextSplitter;

	private final LLMService llmService;

	@Override
	public List<Media> fromResourceId(List<String> resourceIds) {
		if (resourceIds == null || resourceIds.isEmpty()) {
			return List.of();
		}
		List<OriginFileResource> originFileResources = this.listByIds(resourceIds);
		List<Media> medias = originFileResources.stream().map(item -> {
			// 获取文件资源的临时访问链接
			String fileUrl = objectStoreService.getTmpFileUrl(item.getBucketName(), item.getObjectName());
			String[] split = item.getFileName().split("\\.");
			String suffix = split[split.length - 1];
			try {
				// 下载到本地
				File file = FileUtil.downloadToTempFile(fileUrl, "chat_", suffix);
				String mimeType = FileUtil.detectMimeType(file);
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

	@Transactional(rollbackFor = Exception.class)
	@Override
	public String uploadFile(MultipartFile file) {
		OriginFileResource upload = this.upload(file, CHAT_BUCKET_NAME);
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
	 * @param file 用户上传的原始文件
	 * @param knowledgeId 目标知识库 ID
	 * @return 数据库中的文档实体 ID
	 */
	@Transactional(rollbackFor = Exception.class)
	@Override
	public Long uploadFile(MultipartFile file, String knowledgeId) {
		// 1. 先上传文件至 MinIO 并写入 origin_file_source 表；返回的 id 必须回填到
		// document_entity.resource_id，否则 DocumentEntityServiceImpl#transfer 在
		// GET /document/list 时会因 selectById("") 返回 null 而 NPE。
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
			resourceVO.setPath(objectStoreService.getTmpFileUrl(originFileResource.getBucketName(),
					originFileResource.getObjectName()));
			return resourceVO;
		}).toList();
	}

	private OriginFileResource upload(MultipartFile file, String bucketName) {
		String originalFilename = file.getOriginalFilename();
		String objectName = objectNameWithUserId(originalFilename);
		String id = FileUtil.generatorFileId(bucketName, objectName);
		String newObjectName = String.format("%s/%s", objectName, id);
		String path;
		String md5;
		try {
			File tmpFile = FileUtil.createTempFile("know", "_" + file.getOriginalFilename());
			file.transferTo(tmpFile);
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
		originFileResource.setIsImage(file.getContentType() != null && file.getContentType().startsWith("image"));
		originFileResource.setSize(fileInfo.getSize());
		originFileResource.setContentType(fileInfo.getContentType());
		this.saveOrUpdate(originFileResource);
		return originFileResource;
	}

	private String objectNameWithUserId(String filename) {
		SystemUser loginUser = SecurityFrameworkUtil.getLoginUser();
		return loginUser.getId() + "/" + UUID.randomUUID().toString().replace("-", "") + "-" + filename;
	}

}
