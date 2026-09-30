package com.crystalgraphics.harness.trace;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

/**
 * Maps a trace's source location — {@code com/x/Y.java:12}, the class path a name was first used from —
 * to the file in the repository, so a report line opens the right file.
 *
 * <pre>{@code
 * CgTraceReport.of(snapshot).sources(SourcePaths.forRepository());
 * //   com/crystalgui/ui/box/BoxTree.java:333  ->  core/src/main/java/com/crystalgui/ui/box/BoxTree.java:333
 * }</pre>
 *
 * <p>The repository is the outermost directory above the working directory that holds a
 * {@code settings.gradle.kts}, or {@code -Dcrystalgraphics.harness.repoRoot}. A location it cannot find is
 * left as it was.</p>
 */
public final class SourcePaths implements Function<String, String> {

    /** Never source roots, and the ones that are large enough to make the walk slow. */
    private static final Set<String> SKIP = Set.of(
            "build", ".gradle", ".git", ".idea", ".claude", "research_repos", "node_modules", "run", "runs",
            "harness-output", "out", "bin", "plan");

    private final Path root;
    private final List<Path> sourceRoots;
    private final Map<String, String> resolved = new HashMap<>();

    private SourcePaths(Path root, List<Path> sourceRoots) {
        this.root = root;
        this.sourceRoots = sourceRoots;
    }

    public static SourcePaths forRepository() {
        String configured = System.getProperty("crystalgraphics.harness.repoRoot");
        Path root = configured != null ? Paths.get(configured) : outermostBuild(Paths.get("").toAbsolutePath());
        return new SourcePaths(root, sourceRootsUnder(root));
    }

    @Override
    public String apply(String source) {
        return resolved.computeIfAbsent(source, this::resolve);
    }

    private String resolve(String source) {
        int colon = source.lastIndexOf(':');
        String file = colon < 0 ? source : source.substring(0, colon);
        String line = colon < 0 ? "" : source.substring(colon);
        for (Path sourceRoot : sourceRoots) {
            Path candidate = sourceRoot.resolve(file);
            if (Files.isRegularFile(candidate)) {
                return root.relativize(candidate).toString().replace('\\', '/') + line;
            }
        }
        return source;
    }

    private static Path outermostBuild(Path from) {
        Path found = from;
        for (Path at = from; at != null; at = at.getParent()) {
            if (Files.isRegularFile(at.resolve("settings.gradle.kts")) || Files.isRegularFile(at.resolve("settings.gradle"))) {
                found = at;
            }
        }
        return found;
    }

    /** Every {@code src/<set>/java} under {@code root}, shortest first so a module beats a copy of it. */
    private static List<Path> sourceRootsUnder(Path root) {
        List<Path> out = new ArrayList<>();
        try {
            Files.walkFileTree(root, Set.of(), 10, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                    Path name = dir.getFileName();
                    if (name != null && SKIP.contains(name.toString()) && !dir.equals(root)) return FileVisitResult.SKIP_SUBTREE;
                    Path parent = dir.getParent();
                    if (name != null && name.toString().equals("java") && parent != null && parent.getParent() != null
                            && parent.getParent().getFileName() != null
                            && parent.getParent().getFileName().toString().equals("src")) {
                        out.add(dir);
                        return FileVisitResult.SKIP_SUBTREE;
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFileFailed(Path file, IOException exc) {
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException ignored) {
            // an unreadable tree leaves the locations as the trace wrote them
        }
        out.sort(Comparator.comparingInt((Path p) -> p.getNameCount()).thenComparing(Path::toString));
        return out;
    }
}
