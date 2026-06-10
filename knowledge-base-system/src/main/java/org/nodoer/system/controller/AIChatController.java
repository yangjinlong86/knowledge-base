package org.nodoer.system.controller;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.nodoer.system.controller.vo.ChatRequestVO;
import org.nodoer.system.service.ai.AIChatService;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;

import java.util.Objects;

/**
 * @Project: org.nodoer.system.controller
 * @Author: NingNing0111
 * @Github: https://github.com/ningning0111
 * @Date: 2025/4/8 05:21
 * @Description:
 */
@Slf4j
@RestController
@RequestMapping("/ai")
@RequiredArgsConstructor
public class AIChatController {

	private final AIChatService chatService;

	@PostMapping(value = "/chat/unify", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
	public Flux<Generation> unifyChat(@RequestBody ChatRequestVO chatRequestVO) {
		return chatService.unifyChat(chatRequestVO).flatMap(chatResponse -> {
			if (chatResponse == null) {
				return Flux.empty();
			}
			Generation result = chatResponse.getResult();
			if (result == null) {
				return Flux.empty();
			}

			return Flux.just(result);
		});
	}

}
