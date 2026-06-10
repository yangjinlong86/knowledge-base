package org.nodoer.system.controller;

import jakarta.annotation.security.PermitAll;
import lombok.RequiredArgsConstructor;
import org.nodoer.core.common.BaseResponse;
import org.nodoer.core.common.ResultUtils;
import org.nodoer.system.controller.vo.AuthVO;
import org.nodoer.system.controller.vo.UserLoginVO;
import org.nodoer.system.service.system.AuthService;
import org.springframework.web.bind.annotation.*;

/**
 * @Project: org.nodoer.system.controller
 * @Author: NingNing0111
 * @Github: https://github.com/ningning0111
 * @Date: 2025/3/30 18:46
 * @Description:
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/auth")
public class AuthController {

	private final AuthService authService;

	@PostMapping("/login")
	@PermitAll
	public BaseResponse<AuthVO> login(@RequestBody UserLoginVO userLoginVO) {
		return ResultUtils.success(authService.login(userLoginVO));
	}

	@GetMapping("/userInfo")
	public BaseResponse<AuthVO> userInfo() {
		return ResultUtils.success(authService.userInfo());
	}

	// @PostMapping("/logout")
	// public BaseResponse<Boolean> logout() {
	//
	// }

}
