package org.nodoer.system.controller;

import lombok.RequiredArgsConstructor;
import org.nodoer.core.common.BaseResponse;
import org.nodoer.core.common.ResultUtils;
import org.nodoer.system.controller.vo.KnowledgeBaseVO;
import org.nodoer.system.controller.vo.SimpleBaseVO;
import org.nodoer.system.service.ai.KnowledgeBaseService;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * @Project: org.nodoer.system.controller
 * @Author: NingNing0111
 * @Github: https://github.com/ningning0111
 * @Date: 2025/4/8 07:59
 * @Description:
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/knowledge")
public class KnowledgeBaseController {

	private final KnowledgeBaseService knowledgeBaseService;

	@PostMapping("/create")
	public BaseResponse<String> createKnowledgeBase(@RequestBody KnowledgeBaseVO knowledgeBase) {
		return ResultUtils.success(knowledgeBaseService.addKnowledgeBase(knowledgeBase));
	}

	@PostMapping("/remove")
	public BaseResponse<Integer> removeKnowledgeBase(@RequestBody KnowledgeBaseVO knowledgeBase) {
		return ResultUtils.success(knowledgeBaseService.removeKnowledgeBase(knowledgeBase));
	}

	@GetMapping("/list")
	public BaseResponse<List<KnowledgeBaseVO>> listKnowledgeBase() {
		return ResultUtils.success(knowledgeBaseService.knowLedgelist());
	}

	@GetMapping("/simple")
	public BaseResponse<List<SimpleBaseVO>> simpleKnowledgeBase() {
		return ResultUtils.success(knowledgeBaseService.simpleList());
	}

}
