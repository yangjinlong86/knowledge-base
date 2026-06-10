package org.nodoer.system.controller.vo;

import lombok.Data;

/**
 * @Project: org.nodoer.system.controller.vo
 * @Author: NingNing0111
 * @Github: https://github.com/ningning0111
 * @Date: 2025/4/11 20:09
 * @Description:
 */
@Data
public class ResourceVO {

	/**
	 * 资源ID
	 */
	private String resourceId;

	/**
	 * 资源文件名
	 */
	private String fileName;

	/**
	 * 资源类型
	 */
	private String fileType;

	/**
	 * 下载路径
	 */
	private String path;

}
