package com.argus.review.domain.rag;

import dev.langchain4j.data.document.Document;
import dev.langchain4j.data.segment.TextSegment;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 方法级文档切分器测试。
 */
class MethodLevelDocumentSplitterTest {

    private final MethodLevelDocumentSplitter splitter = new MethodLevelDocumentSplitter();

    /**
     * 有方法定义时，应该至少拆出类前导片段和方法片段。
     */
    @Test
    void shouldSplitJavaMethods() {
        String code = """
            package com.example;

            public class Demo {
                public void hello() {
                    System.out.println("hello");
                }

                public int add(int a, int b) {
                    return a + b;
                }
            }
            """;

        List<TextSegment> segments = splitter.split(Document.from(code));

        assertFalse(segments.isEmpty());
        // 期望至少切分出包声明/类声明 + 两个方法
        assertTrue(segments.size() >= 3, "应至少切分出 3 个片段，实际: " + segments.size());

        // 验证方法体被完整包含
        boolean hasAddMethod = segments.stream()
            .anyMatch(s -> s.text().contains("public int add(int a, int b)"));
        assertTrue(hasAddMethod, "应包含 add 方法片段");
    }

    /**
     * 没有方法定义时，整段文本应保持为一个片段。
     */
    @Test
    void shouldHandleCodeWithoutMethods() {
        String code = "package com.example;\nimport java.util.List;";
        List<TextSegment> segments = splitter.split(Document.from(code));
        assertEquals(1, segments.size());
        assertTrue(segments.get(0).text().contains("package com.example"));
    }

}
