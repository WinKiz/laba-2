package ru.student.lab2;

import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/** Проверки нормализации, точных счётчиков и распределения файлов. Без сторонних библиотек. */
public final class SelfTest {
    public static void main(String[] args) throws Exception {
        Path fixture = Files.createTempDirectory("lab2-fixture-");
        try {
            Files.writeString(fixture.resolve("a.txt"), "Word0001 WORD0001, word0001!\nПривет, ПРИВЕТ!", StandardCharsets.UTF_8);
            Files.writeString(fixture.resolve("b.txt"), "word0002; WORD0002? word0001", StandardCharsets.UTF_8);
            Files.writeString(fixture.resolve("c.txt"), "", StandardCharsets.UTF_8);
            Files.writeString(fixture.resolve("distribution.txt"), "word probability\nword0001 0.5\n", StandardCharsets.UTF_8);
            Files.writeString(fixture.resolve("expected-statistics.txt"), "word count\nword0001 4\nword0002 2\nпривет 2\n", StandardCharsets.UTF_8);
            Map<String, Long> expected = Map.of("word0001", 4L, "word0002", 2L, "привет", 2L);
            for (String variant : Analyzers.NAMES) {
                for (int threads : new int[]{1, 2, 4, 8}) {
                    if (!Analyzers.isSafe(variant) && threads != 1) continue;
                    Map<String, Long> actual = Analyzers.get(variant).analyze(fixture, threads);
                    if (!actual.equals(expected)) throw new AssertionError(variant + ": " + actual);
                }
            }
            if (!TextFiles.expected(fixture).equals(expected)) throw new AssertionError("Чтение эталона");
            System.out.println("SelfTest OK: нормализация, исключение служебных файлов, счётчики, пустой файл, потоков больше файлов.");
        } finally {
            try (var paths = Files.walk(fixture)) {
                for (Path p : paths.sorted(Comparator.reverseOrder()).toList()) Files.delete(p);
            }
        }
    }
}
