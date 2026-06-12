package org.nodoer.system.service.ai.impl;

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
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class OriginFileResourceServiceImplTest {

	@Mock
	private ObjectStoreService objectStoreService;

	@Mock
	private DocumentEntityMapper documentEntityMapper;

	@Mock
	private TokenTextSplitter tokenTextSplitter;

	@Mock
	private LLMService llmService;

	@Mock
	private VectorStore vectorStore;

	@AfterEach
	void tearDown() {
		SecurityContextHolder.clearContext();
	}

	@Test
	void uploadFile_shouldUseMarkdownAutoSplitter_whenVectorizingMarkdown() throws Exception {
		SystemUser user = new SystemUser();
		user.setId(1L);
		SecurityContextHolder.getContext()
			.setAuthentication(new UsernamePasswordAuthenticationToken(user, null, List.of()));
		when(llmService.getVectorStore()).thenReturn(vectorStore);
		// 修复后 service 会调 MinIO 上传 + getFileInfo，必须先 mock。当前实现未调 MinIO，标记 lenient
		// 以避免严格 stub 模式下抛 UnnecessaryStubbingException，让本用例在红阶段继续验证
		// MarkdownAutoSplitter 的切分行为。
		Mockito.lenient()
			.when(objectStoreService.uploadFile(any(java.io.File.class), anyString(), anyString()))
			.thenReturn("/minio/path/know");
		Mockito.lenient().when(objectStoreService.getFileInfo(anyString(), anyString())).thenReturn(new StorageFile() {
			@Override
			public String getId() {
				return "origin-id-existing";
			}

			@Override
			public String getBucketName() {
				return "knowledge-file";
			}

			@Override
			public String getObjectName() {
				return "1/abc-test.md";
			}

			@Override
			public String getContentType() {
				return "text/markdown";
			}

			@Override
			public String getFileName() {
				return "test.md";
			}

			@Override
			public String getPath() {
				return "/minio/path/know";
			}

			@Override
			public Long getSize() {
				return 64L;
			}

			@Override
			public String getMd5() {
				return "deadbeef";
			}
		});
		when(documentEntityMapper.insert(any(DocumentEntity.class))).thenAnswer(invocation -> {
			DocumentEntity documentEntity = invocation.getArgument(0);
			documentEntity.setId(10L);
			return 1;
		});

		OriginFileResourceServiceImpl real = new OriginFileResourceServiceImpl(objectStoreService, documentEntityMapper,
				tokenTextSplitter, llmService);
		OriginFileResourceServiceImpl service = Mockito.spy(real);
		// 屏蔽 saveOrUpdate 走数据库（受 MyBatis-Plus ServiceImpl 控制，没有 OriginFileResourceMapper
		// 注入）；MyBatis-Plus 的 saveOrUpdate 返回 boolean，因此用 doReturn(true) 而非 doNothing()
		Mockito.lenient().doReturn(true).when(service).saveOrUpdate(any(OriginFileResource.class));

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
			@Override
			public String getId() {
				return "origin-id-123";
			}

			@Override
			public String getBucketName() {
				return "knowledge-file";
			}

			@Override
			public String getObjectName() {
				return "1/xyz-test.md";
			}

			@Override
			public String getContentType() {
				return "text/markdown";
			}

			@Override
			public String getFileName() {
				return "test.md";
			}

			@Override
			public String getPath() {
				return "/minio/path/know";
			}

			@Override
			public Long getSize() {
				return 64L;
			}

			@Override
			public String getMd5() {
				return "deadbeef";
			}
		});
		ArgumentCaptor<DocumentEntity> captor = ArgumentCaptor.forClass(DocumentEntity.class);
		when(documentEntityMapper.insert(captor.capture())).thenAnswer(invocation -> {
			DocumentEntity documentEntity = invocation.getArgument(0);
			documentEntity.setId(20L);
			return 1;
		});

		OriginFileResourceServiceImpl real = new OriginFileResourceServiceImpl(objectStoreService, documentEntityMapper,
				tokenTextSplitter, llmService);
		OriginFileResourceServiceImpl service = Mockito.spy(real);
		// MyBatis-Plus 的 saveOrUpdate 返回 boolean，因此用 doReturn(true) 而非 doNothing()
		doReturn(true).when(service).saveOrUpdate(any(OriginFileResource.class));

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

}
