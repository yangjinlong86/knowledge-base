package org.nodoer.system.security.service.impl;

import lombok.RequiredArgsConstructor;
import org.nodoer.system.mapper.SystemUserMapper;
import org.nodoer.system.model.entity.user.SystemUser;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

/**
 * @Project: org.nodoer.system.security.service.impl
 * @Author: NingNing0111
 * @Github: https://github.com/ningning0111
 * @Date: 2025/3/30 18:41
 * @Description:
 */
@Service
@RequiredArgsConstructor
public class UserDetailsServiceImpl implements UserDetailsService {

	private final SystemUserMapper systemUserMapper;

	@Override
	public UserDetails loadUserByUsername(String username) throws UsernameNotFoundException {
		SystemUser systemUser = systemUserMapper.getUserWithRolesAndPermissions(username);
		if (systemUser == null) {
			throw new UsernameNotFoundException("用户不存在");
		}
		return systemUser;
	}

}
