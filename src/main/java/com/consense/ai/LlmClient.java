package com.consense.ai;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;

/**
 * 大模型客户端抽象：默认接本机 Ollama，也可切到任意 OpenAI 兼容端点。
 */
public interface LlmClient {

    /** 普通对话补全 */
    String chat(List<ChatTurn> turns);

    /** Optional structured decoding. Providers without schema support keep prompt-based decoding. */
    default String chatStructured(List<ChatTurn> turns, JsonNode schema) {
        return chat(turns);
    }

    /** 文本向量化 */
    List<float[]> embed(List<String> texts);

    /** 本地服务是否可用（不可用时业务层降级） */
    boolean available();

    /** 当前对话模型名，用于前端展示"由哪个模型产出" */
    String chatModel();

    /** 当前向量模型名 */
    String embedModel();

    /** 单轮对话（Java 8 无 record，改为不可变类） */
    final class ChatTurn {

        private final String role;
        private final String content;

        public ChatTurn(String role, String content) {
            this.role = role;
            this.content = content;
        }

        public static ChatTurn system(String content) {
            return new ChatTurn("system", content);
        }

        public static ChatTurn user(String content) {
            return new ChatTurn("user", content);
        }

        public static ChatTurn assistant(String content) {
            return new ChatTurn("assistant", content);
        }

        public String getRole() {
            return role;
        }

        public String getContent() {
            return content;
        }
    }
}
