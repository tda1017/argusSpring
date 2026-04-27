package com.argus.review.domain.rag;

import dev.langchain4j.data.document.Document;
import dev.langchain4j.data.document.DocumentSplitter;
import dev.langchain4j.data.segment.TextSegment;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 方法级代码块切分器。
 * <p>将代码文档按 Java 方法签名进行切分，确保向量检索的最小语义单元为单个方法。</p>
 */
public class MethodLevelDocumentSplitter implements DocumentSplitter {

    // 匹配 Java 方法签名的简化正则：修饰符 + 返回类型 + 方法名 + 参数列表
    private static final Pattern METHOD_PATTERN = Pattern.compile(
        "(?m)^(?=\\s*(?:public|protected|private|static|final|abstract|synchronized|@\\w+)\\s*[\\w<>,?\\s\\[\\]]+\\s+\\w+\\s*\\([^)]*\\)\\s*\\{)"
    );

    @Override
    public List<TextSegment> split(Document document) {
        String text = document.text();
        List<TextSegment> segments = new ArrayList<>();

        Matcher matcher = METHOD_PATTERN.matcher(text);
        int lastEnd = 0;
        int methodIndex = 0;

        while (matcher.find()) {
            if (matcher.start() > lastEnd) {
                // 方法前的内容（如 import、类声明等）作为独立片段
                String preamble = text.substring(lastEnd, matcher.start()).trim();
                if (!preamble.isEmpty()) {
                    segments.add(TextSegment.from(preamble));
                }
            }

            int braceDepth = 0;
            int methodStart = matcher.start();
            int i = methodStart;
            boolean inString = false;
            char stringChar = 0;

            // 线性扫描方法体，通过花括号深度找到真实结束位置，避免被字符串里的括号干扰。
            for (; i < text.length(); i++) {
                char c = text.charAt(i);

                if (inString) {
                    if (c == '\\') {
                        i++; // 跳过转义字符
                    } else if (c == stringChar) {
                        inString = false;
                    }
                    continue;
                }

                if (c == '"' || c == '\'') {
                    inString = true;
                    stringChar = c;
                    continue;
                }

                if (c == '{') {
                    braceDepth++;
                } else if (c == '}') {
                    braceDepth--;
                    if (braceDepth == 0) {
                        i++; // 包含最后的 }
                        break;
                    }
                }
            }

            String methodBody = text.substring(methodStart, i).trim();
            if (!methodBody.isEmpty()) {
                segments.add(TextSegment.from(methodBody));
            }
            lastEnd = i;
        }

        // 尾部剩余内容
        if (lastEnd < text.length()) {
            String tail = text.substring(lastEnd).trim();
            if (!tail.isEmpty()) {
                segments.add(TextSegment.from(tail));
            }
        }

        return segments;
    }

}
