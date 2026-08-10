package com.gahyeonbot.core;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class CoreDependencyBoundaryTest {
    private static final List<String> FORBIDDEN_IMPORTS = List.of(
            "net.dv8tion",
            "com.sedmelluq",
            "moe.kyokobot",
            "org.springframework",
            "jakarta.",
            "com.sun.jna",
            "com.gahyeonbot.services",
            "com.gahyeonbot.adapters");

    @Test
    void coreHasNoFrameworkProviderOrPlatformImports() throws IOException {
        Path core = Path.of("src/main/java/com/gahyeonbot/core");
        try (var files = Files.walk(core)) {
            List<String> violations = files
                    .filter(path -> path.toString().endsWith(".java"))
                    .flatMap(path -> imports(path).stream()
                            .filter(CoreDependencyBoundaryTest::isForbidden)
                            .map(line -> core.relativize(path) + ": " + line.trim()))
                    .toList();

            assertThat(violations)
                    .as("Gahyeon Core must remain independent of frameworks, providers, and clients")
                    .isEmpty();
        }
    }

    private static List<String> imports(Path path) {
        try {
            return Files.readAllLines(path).stream()
                    .filter(line -> line.stripLeading().startsWith("import "))
                    .toList();
        } catch (IOException error) {
            throw new IllegalStateException("Failed to inspect " + path, error);
        }
    }

    private static boolean isForbidden(String line) {
        return FORBIDDEN_IMPORTS.stream().anyMatch(line::contains);
    }
}
