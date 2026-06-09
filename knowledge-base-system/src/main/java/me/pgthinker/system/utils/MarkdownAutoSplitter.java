package me.pgthinker.system.utils;

import org.springframework.ai.document.Document;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Markdown 文档自动分割器。
 *
 * <p>
 * 该工具类用于将 Markdown 文本切分成适合向量化存储的 {@link Document} 列表。相比通用的文本分割器， 它会优先识别 Markdown
 * 的结构语义，尽量避免破坏标题层级和表格内容：
 * </p>
 *
 * <ul>
 * <li>一级标题（h1）作为新的数据块边界，并写入 {@code heading} 元数据；</li>
 * <li>二级到六级标题（h2-h6）合并到当前数据块中，保留上下文连续性；</li>
 * <li>表格行作为整体内容连续追加，尽量避免把一张表拆散；</li>
 * <li>通过 {@code maxChunkSize} 和 {@code minChunkSize} 控制最终数据块大小。</li>
 * </ul>
 */
public class MarkdownAutoSplitter {

	/**
	 * 单个数据块允许的最大字符数。
	 */
	private final int maxChunkSize;

	/**
	 * 单个数据块期望保留的最小字符数，用于后续小块合并。
	 */
	private final int minChunkSize;

	/**
	 * 是否将一级标题文本写入数据块正文。为 false 时，一级标题仅作为元数据保存。
	 */
	private final boolean includeTitleInChunk;

	/**
	 * 使用默认参数创建分割器。
	 *
	 * <p>
	 * 默认最大块大小为 1000 字符，最小块大小为 100 字符，且一级标题不写入正文。
	 * </p>
	 */
	public MarkdownAutoSplitter() {
		this(1000, 100, false);
	}

	/**
	 * 使用自定义参数创建分割器。
	 * @param maxChunkSize 单个数据块最大字符数
	 * @param minChunkSize 单个数据块最小字符数
	 * @param includeTitleInChunk 是否将一级标题包含在数据块正文中
	 */
	public MarkdownAutoSplitter(int maxChunkSize, int minChunkSize, boolean includeTitleInChunk) {
		this.maxChunkSize = maxChunkSize;
		this.minChunkSize = minChunkSize;
		this.includeTitleInChunk = includeTitleInChunk;
	}

	/**
	 * 将 Markdown 字符串拆分为 Spring AI Document 列表。
	 *
	 * <p>
	 * 处理流程包括：空值判断、YAML 前置元数据移除、Markdown 结构解析、段落单元构建、 大小约束应用和小块合并。
	 * </p>
	 * @param markdown 原始 Markdown 文本
	 * @return 分割后的 Document 列表；当输入为空或空白字符串时返回空列表
	 */
	public List<Document> split(String markdown) {
		if (markdown == null || markdown.isBlank()) {
			return List.of();
		}

		// 第 1 步：移除 YAML 前置元数据
		String content = removeYamlFrontMatter(markdown);
		// 第 2 步：逐行识别 Markdown 结构元素（标题/表格/普通文本）
		List<StructureElement> elements = parseStructure(content);
		// 第 3 步：将结构元素组装成段落单元
		List<ParagraphUnit> units = buildParagraphUnits(elements);
		// 第 4 步：按大小约束进行分块，并合并过小的数据块
		return applySizeConstraints(units);
	}

	/**
	 * 移除 Markdown 文件顶部的 YAML 前置元数据。
	 *
	 * <p>
	 * YAML 前置元数据以 <code>---</code> 开始和结束，常见于 Hugo/Jekyll 等静态站点生成的 Markdown 文件。 若不存在 YAML
	 * 前置元数据，则原样返回。
	 * </p>
	 * @param markdown 原始 Markdown 文本
	 * @return 去除 YAML 前置元数据后的文本
	 */
	private String removeYamlFrontMatter(String markdown) {
		if (!markdown.startsWith("---")) {
			return markdown;
		}
		// 在开头 --- 之后寻找结束的 ---
		int endIdx = markdown.indexOf("\n---", 3);
		if (endIdx > 0) {
			return markdown.substring(endIdx + 4).trim();
		}
		return markdown;
	}

	/**
	 * 逐行解析 Markdown 内容，识别结构元素类型（标题、表格行、普通文本）。
	 *
	 * <p>
	 * 内部维护 {@code inTable} 状态，用于判断后续包含 {@code |} 的行是否属于表格行。 当遇到空行时，表格状态重置。
	 * </p>
	 * @param content 已去除 YAML 前置元数据的 Markdown 文本
	 * @return 结构元素列表
	 */
	private List<StructureElement> parseStructure(String content) {
		List<StructureElement> elements = new ArrayList<>();
		String[] lines = content.split("\n");
		// 标记当前是否处于表格区域内（遇到空行或标题时重置）
		boolean inTable = false;

		for (int i = 0; i < lines.length; i++) {
			String line = lines[i];
			String trimmed = line.trim();

			if (trimmed.isEmpty()) {
				if (inTable) {
					// 空行意味着表格区域结束
					inTable = false;
				}
				continue;
			}

			// 一级标题：作为新的数据块边界
			if (trimmed.matches("^#{1}\\s+.+")) {
				String headingText = trimmed.replaceFirst("^#\\s+", "");
				elements.add(new StructureElement(ElementType.HEADING, line, 1, headingText));
				inTable = false;
			}
			// 二级到六级标题：合并到当前数据块
			else if (trimmed.matches("^#{2,6}\\s+.+")) {
				// 计算标题层级（# 的个数）
				int level = 0;
				while (level < trimmed.length() && trimmed.charAt(level) == '#') {
					level++;
				}
				String headingText = trimmed.replaceFirst("^#+\\s+", "");
				elements.add(new StructureElement(ElementType.HEADING, line, level, headingText));
				inTable = false;
			}
			// 表格分隔行（如 |---|---|），标记进入表格区域
			else if (trimmed.matches("^\\|?\\s*[-:]+[-|\\s:]+$") && trimmed.contains("|")) {
				inTable = true;
				elements.add(new StructureElement(ElementType.TABLE_ROW, line, 0, null));
			}
			// 表格数据行：在表格区域内，或行本身看起来像 Markdown 表格行
			else if (trimmed.contains("|") && (inTable || looksLikeTableRow(trimmed))) {
				inTable = true;
				elements.add(new StructureElement(ElementType.TABLE_ROW, line, 0, null));
			}
			// 普通文本：追加到当前段落单元
			else {
				inTable = false;
				elements.add(new StructureElement(ElementType.TEXT, line, 0, null));
			}
		}
		return elements;
	}

	/**
	 * 判断一行文本是否看起来像 Markdown 表格行。
	 *
	 * <p>
	 * 判断依据：以 | 开头且以 | 结尾，且长度大于 2。
	 * </p>
	 * @param line 待判断的文本行
	 * @return 如果是 Markdown 表格行则返回 true
	 */
	private boolean looksLikeTableRow(String line) {
		String trimmed = line.trim();
		return trimmed.startsWith("|") && trimmed.endsWith("|") && trimmed.length() > 2;
	}

	/**
	 * 将结构元素组装为段落单元。
	 *
	 * <p>
	 * 段落单元是后续分块的基础单位。一级标题会开启新的段落单元； 非一级标题、表格行和普通文本会追加到当前段落单元中，保持上下文完整。
	 * </p>
	 * @param elements 解析得到的 Markdown 结构元素
	 * @return 段落单元列表
	 */
	private List<ParagraphUnit> buildParagraphUnits(List<StructureElement> elements) {
		List<ParagraphUnit> units = new ArrayList<>();
		ParagraphUnit current = null;

		for (StructureElement element : elements) {
			switch (element.type) {
				case HEADING -> {
					if (element.level == 1) {
						// 一级标题：开启新的段落单元
						if (current != null && !current.content.isEmpty()) {
							units.add(current);
						}
						current = new ParagraphUnit(element.heading, 1);
					}
					else {
						// 二级及以下标题：合并到当前段落单元中
						if (current == null) {
							current = new ParagraphUnit(element.heading, element.level);
						}
						else {
							current.appendContent(element.line);
						}
					}
				}
				case TABLE_ROW -> {
					if (current == null) {
						current = new ParagraphUnit("", 0);
					}
					current.appendContent(element.line);
				}
				case TEXT -> {
					if (current == null) {
						current = new ParagraphUnit("", 0);
					}
					current.appendContent(element.line);
				}
			}
		}

		if (current != null && !current.content.isEmpty()) {
			units.add(current);
		}

		return units;
	}

	/**
	 * 对段落单元应用大小约束，生成最终 Document 列表。
	 *
	 * <p>
	 * 该方法会保证一级标题单元优先开启新数据块；当单元内容超过最大大小时， 先按行拆分，单行仍超限时再按字符拆分。最后会调用
	 * {@link #mergeSmallChunks(List)} 合并过小数据块。
	 * </p>
	 * @param units 段落单元列表
	 * @return 应用大小约束后的 Document 列表
	 */
	private List<Document> applySizeConstraints(List<ParagraphUnit> units) {
		List<Document> documents = new ArrayList<>();
		StringBuilder currentChunk = new StringBuilder();
		String currentHeading = null;

		for (ParagraphUnit unit : units) {
			String unitText = formatUnitText(unit);
			if (unitText.isEmpty()) {
				continue;
			}

			// 一级标题单元始终开启新的数据块
			if (unit.level == 1 && currentChunk.length() > 0) {
				documents.add(createDocument(currentChunk.toString(), currentHeading, documents.size()));
				currentChunk = new StringBuilder();
				currentHeading = null;
			}

			// 如果单元自身超过最大块大小，先写出当前块，再按行拆分该单元
			if (unitText.length() > maxChunkSize) {
				if (currentChunk.length() > 0) {
					documents.add(createDocument(currentChunk.toString(), currentHeading, documents.size()));
					currentChunk = new StringBuilder();
					currentHeading = null;
				}
				String[] lines = unitText.split("\n", -1);
				for (String line : lines) {
					if (line.isEmpty()) {
						continue;
					}
					if (line.length() > maxChunkSize) {
						// 单行仍超过最大块大小时，退化为按字符切分
						if (currentChunk.length() > 0) {
							documents.add(createDocument(currentChunk.toString(), currentHeading, documents.size()));
							currentChunk = new StringBuilder();
							currentHeading = null;
						}
						for (int start = 0; start < line.length(); start += maxChunkSize) {
							int end = Math.min(start + maxChunkSize, line.length());
							String piece = line.substring(start, end);
							if (currentChunk.length() > 0) {
								documents
									.add(createDocument(currentChunk.toString(), currentHeading, documents.size()));
								currentChunk = new StringBuilder();
								currentHeading = null;
							}
							currentChunk.append(piece);
							if (currentHeading == null && unit.heading != null && !unit.heading.isEmpty()) {
								currentHeading = unit.heading;
							}
						}
						continue;
					}
					int potentialSize = currentChunk.length() + (currentChunk.length() > 0 ? 1 : 0) + line.length();
					if (potentialSize > maxChunkSize && currentChunk.length() > 0) {
						documents.add(createDocument(currentChunk.toString(), currentHeading, documents.size()));
						currentChunk = new StringBuilder();
						currentHeading = null;
					}
					if (currentChunk.length() > 0) {
						currentChunk.append("\n");
					}
					currentChunk.append(line);
					if (currentHeading == null && unit.heading != null && !unit.heading.isEmpty()) {
						currentHeading = unit.heading;
					}
				}
				continue;
			}

			int potentialSize = currentChunk.length() + (currentChunk.length() > 0 ? 1 : 0) + unitText.length();

			if (potentialSize > maxChunkSize && currentChunk.length() > 0) {
				documents.add(createDocument(currentChunk.toString(), currentHeading, documents.size()));
				currentChunk = new StringBuilder();
				currentHeading = unit.heading;
			}

			if (currentChunk.length() > 0) {
				currentChunk.append("\n");
			}
			currentChunk.append(unitText);

			if (currentHeading == null && unit.heading != null && !unit.heading.isEmpty()) {
				currentHeading = unit.heading;
			}
		}

		if (currentChunk.length() > 0) {
			documents.add(createDocument(currentChunk.toString(), currentHeading, documents.size()));
		}

		return mergeSmallChunks(documents);
	}

	/**
	 * 将段落单元格式化为待写入数据块的正文。
	 *
	 * <p>
	 * 当配置为不包含一级标题时，一级标题仅作为元数据保存，不写入正文。
	 * </p>
	 * @param unit 段落单元
	 * @return 需要写入 Document 的文本
	 */
	private String formatUnitText(ParagraphUnit unit) {
		if (unit.level == 1 && !includeTitleInChunk) {
			// 一级标题：默认只保留标题后的正文，标题本身写入元数据
			return unit.content.isEmpty() ? "" : unit.content;
		}
		return unit.content;
	}

	/**
	 * 创建 Spring AI Document，并写入分块元数据。
	 * @param content 数据块正文
	 * @param heading 当前数据块所属的一级标题，可为空
	 * @param index 数据块序号
	 * @return 包含正文和元数据的 Document
	 */
	private Document createDocument(String content, String heading, int index) {
		Map<String, Object> metadata = new HashMap<>();
		metadata.put("chunk_index", index);
		if (heading != null && !heading.isEmpty()) {
			metadata.put("heading", heading);
		}
		return new Document(content, metadata);
	}

	/**
	 * 合并过小的数据块，同时避免跨一级标题边界合并。
	 *
	 * <p>
	 * 如果两个相邻数据块属于不同一级标题，即使前一个数据块较小，也不会与后一个标题的数据块合并， 以避免破坏文档章节边界。
	 * </p>
	 * @param documents 初步分割得到的 Document 列表
	 * @return 合并小块后的 Document 列表
	 */
	private List<Document> mergeSmallChunks(List<Document> documents) {
		if (documents.size() <= 1) {
			return documents;
		}

		// 标记一级标题边界：由新一级标题创建的数据块不应与前一个数据块合并
		List<Boolean> isH1Boundary = new ArrayList<>();
		isH1Boundary.add(true); // 第一个数据块始终是边界
		for (int i = 1; i < documents.size(); i++) {
			// 如果该数据块的标题与前一个不同，则为一级标题边界
			Object heading = documents.get(i).getMetadata().get("heading");
			Object prevHeading = documents.get(i - 1).getMetadata().get("heading");
			isH1Boundary.add(heading != null && !heading.equals(prevHeading));
		}

		List<Document> merged = new ArrayList<>();
		StringBuilder buffer = new StringBuilder();
		String bufferHeading = null;

		for (int i = 0; i < documents.size(); i++) {
			Document doc = documents.get(i);
			String text = doc.getText();

			// 禁止跨一级标题边界合并
			if (isH1Boundary.get(i) && buffer.length() > 0) {
				merged.add(createDocument(buffer.toString(), bufferHeading, merged.size()));
				buffer = new StringBuilder();
				bufferHeading = null;
			}

			int potentialSize = buffer.length() + (buffer.length() > 0 ? 1 : 0) + text.length();

			if (potentialSize > maxChunkSize && buffer.length() > 0) {
				merged.add(createDocument(buffer.toString(), bufferHeading, merged.size()));
				buffer = new StringBuilder();
				bufferHeading = null;
			}

			if (buffer.length() > 0) {
				buffer.append("\n");
			}
			buffer.append(text);

			Object heading = doc.getMetadata().get("heading");
			if (bufferHeading == null && heading != null) {
				bufferHeading = heading.toString();
			}
		}

		if (buffer.length() > 0) {
			merged.add(createDocument(buffer.toString(), bufferHeading, merged.size()));
		}

		return merged;
	}

	/**
	 * Markdown 行元素的类型枚举。
	 */
	private enum ElementType {

		HEADING, // 标题行（h1-h6）
		TABLE_ROW, // 表格行（含分隔行和数据行）
		TEXT // 普通文本行

	}

	/**
	 * 解析阶段的结构元素。
	 *
	 * <p>
	 * 该对象表示 Markdown 中的一行及其结构语义，只负责描述原始行，不负责最终分块。
	 * </p>
	 */
	private static class StructureElement {

		/** 元素类型：标题、表格行或普通文本。 */
		final ElementType type;

		/** 原始文本行，保留原始缩进和内容。 */
		final String line;

		/** 标题层级；非标题元素为 0。 */
		final int level;

		/** 去掉 # 后的标题文本；非标题元素为 null。 */
		final String heading;

		StructureElement(ElementType type, String line, int level, String heading) {
			this.type = type;
			this.line = line;
			this.level = level;
			this.heading = heading;
		}

	}

	/**
	 * 分块前的段落单元。
	 *
	 * <p>
	 * 多个结构元素会被合并为一个段落单元。后续按大小切分时，以段落单元作为优先保留的语义边界。
	 * </p>
	 */
	private static class ParagraphUnit {

		/** 当前段落单元累计的正文内容。 */
		String content;

		/** 段落单元对应的标题层级；普通内容为 0。 */
		final int level;

		/** 当前段落单元所属的一级标题文本，用于写入 Document 元数据。 */
		String heading;

		ParagraphUnit(String heading, int level) {
			this.content = "";
			this.heading = heading;
			this.level = level;
		}

		/**
		 * 向当前段落单元追加一行文本。
		 * @param text 要追加的文本行
		 */
		void appendContent(String text) {
			if (content.isEmpty()) {
				content = text;
			}
			else {
				content += "\n" + text;
			}
		}

	}

}
