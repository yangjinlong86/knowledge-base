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
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
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
			@Override
			public String getId() {
				return "origin-id-consumed";
			}

			@Override
			public String getBucketName() {
				return "knowledge-file";
			}

			@Override
			public String getObjectName() {
				return "1/consumed.md";
			}

			@Override
			public String getContentType() {
				return "text/markdown";
			}

			@Override
			public String getFileName() {
				return "consumed.md";
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
			documentEntity.setId(30L);
			return 1;
		});

		OriginFileResourceServiceImpl real = new OriginFileResourceServiceImpl(objectStoreService, documentEntityMapper,
				tokenTextSplitter, llmService);
		OriginFileResourceServiceImpl service = Mockito.spy(real);
		doReturn(true).when(service).saveOrUpdate(any(OriginFileResource.class));

		// 使用模拟真实容器 move 语义的 MultipartFile：transferTo 之后 getBytes/getInputStream 都会抛
		// IOException
		byte[] md = "# Hello\n\nworld".getBytes(StandardCharsets.UTF_8);
		TransferToConsumesMultipartFile file = new TransferToConsumesMultipartFile("consumed.md", "text/markdown", md);

		// 当前实现：service 内部 this.upload() 调 file.transferTo() 之后，Tika 路径再次
		// file.getInputStream() 会抛 IOException
		// 期望修复后：service 在入口一次性 file.getBytes()，Tika 与 upload 助手共用缓存，不再依赖已被 move 走的
		// MultipartFile
		service.uploadFile(file, "kb-3");

		// 修复后断言：documentEntity.resourceId 正确指向 MinIO 返回的 id
		DocumentEntity inserted = captor.getValue();
		assertEquals("origin-id-consumed", inserted.getResourceId(),
				"uploadFile 必须在入口预读 MultipartFile 字节，避免 Tika 路径在 transferTo 之后再次读取失败");
	}

	@Test
	void uploadFile_chat_shouldUploadWithPreReadBytes() throws Exception {
		SystemUser user = new SystemUser();
		user.setId(1L);
		SecurityContextHolder.getContext()
			.setAuthentication(new UsernamePasswordAuthenticationToken(user, null, List.of()));
		when(objectStoreService.uploadFile(any(java.io.File.class), anyString(), anyString()))
			.thenReturn("/minio/path/chat");
		when(objectStoreService.getFileInfo(anyString(), anyString())).thenReturn(new StorageFile() {
			@Override
			public String getId() {
				return "origin-id-chat-1";
			}

			@Override
			public String getBucketName() {
				return "origin-file";
			}

			@Override
			public String getObjectName() {
				return "1/chat-1.png";
			}

			@Override
			public String getContentType() {
				return "image/png";
			}

			@Override
			public String getFileName() {
				return "chat-1.png";
			}

			@Override
			public String getPath() {
				return "/minio/path/chat";
			}

			@Override
			public Long getSize() {
				return 32L;
			}

			@Override
			public String getMd5() {
				return "cafebabe";
			}
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

	/**
	 * 模拟 Spring StandardMultipartFile / Servlet Part 的 move 语义： - transferTo(File)
	 * 之后，多部分原始 temp 文件被 move 走 - 之后再调 getInputStream() / getBytes() 抛 IOException，message
	 * 即 temp 路径 用以覆盖真实容器场景下 OriginFileResourceServiceImpl#uploadFile 的 "MultipartFile
	 * 一次性消费"行为（MockMultipartFile 是 copy 语义，覆盖不到）。
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

}
