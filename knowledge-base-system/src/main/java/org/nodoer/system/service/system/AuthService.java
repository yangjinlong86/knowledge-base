package org.nodoer.system.service.system;

import org.nodoer.system.controller.vo.AuthVO;
import org.nodoer.system.controller.vo.UserLoginVO;

/**
 * @Project: org.nodoer.system.service
 * @Author: NingNing0111
 * @Github: https://github.com/ningning0111
 * @Date: 2025/3/30 19:03
 * @Description:
 */
public interface AuthService {

	AuthVO login(UserLoginVO userLoginVO);

	AuthVO userInfo();

}
