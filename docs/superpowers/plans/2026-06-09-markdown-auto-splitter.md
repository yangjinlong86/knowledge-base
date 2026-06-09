# Markdown 自动分割器实施计划

> **给 AI 工作者的提示：** 必需子技能：使用 superpowers:subagent-driven-development（推荐）或 superpowers:executing-plans 按任务逐一执行本计划。步骤使用复选框（`- [ ]`）语法以便跟踪。

**目标：** 创建一个 MarkdownAutoSplitter 工具类，能够智能地分割 Markdown 内容，同时保留表格结构。

**架构：** 单类工具，使用状态机模式解析 Markdown 结构、构建段落单元并应用大小约束。集成点在 `OriginFileResourceServiceImpl` 的文档上传流程中。

**技术栈：** Spring AI Core, Java 17+, JUnit 5

---

## 文件结构

| 操作 | 路径 | 职责 |
|------|------|------|
| 创建 | `knowledge-base-system/src/main/java/me/pgthinker/system/utils/MarkdownAutoSplitter.java` | 主分割器类 |
| 测试 | `knowledge-base-system/src/test/java/me/pgthinker/system/utils/MarkdownAutoSplitterTest.java` | 单元测试 |

---

### [x] 任务 1：项目设置与测试类

**文件：**
- 创建：`knowledge-base-system/src/test/java/me/pgthinker/system/utils/MarkdownAutoSplitterTest.java`

- [x] **第 1 步：编写空输入的失败测试**

```java
package me.pgthinker.system.utils;

import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class MarkdownAutoSplitterTest {

    @Test
    void split_shouldReturnEmptyList_whenInputIsEmpty() {
        MarkdownAutoSplitter splitter = new MarkdownAutoSplitter();
        List<Document> result = splitter.split("");
        assertTrue(result.isEmpty());
    }

    @Test
    void split_shouldReturnEmptyList_whenInputIsNull() {
        MarkdownAutoSplitter splitter = new MarkdownAutoSplitter();
        List<Document> result = splitter.split(null);
        assertTrue(result.isEmpty());
    }
}
```

- [ ] **第 2 步：运行测试以确认失败**

运行：`mvn test -pl knowledge-base-system -Dtest=MarkdownAutoSplitterTest -Dsurefire.useFile=false`
预期：FAIL 并提示 "Cannot resolve 'MarkdownAutoSplitter'"

- [ ] **第 3 步：创建主分割器类桩**

```java
package me.pgthinker.system.utils;

import org.springframework.ai.document.Document;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;

import java.util.ArrayList;
import java.util.List;

public class MarkdownAutoSplitter {

    private final int maxChunkSize;
    private final int minChunkSize;
    private final boolean includeTitleInChunk;
    private final TokenTextSplitter tokenTextSplitter;

    public MarkdownAutoSplitter() {
        this(1000, 100, false);
    }

    public MarkdownAutoSplitter(int maxChunkSize, int minChunkSize, boolean includeTitleInChunk) {
        this.maxChunkSize = maxChunkSize;
        this.minChunkSize = minChunkSize;
        this.includeTitleInChunk = includeTitleInChunk;
        this.tokenTextSplitter = new TokenTextSplitter();
    }

    public List<Document> split(String markdown) {
        // TODO: 实现
        return new ArrayList<>();
    }
}
```

- [ ] **第 4 步：运行测试以确认通过**

运行：`mvn test -pl knowledge-base-system -Dtest=MarkdownAutoSplitterTest -Dsurefire.useFile=false`
预期：PASS

- [ ] **第 5 步：提交**

```bash
git add knowledge-base-system/src/test/java/me/pgthinker/system/utils/MarkdownAutoSplitterTest.java
git add knowledge-base-system/src/main/java/me/pgthinker/system/utils/MarkdownAutoSplitter.java
git commit -m "feat: add MarkdownAutoSplitter stub"
```

---

### 任务 2：处理标题（h1 作为数据块边界）

**文件：**
- 修改：`knowledge-base-system/src/main/java/me/pgthinker/system/utils/MarkdownAutoSplitter.java`
- 修改：`knowledge-base-system/src/test/java/me/pgthinker/system/utils/MarkdownAutoSplitterTest.java`

- [ ] **第 1 步：编写 h1 标题的失败测试**

添加到 `MarkdownAutoSplitterTest`：

```java
@Test
void split_shouldCreateNewChunk_whenH1HeadingPresent() {
    MarkdownAutoSplitter splitter = new MarkdownAutoSplitter();
    String markdown = "# Chapter 1\n\nThis is the first chapter.";
    List<Document> result = splitter.split(markdown);

    assertEquals(1, result.size());
    assertEquals("Chapter 1", result.get(0).getMetadata().get("heading"));
}
```

- [ ] **第 2 步：运行测试以确认失败**

运行：`mvn test -pl knowledge-base-system -Dtest=MarkdownAutoSplitterTest#split_shouldCreateNewChunk_whenH1HeadingPresent -Dsurefire.useFile=false`
预期：FAIL

- [ ] **第 3 步：实现 h1 处理**

修改 `MarkdownAutoSplitter.java`：

```java
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class MarkdownAutoSplitter {

    private static final Pattern H1_PATTERN = Pattern.compile("^#{1}\\s+(.+)$", Pattern.MULTILINE);
    private static final Pattern H2PLUS_PATTERN = Pattern.compile("^#{2,6}\\s+(.+)$", Pattern.MULTILINE);
    private static final Pattern TABLE_PATTERN = Pattern.compile("\\|[\\s\\w|]*\\|");

    private final int maxChunkSize;
    private final int minChunkSize;
    private final boolean includeTitleInChunk;
    private final TokenTextSplitter tokenTextSplitter;

    // ... 现有构造函数 ...

    public List<Document> split(String markdown) {
        if (markdown == null || markdown.isBlank()) {
            return List.of();
        }

        // 移除 YAML 前置元数据（如果存在）
        String content = removeYamlFrontMatter(markdown);

        // 解析行并识别结构元素
        List<StructureElement> elements = parseStructure(content);

        // 构建段落单元
        List<ParagraphUnit> units = buildParagraphUnits(elements);

        // 应用大小约束
        return applySizeConstraints(units);
    }

    private String removeYamlFrontMatter(String markdown) {
        if (!markdown.startsWith("---")) {
            return markdown;
        }
        int endIdx = markdown.indexOf("\n---", 3);
        if (endIdx > 0) {
            return markdown.substring(endIdx + 3).trim();
        }
        return markdown;
    }

    private List<StructureElement> parseStructure(String content) {
        List<StructureElement> elements = new ArrayList<>();
        String[] lines = content.split("\n");

        for (int i = 0; i < lines.length; i++) {
            String line = lines[i].trim();

            if (line.matches("^#{1}\\s+.+")) {
                // h1 标题 - 开始新单元
                elements.add(new StructureElement(i, ElementType.HEADING, line, "h1"));
            } else if (line.matches("^#{2,6}\\s+.+")) {
                // h2+ 标题 - 合并到前一个单元
                elements.add(new StructureElement(i, ElementType.HEADING, line, "h2plus"));
            } else if (line.contains("|") && !line.startsWith("|")) {
                // 表格行标记
                elements.add(new StructureElement(i, ElementType.TABLE_ROW, line, "table_row"));
            }
        }
        return elements;
    }

    private List<ParagraphUnit> buildParagraphUnits(List<StructureElement> elements) {
        List<ParagraphUnit> units = new ArrayList<>();
        ParagraphUnit current = null;

        for (StructureElement element : elements) {
            switch (element.type) {
                case HEADING -> {
                    if (current != null && current.content.length() > 0) {
                        units.add(current);
                    }
                    current = new ParagraphUnit(element.text, element.level);
                }
                case TABLE_ROW -> {
                    if (current == null) {
                        current = new ParagraphUnit("", 0);
                    }
                    current.appendContent(element.text);
                }
                default -> {
                    if (current == null) {
                        current = new ParagraphUnit("", 0);
                    }
                    current.appendContent(element.text);
                }
            }
        }

        if (current != null && current.content.length() > 0) {
            units.add(current);
        }

        return units;
    }

    private List<Document> applySizeConstraints(List<ParagraphUnit> units) {
        List<Document> documents = new ArrayList<>();
        StringBuilder currentChunk = new StringBuilder();

        for (ParagraphUnit unit : units) {
            String unitText = formatUnitText(unit);
            int potentialSize = currentChunk.length() + unitText.length();

            if (potentialSize > maxChunkSize && currentChunk.length() > 0) {
                // 写入当前数据块
                documents.add(createDocument(currentChunk.toString()));
                currentChunk = new StringBuilder();
            }

            currentChunk.append(unitText);
        }

        // 添加剩余内容
        if (currentChunk.length() > 0) {
            documents.add(createDocument(currentChunk.toString()));
        }

        return documents;
    }

    private String formatUnitText(ParagraphUnit unit) {
        if (unit.level == 1) {
            return unit.content; // 标题包含在数据块中
        }
        return unit.content; // 跳过标题
    }

    private Document createDocument(String content) {
        Document doc = new Document(content);
        doc.getMetadata().put("chunk_index", documents.size());
        return doc;
    }

    // 内部类
    private enum ElementType {
        HEADING, TABLE_ROW
    }

    private record StructureElement(int lineIndex, ElementType type, String text, String level) {}

    private record ParagraphUnit(String content, int level) {
        void appendContent(String additional) {
            if (!content.isEmpty()) {
                content += "\n" + additional;
            } else {
                content = additional;
            }
        }
    }
}
```

- [ ] **第 4 步：运行测试以确认通过**

运行：`mvn test -pl knowledge-base-system -Dtest=MarkdownAutoSplitterTest -Dsurefire.useFile=false`
预期：PASS

- [ ] **第 5 步：提交**

```bash
git add knowledge-base-system/src/main/java/me/pgthinker/system/utils/MarkdownAutoSplitter.java
git add knowledge-base-system/src/test/java/me/pgthinker/system/utils/MarkdownAutoSplitterTest.java
git commit -m "feat: implement h1 heading as chunk boundary"
```

---

### 任务 3：处理表格（保留完整表格）

**文件：**
- 修改：`knowledge-base-system/src/main/java/me/pgthinker/system/utils/MarkdownAutoSplitter.java`
- 修改：`knowledge-base-system/src/test/java/me/pgthinker/system/utils/MarkdownAutoSplitterTest.java`

- [ ] **第 1 步：编写表格保留的失败测试**

添加到 `MarkdownAutoSplitterTest`：

```java
@Test
void split_shouldPreserveTableAsWholeUnit() {
    MarkdownAutoSplitter splitter = new MarkdownAutoSplitter();
    String markdown = "# Overview\n\n" +
            "| Name | Age |\n" +
            "|------|-----|\n" +
            "| A    | 20  |\n" +
            "| B    | 30  |\n";
    List<Document> result = splitter.split(markdown);

    assertEquals(1, result.size());
    assertTrue(result.get(0).getText().contains("| Name | Age |"));
    assertTrue(result.get(0).getText().contains("| A    | 20   |"));
}
```

- [ ] **第 2 步：运行测试以确认失败**

运行：`mvn test -pl knowledge-base-system -Dtest=MarkdownAutoSplitterTest#split_shouldPreserveTableAsWholeUnit -Dsurefire.useFile=false`
预期：FAIL

- [ ] **第 3 步：实现表格检测与保留**

修改 `MarkdownAutoSplitter.java` 以正确检测和保留表格：

```java
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class MarkdownAutoSplitter {

    private static final Pattern H1_PATTERN = Pattern.compile("^#{1}\\s+(.+)$", Pattern.MULTILINE);
    private static final Pattern H2PLUS_PATTERN = Pattern.compile("^#{2,6}\\s+(.+)$", Pattern.MULTILINE);
    private static final Pattern TABLE_SEPARATOR = Pattern.compile("^\\|?[-:]+\\|");

    // ... 其余代码 ...

    private List<StructureElement> parseStructure(String content) {
        List<StructureElement> elements = new ArrayList<>();
        String[] lines = content.split("\n");

        for (int i = 0; i < lines.length; i++) {
            String line = lines[i].trim();

            if (line.matches("^#{1}\\s+.+")) {
                elements.add(new StructureElement(i, ElementType.HEADING, line, "h1"));
            } else if (line.matches("^#{2,6}\\s+.+")) {
                elements.add(new StructureElement(i, ElementType.HEADING, line, "h2plus"));
            } else if (TABLE_SEPARATOR.matcher(line).find()) {
                // 表格分隔行 - 标记为表格开始
                elements.add(new StructureElement(i, ElementType.TABLE_START, line, "table_start"));
            } else if (line.contains("|") && !line.startsWith("|")) {
                elements.add(new StructureElement(i, ElementType.TABLE_ROW, line, "table_row"));
            }
        }
        return elements;
    }

    private List<ParagraphUnit> buildParagraphUnits(List<StructureElement> elements) {
        List<ParagraphUnit> units = new ArrayList<>();
        ParagraphUnit current = null;
        boolean inTable = false;

        for (StructureElement element : elements) {
            switch (element.type) {
                case HEADING -> {
                    if (inTable) {
                        // 在标题前结束表格
                        units.add(current);
                        current = null;
                        inTable = false;
                    }
                    if (current != null && current.content.length() > 0) {
                        units.add(current);
                    }
                    current = new ParagraphUnit(element.text, Integer.parseInt(element.level));
                }
                case TABLE_START -> {
                    if (current != null && current.content.length() > 0) {
                        units.add(current);
                    }
                    current = new ParagraphUnit("", 0);
                    inTable = true;
                    current.appendContent(element.text);
                }
                case TABLE_ROW -> {
                    if (current == null) {
                        current = new ParagraphUnit("", 0);
                    }
                    current.appendContent(element.text);
                }
                default -> {
                    if (current == null) {
                        current = new ParagraphUnit("", 0);
                    }
                    current.appendContent(element.text);
                }
            }
        }

        if (current != null && current.content.length() > 0) {
            units.add(current);
        }

        return units;
    }
}
```

- [ ] **第 4 步：运行测试以确认通过**

运行：`mvn test -pl knowledge-base-system -Dtest=MarkdownAutoSplitterTest -Dsurefire.useFile=false`
预期：PASS

- [ ] **第 5 步：提交**

```bash
git add knowledge-base-system/src/main/java/me/pgthinker/system/utils/MarkdownAutoSplitter.java
git add knowledge-base-system/src/test/java/me/pgthinker/system/utils/MarkdownAutoSplitterTest.java
git commit -m "feat: preserve tables as whole units"
```

---

### 任务 4：处理大小约束（max/min 数据块大小）

**文件：**
- 修改：`knowledge-base-system/src/main/java/me/pgthinker/system/utils/MarkdownAutoSplitter.java`
- 修改：`knowledge-base-system/src/test/java/me/pgthinker/system/utils/MarkdownAutoSplitterTest.java`

- [ ] **第 1 步：编写大小约束的失败测试**

添加到 `MarkdownAutoSplitterTest`：

```java
@Test
void split_shouldSplitLongContent_atMaxChunkSize() {
    MarkdownAutoSplitter splitter = new MarkdownAutoSplitter(500, 100, false);
    String longContent = repeatText("A", 800); // 800 字符
    List<Document> result = splitter.split("# Content\n\n" + longContent);

    assertTrue(result.size() >= 1);
    assertTrue(result.get(0).getText().length() <= 500);
}

@Test
void split_shouldMergeShortChunks_belowMinChunkSize() {
    MarkdownAutoSplitter splitter = new MarkdownAutoSplitter(1000, 200, false);
    String shortContent = "Short text ".repeat(20); // ~240 字符
    List<Document> result = splitter.split("# Title\n\n" + shortContent);

    assertEquals(1, result.size());
    assertTrue(result.get(0).getText().length() >= 200);
}
```

- [ ] **第 2 步：运行测试以确认失败**

运行：`mvn test -pl knowledge-base-system -Dtest=MarkdownAutoSplitterTest#split_shouldSplitLongContent_atMaxChunkSize -Dsurefire.useFile=false`
预期：FAIL

- [ ] **第 3 步：修复大小约束逻辑**

更新 `applySizeConstraints` 方法：

```java
private List<Document> applySizeConstraints(List<ParagraphUnit> units) {
    List<Document> documents = new ArrayList<>();
    StringBuilder currentChunk = new StringBuilder();

    for (ParagraphUnit unit : units) {
        String unitText = formatUnitText(unit);
        int spaceNeeded = currentChunk.length() + unitText.length();

        if (spaceNeeded > maxChunkSize && currentChunk.length() > 0) {
            // 写入当前数据块
            documents.add(createDocument(currentChunk.toString()));
            currentChunk = new StringBuilder();
        }

        currentChunk.append(unitText);
    }

    // 添加剩余内容
    if (currentChunk.length() > 0) {
        documents.add(createDocument(currentChunk.toString()));
    }

    // 合并小数据块
    return mergeSmallChunks(documents);
}

private List<Document> mergeSmallChunks(List<Document> documents) {
    if (documents.size() <= 1) {
        return documents;
    }

    List<Document> merged = new ArrayList<>();
    StringBuilder buffer = new StringBuilder();

    for (Document doc : documents) {
        int potentialSize = buffer.length() + doc.getText().length();
        if (potentialSize > maxChunkSize) {
            if (buffer.length() > 0) {
                merged.add(createDocument(buffer.toString()));
                buffer = new StringBuilder();
            }
        }
        buffer.append(doc.getText());
    }

    if (buffer.length() > 0) {
        merged.add(createDocument(buffer.toString()));
    }

    return merged;
}
```

- [ ] **第 4 步：运行测试以确认通过**

运行：`mvn test -pl knowledge-base-system -Dtest=MarkdownAutoSplitterTest -Dsurefire.useFile=false`
预期：PASS

- [ ] **第 5 步：提交**

```bash
git add knowledge-base-system/src/main/java/me/pgthinker/system/utils/MarkdownAutoSplitter.java
git add knowledge-base-system/src/test/java/me/pgthinker/system/utils/MarkdownAutoSplitterTest.java
git commit -m "feat: implement max/min chunk size constraints"
```

---

### 任务 5：与现有代码集成

**文件：**
- 修改：`knowledge-base-system/src/main/java/me/pgthinker/system/service/ai/impl/OriginFileResourceServiceImpl.java`

- [ ] **第 1 步：更新 OriginFileResourceServiceImpl 中的导入**

在文件顶部添加：

```java
import me.pgthinker.system.utils.MarkdownAutoSplitter;
```

- [ ] **第 2 步：用 MarkdownAutoSplitter 替换 SentenceSplitter**

找到第 125-126 行附近区域：

```java
SentenceSplitter sentenceSplitter = new SentenceSplitter();
List<Document> splitDocumentList = sentenceSplitter.split(rawDocumentList);
```

替换为：

```java
MarkdownAutoSplitter markdownSplitter = new MarkdownAutoSplitter();
List<Document> splitDocumentList = markdownSplitter.split(tikaDocumentReader.read().stream()
        .map(Document::getText)
        .collect(Collectors.joining("\n\n")));
```

- [ ] **第 3 步：运行集成测试**

运行：`mvn test -pl knowledge-base-system -Dtest=OriginFileResourceServiceImplTest -Dsurefire.useFile=false`
预期：PASS（或至少无编译错误）

- [ ] **第 4 步：提交**

```bash
git add knowledge-base-system/src/main/java/me/pgthinker/system/service/ai/impl/OriginFileResourceServiceImpl.java
git commit -m "feat: integrate MarkdownAutoSplitter into document upload"
```

---

### 任务 6：最终验证与清理

- [ ] **第 1 步：运行全部测试**

运行：`mvn test -pl knowledge-base-system`
预期：全部测试 PASS

- [ ] **第 2 步：审查最终实现**

阅读完整的 `MarkdownAutoSplitter.java`，确保满足所有需求：
- 表格作为完整单元保留 ✓
- h1 标题作为数据块边界 ✓
- 应用最大/最小数据块大小 ✓
- 返回 Spring AI Document 对象 ✓

- [ ] **第 3 步：最终提交**

```bash
git add -A
git commit -m "chore: finalize MarkdownAutoSplitter implementation"
```
