package org.nodoer.system.service.ai.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.nodoer.core.common.BaseResponse;
import org.nodoer.core.common.CoreCode;
import org.nodoer.core.exception.BusinessException;
import org.nodoer.core.service.objectstore.ObjectStoreService;
import org.nodoer.system.controller.vo.DocumentVO;
import org.nodoer.system.mapper.DocumentEntityMapper;
import org.nodoer.system.mapper.KnowledgeBaseMapper;
import org.nodoer.system.mapper.OriginFileResourceMapper;
import org.nodoer.system.model.entity.ai.DocumentEntity;
import org.nodoer.system.model.entity.ai.KnowledgeBase;
import org.nodoer.system.model.entity.ai.OriginFileResource;
import org.nodoer.system.objectstore.service.MinIOService;
import org.nodoer.system.service.ai.DocumentEntityService;
import org.nodoer.system.service.ai.LLMService;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.Filter;
import org.springframework.ai.vectorstore.filter.FilterExpressionBuilder;
import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * @Project: org.nodoer.system.service.ai.impl
 * @Author: NingNing0111
 * @Github: https://github.com/ningning0111
 * @Date: 2025/4/8 23:34
 * @Description:
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DocumentEntityServiceImpl implements DocumentEntityService {

	private final DocumentEntityMapper documentEntityMapper;

	private final OriginFileResourceMapper originFileResourceMapper;

	private final KnowledgeBaseMapper knowledgeBaseMapper;

	private final ObjectStoreService objectStoreService;

	private final LLMService llmService;

	private final MinIOService minIOService;

	@Override
	public Page<DocumentVO> listDocuments(DocumentVO document) {
		if (document.getKnowledgeBaseId() == null) {
			throw new BusinessException(CoreCode.PARAMS_ERROR);
		}
		LambdaQueryWrapper<DocumentEntity> qw = new LambdaQueryWrapper<>();
		if (document.getFileName() != null) {
			qw.like(DocumentEntity::getFileName, document.getFileName());
		}
		qw.eq(DocumentEntity::getBaseId, document.getKnowledgeBaseId());
		qw.orderByDesc(DocumentEntity::getCreateTime);
		Page<DocumentEntity> page = Page.of(document.getPageNo(), document.getPageSize());
		Page<DocumentEntity> documentPage = documentEntityMapper.selectPage(page, qw);
		List<DocumentVO> vos = transfer(documentPage.getRecords());
		Page<DocumentVO> res = new Page<>();
		BeanUtils.copyProperties(documentPage, res);
		res.setRecords(vos);
		return res;
	}

	@Override
	public Boolean deleteKnowledgeFile(DocumentVO documentVO) {
		Long fileId = documentVO.getId();
		String baseId = documentVO.getBaseId();

		QueryWrapper<DocumentEntity> queryWrapper = new QueryWrapper<>();
		queryWrapper.eq("base_id", baseId);
		queryWrapper.eq("file_id", fileId);

		DocumentEntity document = documentEntityMapper.selectOne(queryWrapper);

		try {
			// 删除文件
			documentEntityMapper.deleteById(document);
			// 删除向量数据
			VectorStore vectorStore = llmService.getVectorStore();

			Filter.Expression filterExpression = new FilterExpressionBuilder().eq("document_id", fileId).build();

			vectorStore.delete(filterExpression);
			return true;
		}
		catch (Exception e) {
			throw new BusinessException(CoreCode.SYSTEM_ERROR, e.getMessage());
		}

	}

	@Override
	public void download(Long fileId, HttpServletResponse response) {
		DocumentEntity document = documentEntityMapper.selectById(fileId);
		if (document == null) {
			throw new BusinessException(CoreCode.FILE_NOT_FOUND);
		}
		OriginFileResource originFileResource = originFileResourceMapper.selectById(document.getResourceId());
		if (originFileResource == null) {
			throw new BusinessException(CoreCode.FILE_NOT_FOUND);
		}
		InputStream file = minIOService.getFile(originFileResource.getBucketName(), originFileResource.getObjectName());

		// 设置响应头
		response.setContentType("application/octet-stream");
		response.setHeader("Content-Disposition", "attachment; filename=\""
				+ URLEncoder.encode(originFileResource.getFileName(), StandardCharsets.UTF_8) + "\"");

		try (ServletOutputStream out = response.getOutputStream()) {
			byte[] buffer = new byte[1024];
			int length;
			while ((length = file.read(buffer)) != -1) {
				out.write(buffer, 0, length);
			}
			out.flush();
		}
		catch (IOException e) {
			throw new RuntimeException("文件下载失败", e);
		}
	}

	private List<DocumentVO> transfer(List<DocumentEntity> documentEntities) {
		return documentEntities.stream().map(item -> {
			String resourceId = item.getResourceId();
			OriginFileResource originFileResource = originFileResourceMapper.selectById(resourceId);
			String path = "";// objectStoreService.getTmpFileUrl(originFileResource.getBucketName(),
			// originFileResource.getObjectName());
			KnowledgeBase knowledgeBase = knowledgeBaseMapper.selectById(item.getBaseId());
			DocumentVO documentVO = new DocumentVO();
			documentVO.setId(item.getId());
			documentVO.setFileName(item.getFileName());
			documentVO.setIsEmbedding(item.getIsEmbedding());
			documentVO.setBaseId(item.getBaseId());
			documentVO.setPath(path);
			documentVO.setKnowledgeBaseName(knowledgeBase.getName());
			documentVO.setFileType("Markdown");
			documentVO.setUploadTime(item.getCreateTime());
			return documentVO;
		}).toList();
	}

}
