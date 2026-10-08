package com.repocortex.indexing;

import java.util.Locale;
import java.util.Map;
import java.util.Set;

public final class SourceFileFilter {

    public static final long MAX_FILE_BYTES = 100 * 1024;

    private static final Set<String> SKIPPED_DIRS = Set.of(
            "node_modules", "dist", "build", "target", ".git", "out", "coverage",
            "vendor", "bower_components", ".next", ".nuxt", ".gradle", ".idea", ".vscode",
            "__pycache__", ".venv", "venv", ".tox", ".mypy_cache", ".pytest_cache");

    private static final Set<String> LOCKFILES = Set.of(
            "package-lock.json", "yarn.lock", "pnpm-lock.yaml", "bun.lockb", "npm-shrinkwrap.json",
            "cargo.lock", "poetry.lock", "pipfile.lock", "uv.lock", "gemfile.lock", "composer.lock",
            "go.sum", "gradle.lockfile", "mix.lock", "podfile.lock", "packages.lock.json", "flake.lock");

    // files without a useful extension that are still worth indexing
    private static final Map<String, String> SPECIAL_NAMES = Map.of(
            "makefile", "makefile", "gemfile", "ruby", "rakefile", "ruby",
            "jenkinsfile", "groovy", "procfile", "text");

    // extension -> language name
    private static final Map<String, String> LANGUAGES = Map.ofEntries(
            Map.entry("java", "java"), Map.entry("kt", "kotlin"), Map.entry("kts", "kotlin"),
            Map.entry("scala", "scala"), Map.entry("groovy", "groovy"), Map.entry("gradle", "groovy"),
            Map.entry("go", "go"), Map.entry("rs", "rust"), Map.entry("py", "python"),
            Map.entry("rb", "ruby"), Map.entry("php", "php"),
            Map.entry("js", "javascript"), Map.entry("jsx", "javascript"), Map.entry("mjs", "javascript"),
            Map.entry("cjs", "javascript"), Map.entry("ts", "typescript"), Map.entry("tsx", "typescript"),
            Map.entry("mts", "typescript"), Map.entry("cts", "typescript"),
            Map.entry("vue", "vue"), Map.entry("svelte", "svelte"),
            Map.entry("c", "c"), Map.entry("h", "c"), Map.entry("cc", "cpp"), Map.entry("cpp", "cpp"),
            Map.entry("cxx", "cpp"), Map.entry("hpp", "cpp"), Map.entry("hh", "cpp"),
            Map.entry("cs", "csharp"), Map.entry("fs", "fsharp"), Map.entry("swift", "swift"),
            Map.entry("m", "objective-c"), Map.entry("mm", "objective-c"), Map.entry("dart", "dart"),
            Map.entry("lua", "lua"), Map.entry("r", "r"), Map.entry("jl", "julia"),
            Map.entry("ex", "elixir"), Map.entry("exs", "elixir"), Map.entry("erl", "erlang"),
            Map.entry("clj", "clojure"), Map.entry("cljs", "clojure"), Map.entry("hs", "haskell"),
            Map.entry("ml", "ocaml"), Map.entry("elm", "elm"), Map.entry("zig", "zig"),
            Map.entry("sol", "solidity"), Map.entry("nix", "nix"),
            Map.entry("sh", "shell"), Map.entry("bash", "shell"), Map.entry("zsh", "shell"),
            Map.entry("ps1", "powershell"), Map.entry("bat", "batch"),
            Map.entry("sql", "sql"), Map.entry("graphql", "graphql"), Map.entry("gql", "graphql"),
            Map.entry("proto", "protobuf"), Map.entry("html", "html"), Map.entry("htm", "html"),
            Map.entry("css", "css"), Map.entry("scss", "scss"), Map.entry("sass", "sass"), Map.entry("less", "less"),
            Map.entry("xml", "xml"), Map.entry("json", "json"), Map.entry("yaml", "yaml"), Map.entry("yml", "yaml"),
            Map.entry("toml", "toml"), Map.entry("ini", "ini"), Map.entry("cfg", "ini"), Map.entry("conf", "ini"),
            Map.entry("properties", "properties"), Map.entry("tf", "terraform"), Map.entry("hcl", "hcl"),
            Map.entry("cmake", "cmake"), Map.entry("mk", "makefile"),
            Map.entry("md", "markdown"), Map.entry("mdx", "markdown"), Map.entry("rst", "rst"),
            Map.entry("adoc", "asciidoc"), Map.entry("txt", "text"));

    // languages that count as "main source" rather than config or docs
    private static final Set<String> CONFIG_OR_DOC_LANGUAGES = Set.of(
            "xml", "json", "yaml", "toml", "ini", "properties", "markdown", "rst", "asciidoc", "text",
            "html", "css", "scss", "sass", "less", "makefile", "dockerfile");

    private SourceFileFilter() {
    }

    // cheap checks that only need the path and size, done before reading the content
    public static boolean isCandidate(String path, long size) {
        if (size > MAX_FILE_BYTES) {
            return false;
        }
        String[] parts = path.split("/");
        for (int i = 0; i < parts.length - 1; i++) {
            if (SKIPPED_DIRS.contains(parts[i].toLowerCase(Locale.ROOT))) {
                return false;
            }
        }
        String fileName = parts[parts.length - 1].toLowerCase(Locale.ROOT);
        if (LOCKFILES.contains(fileName) || fileName.contains(".min.")) {
            return false;
        }
        return languageOf(path) != null;
    }

    public static boolean isTextContent(byte[] content) {
        for (byte b : content) {
            if (b == 0) {
                return false;
            }
        }
        return true;
    }

    // minified or generated bundles have very long lines on average
    public static boolean looksMinified(String content) {
        long lines = content.lines().count();
        return lines > 0 && content.length() / lines > 300;
    }

    public static String languageOf(String path) {
        String fileName = path.substring(path.lastIndexOf('/') + 1).toLowerCase(Locale.ROOT);
        if (fileName.startsWith("dockerfile")) {
            return "dockerfile";
        }
        if (SPECIAL_NAMES.containsKey(fileName)) {
            return SPECIAL_NAMES.get(fileName);
        }
        int dot = fileName.lastIndexOf('.');
        if (dot < 0) {
            return null;
        }
        return LANGUAGES.get(fileName.substring(dot + 1));
    }

    public static boolean isCode(String language) {
        return language != null && !CONFIG_OR_DOC_LANGUAGES.contains(language);
    }
}
