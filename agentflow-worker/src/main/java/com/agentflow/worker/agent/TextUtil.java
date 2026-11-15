package com.agentflow.worker.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;


/** 处理模型/工具文本的小工具:宽松提取 JSON、提取 URL、截断。 */
public final class TextUtil {

    /** 单个工具结果进上下文前的截断长度(防上下文膨胀)。 */
    public static final int MAX_TOOL_CHARS = 2000;

    private static final ObjectMapper OM = new ObjectMapper();
    // 保留括号(Wikipedia 的 Kafka_(software) 这类 URL),以空白/引号/尖括号/方括号为界

    private TextUtil() {}

    /** 从模型输出中取第一个 '{' 到最后一个 '}' 解析为 JSON:兼容 ```json 代码块和前后废话。 */
    public static JsonNode parseObject(String text) {
        if (text == null) {
            throw new IllegalStateException("LLM 输出为空");
        }
        int a = text.indexOf('{');
        int b = text.lastIndexOf('}');
        if (a < 0 || b < a) {
            throw new IllegalStateException("LLM 输出不含 JSON 对象:" + truncate(text, 200));
        }
        try {
            return OM.readTree(text.substring(a, b + 1));
        } catch (Exception e) {
            throw new IllegalStateException("LLM 输出 JSON 解析失败:" + truncate(text, 200));
        }
    }

    public static String truncate(String s, int max) {
        if (s == null) {
            return "";
        }
        return s.length() <= max ? s : s.substring(0, max) + "…";
    }

    /** 摘录比对用:小写、统一弯引号、压缩空白。模型抄原文时常改大小写 / 引号 / 空格,这些不算编造。 */
    public static String normalize(String s) {
        return s == null ? "" : s.toLowerCase()
                .replace('\u2018', '\'').replace('\u2019', '\'').replace('\u201C', '"').replace('\u201D', '"')
                .replaceAll("\\s+", " ").trim();
    }
}
