package org.nodoer.system.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.nodoer.core.common.BaseResponse;
import org.nodoer.core.common.ResultUtils;
import org.nodoer.system.controller.vo.DocumentVO;
import org.nodoer.system.service.ai.DocumentEntityService;
import org.springframework.web.bind.annotation.*;

/**
 * @Project: org.nodoer.system.controller
 * @Author: NingNing0111
 * @Github: https://github.com/ningning0111
 * @Date: 2025/4/8 23:46
 * @Description:
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/document")
public class DocumentController {

	private final DocumentEntityService documentEntityService;

	@GetMapping("/list")
	public BaseResponse<Page<DocumentVO>> listDocument(DocumentVO documentVO) {
		return ResultUtils.success(documentEntityService.listDocuments(documentVO));
	}

	@PostMapping("/delete")
	public BaseResponse<Boolean> deleteKnowledgeFile(@RequestBody DocumentVO documentVO) {
		return ResultUtils.success(documentEntityService.deleteKnowledgeFile(documentVO));
	}

	@GetMapping("/download/{fileId}")
	public void downloadDocument(@PathVariable Long fileId, HttpServletResponse response) {
		documentEntityService.download(fileId, response);
	}

}
