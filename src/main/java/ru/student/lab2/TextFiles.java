package ru.student.lab2;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.function.Consumer;
import java.util.regex.*;
import java.util.stream.Stream;

final class TextFiles {
    // Буквы и цифры сохраняются, пунктуация служит разделителем.
    private static final Pattern WORD = Pattern.compile("[\\p{L}\\p{N}]+");

    static List<Path> list(Path directory) throws IOException {
        try (Stream<Path> files = Files.list(directory)) {
            List<Path> result = files.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().endsWith(".txt"))
                    .filter(p -> !Set.of("expected-statistics.txt", "distribution.txt")
                            .contains(p.getFileName().toString()))
                    .sorted().toList();
            if (result.isEmpty()) throw new IOException("Нет входных .txt файлов: " + directory);
            return result;
        }
    }

    static void read(Path file, Consumer<String> action) throws IOException {
        try (BufferedReader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            String line;
            while ((line = reader.readLine()) != null) {
                Matcher matcher = WORD.matcher(line.toLowerCase(Locale.ROOT));
                while (matcher.find()) action.accept(matcher.group());
                if (Thread.currentThread().isInterrupted()) throw new IOException("Обработка прервана");
            }
        }
    }

    static Map<String, Long> expected(Path directory) throws IOException {
        Map<String, Long> result = new TreeMap<>();
        try (BufferedReader reader = Files.newBufferedReader(
                directory.resolve("expected-statistics.txt"), StandardCharsets.UTF_8)) {
            if (!"word count".equals(reader.readLine())) throw new IOException("Неверный заголовок эталона");
            String line;
            while ((line = reader.readLine()) != null) {
                String[] parts = line.trim().split("\\s+");
                if (parts.length != 2) throw new IOException("Неверная строка эталона: " + line);
                long count = Long.parseLong(parts[1]);
                if (count < 0 || result.putIfAbsent(parts[0], count) != null)
                    throw new IOException("Некорректный счётчик или повтор слова");
            }
        }
        return result;
    }

    static Map<String, Long> toLong(Map<String, Integer> source) {
        Map<String, Long> result = new HashMap<>();
        source.forEach((word, count) -> result.put(word, count.longValue()));
        return result;
    }

    private TextFiles() { }
}
