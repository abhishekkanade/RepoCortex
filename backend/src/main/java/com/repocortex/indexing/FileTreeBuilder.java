package com.repocortex.indexing;

import java.util.List;

// Groups paths by folder so the tree is shorter than a flat path list:
//   src/main/java/app/
//     App.java
//     Config.java
public final class FileTreeBuilder {

    private static final int MAX_LINES = 3000;

    private FileTreeBuilder() {
    }

    public static String build(List<String> paths) {
        StringBuilder tree = new StringBuilder();
        String currentDir = null;
        int lines = 0;
        for (String path : paths.stream().sorted().toList()) {
            if (lines >= MAX_LINES) {
                tree.append("... (").append(paths.size()).append(" files total)\n");
                break;
            }
            int slash = path.lastIndexOf('/');
            String dir = slash < 0 ? "" : path.substring(0, slash + 1);
            if (!dir.equals(currentDir)) {
                if (!dir.isEmpty()) {
                    tree.append(dir).append('\n');
                    lines++;
                }
                currentDir = dir;
            }
            tree.append(dir.isEmpty() ? "" : "  ").append(path.substring(slash + 1)).append('\n');
            lines++;
        }
        return tree.toString();
    }
}
