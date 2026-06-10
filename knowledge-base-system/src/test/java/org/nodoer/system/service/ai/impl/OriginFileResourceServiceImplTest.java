package org.nodoer.system.service.ai.impl;

import org.nodoer.core.service.objectstore.ObjectStoreService;
import org.nodoer.system.mapper.DocumentEntityMapper;
import org.nodoer.system.model.entity.ai.DocumentEntity;
import org.nodoer.system.model.entity.user.SystemUser;
import org.nodoer.system.service.ai.LLMService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
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
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

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
	void uploadFile_shouldUseMarkdownAutoSplitter_whenVectorizingMarkdown() {
		SystemUser user = new SystemUser();
		user.setId(1L);
		SecurityContextHolder.getContext()
			.setAuthentication(new UsernamePasswordAuthenticationToken(user, null, List.of()));
		when(llmService.getVectorStore()).thenReturn(vectorStore);
		when(documentEntityMapper.insert(any(DocumentEntity.class))).thenAnswer(invocation -> {
			DocumentEntity documentEntity = invocation.getArgument(0);
			documentEntity.setId(10L);
			return 1;
		});

		OriginFileResourceServiceImpl service = new OriginFileResourceServiceImpl(objectStoreService,
				documentEntityMapper, tokenTextSplitter, llmService);
		String markdown = "# Overview\n\n" + "| Name | Age |\n" + "|------|-----|\n" + "| A    | 20  |\n"
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

}
