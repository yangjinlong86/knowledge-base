package org.nodoer.system.service.system.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.nodoer.system.mapper.SystemRoleMapper;
import org.nodoer.system.model.entity.user.SystemRole;
import org.nodoer.system.service.system.SystemRoleService;
import org.springframework.stereotype.Service;

/**
 * @author pgthinker
 * @description 针对表【system_role(系统角色表)】的数据库操作Service实现
 * @createDate 2025-03-30 18:28:12
 */
@Service
public class SystemRoleServiceImpl extends ServiceImpl<SystemRoleMapper, SystemRole> implements SystemRoleService {

}
