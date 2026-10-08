package com.repocortex.indexing;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Component;

import java.util.Map;

@Component
public class RepoSummarizer {

    // keeps the prompt around 3-4k tokens, inside small free-tier per-minute token limits
    private static final int MAX_README_CHARS = 4000;
    private static final int MAX_TREE_CHARS = 3000;
    private static final int MAX_MANIFEST_CHARS = 2000;

    private static final String SYSTEM_PROMPT = """
            You summarize GitHub repositories for developers. Using only the material given, write a short
            summary in Markdown with three parts: what the project does (1-2 sentences), the tech stack
            (a short list), and the main folders with one line each. Stay under 200 words. If something
            is unclear from the material, leave it out instead of guessing.
            """;

    private final ChatClient chatClient;

    public RepoSummarizer(ChatClient.Builder chatClientBuilder) {
        this.chatClient = chatClientBuilder.build();
    }

    public String summarize(String fullName, String readme, String fileTree, Map<String, String> manifests) {
        StringBuilder input = new StringBuilder("Repository: ").append(fullName).append("\n\n");
        if (readme != null) {
            input.append("README:\n").append(cut(readme, MAX_README_CHARS)).append("\n\n");
        }
        input.append("File tree:\n").append(cut(fileTree, MAX_TREE_CHARS)).append("\n\n");
        manifests.forEach((path, content) ->
                input.append(path).append(":\n").append(cut(content, MAX_MANIFEST_CHARS)).append("\n\n"));

        return chatClient.prompt()
                .system(SYSTEM_PROMPT)
                .user(input.toString())
                .call()
                .content();
    }

    private static String cut(String text, int maxChars) {
        return text.length() <= maxChars ? text : text.substring(0, maxChars) + "\n...";
    }
}
