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

	// Task 2: h1 heading as chunk boundary

	@Test
	void split_shouldCreateNewChunk_whenH1HeadingPresent() {
		MarkdownAutoSplitter splitter = new MarkdownAutoSplitter();
		String markdown = "# Chapter 1\n\nThis is the first chapter.";
		List<Document> result = splitter.split(markdown);

		assertEquals(1, result.size());
		assertEquals("Chapter 1", result.get(0).getMetadata().get("heading"));
		assertTrue(result.get(0).getText().contains("This is the first chapter."));
	}

	@Test
	void split_shouldSeparateChunks_atH1Headings() {
		MarkdownAutoSplitter splitter = new MarkdownAutoSplitter();
		String markdown = "# Chapter 1\n\nContent A\n\n# Chapter 2\n\nContent B";
		List<Document> result = splitter.split(markdown);

		assertEquals(2, result.size());
		assertEquals("Chapter 1", result.get(0).getMetadata().get("heading"));
		assertTrue(result.get(0).getText().contains("Content A"));
		assertEquals("Chapter 2", result.get(1).getMetadata().get("heading"));
		assertTrue(result.get(1).getText().contains("Content B"));
	}

	@Test
	void split_shouldMergeH2IntoCurrentChunk() {
		MarkdownAutoSplitter splitter = new MarkdownAutoSplitter();
		String markdown = "# Chapter 1\n\n## Section 1\n\nSome content";
		List<Document> result = splitter.split(markdown);

		assertEquals(1, result.size());
		assertEquals("Chapter 1", result.get(0).getMetadata().get("heading"));
		assertTrue(result.get(0).getText().contains("## Section 1"));
	}

	// Task 3: table preservation

	@Test
	void split_shouldPreserveTableAsWholeUnit() {
		MarkdownAutoSplitter splitter = new MarkdownAutoSplitter();
		String markdown = "# Overview\n\n| Name | Age |\n|------|-----|\n| A    | 20  |\n| B    | 30  |";
		List<Document> result = splitter.split(markdown);

		assertEquals(1, result.size());
		assertTrue(result.get(0).getText().contains("| Name | Age |"));
		assertTrue(result.get(0).getText().contains("| A    | 20  |"));
		assertTrue(result.get(0).getText().contains("| B    | 30  |"));
	}

	@Test
	void split_shouldNotSplitTableAcrossChunks() {
		MarkdownAutoSplitter splitter = new MarkdownAutoSplitter(200, 50, false);
		String markdown = "# Data\n\n| Col1 | Col2 | Col3 |\n|------|------|------|\n| A | B | C |\n| D | E | F |";
		List<Document> result = splitter.split(markdown);

		// Table rows should all be in the same chunk
		long tableChunks = result.stream().filter(d -> d.getText().contains("| Col1 | Col2 | Col3 |")).count();
		assertEquals(1, tableChunks);
	}

	// Task 4: size constraints

	@Test
	void split_shouldSplitLongContent_atMaxChunkSize() {
		MarkdownAutoSplitter splitter = new MarkdownAutoSplitter(500, 100, false);
		String longContent = "# Content\n\n" + "A".repeat(800);
		List<Document> result = splitter.split(longContent);

		assertTrue(result.size() >= 2);
		// Each chunk should respect maxChunkSize (with some tolerance for newlines)
		for (Document doc : result) {
			assertTrue(doc.getText().length() <= 510, "Chunk size " + doc.getText().length() + " exceeds max");
		}
	}

	@Test
	void split_shouldMergeShortChunks_belowMinChunkSize() {
		MarkdownAutoSplitter splitter = new MarkdownAutoSplitter(1000, 200, false);
		String shortContent = "# Title\n\n" + "Short text ".repeat(20);
		List<Document> result = splitter.split(shortContent);

		assertEquals(1, result.size());
	}

	@Test
	void split_shouldRemoveYamlFrontMatter() {
		MarkdownAutoSplitter splitter = new MarkdownAutoSplitter();
		String markdown = "---\ntitle: Test\n---\n\n# Hello\n\nWorld";
		List<Document> result = splitter.split(markdown);

		assertEquals(1, result.size());
		assertFalse(result.get(0).getText().contains("title: Test"));
		assertTrue(result.get(0).getText().contains("World"));
	}

}
