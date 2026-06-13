package org.nodoer.system.service.ai;

import com.baomidou.mybatisplus.extension.service.IService;
import org.nodoer.system.controller.vo.ResourceVO;
import org.nodoer.system.model.entity.ai.OriginFileResource;
import org.springframework.ai.content.Media;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

/**
 * 原始文件资源服务：管理 {@code origin_file_source} 表（用户上传文件的元数据）， 同时封装 MinIO 对象存储的读写以及"资源 id ↔
 * Spring AI {@link Media}"的转换。
 *
 * <p>
 * 在系统中承担两类场景的存储入口：
 * <ul>
 * <li><b>对话附件</b>：多模态对话上传的图片/文档（{@link #uploadFile(MultipartFile)}），存到
 * {@code origin-file} 桶。</li>
 * <li><b>知识库附件</b>：用户向某个知识库上传的文档（{@link #uploadFile(MultipartFile, String)}）， 存到
 * {@code knowledge-file} 桶并做 Tika 解析 + 切分 + pgvector 入库。</li>
 * </ul>
 */
public interface OriginFileResourceService extends IService<OriginFileResource> {

	/**
	 * 根据资源 id 列表构造 Spring AI 的 {@link Media} 列表（多模态对话的附件入参）。
	 *
	 * <p>
	 * 内部会逐一调用 MinIO 拉文件到本地临时目录、嗅探 mime 类型，再封装成 Media。空入参直接返回空列表。
	 * @param resourceIds {@code origin_file_source.id} 列表
	 * @return Media 列表，与入参顺序一致
	 */
	List<Media> fromResourceId(List<String> resourceIds);

	/**
	 * 上传对话附件（多模态对话场景）。
	 *
	 * <p>
	 * 文件落 MinIO 的 {@code origin-file} 桶；额外根据 contentType 标记 isImage（前端预览/区分场景使用）。
	 * @param file 用户上传文件
	 * @return 新建的 origin_file_source 主键
	 */
	String uploadFile(MultipartFile file);

	/**
	 * 上传知识库附件，并完成 Tika 解析 + Markdown 切分 + pgvector 向量化入库。
	 *
	 * <p>
	 * 这是知识库构建的核心入口；流程见
	 * {@link org.nodoer.system.service.ai.impl.OriginFileResourceServiceImpl#uploadFile(MultipartFile, String)}。
	 * @param file 用户上传文件
	 * @param knowledgeId 目标知识库 ID
	 * @return 新建的 document_entity 主键
	 */
	Long uploadFile(MultipartFile file, String knowledgeId);

	/**
	 * 把资源 id 列表转换为前端友好的 {@link ResourceVO}（包含临时下载链接 + 文件名 + mime 类型）。 用于历史消息展示、附件预览等场景。
	 * @param resourceIds 资源 id 列表
	 * @return ResourceVO 列表
	 */
	List<ResourceVO> resourcesFromIds(List<String> resourceIds);

}
