package ru.student.lab2;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/** Независимый генератор считает эталон ДО добавления регистра и пунктуации. */
public final class TestDataGenerator {
    public static void main(String[] args) throws Exception {
        Path output = Path.of(args.length > 0 ? args[0] : "generated-data/default");
        int files = args.length > 1 ? Integer.parseInt(args[1]) : 128;
        int words = args.length > 2 ? Integer.parseInt(args[2]) : 250_000;
        int vocabulary = args.length > 3 ? Integer.parseInt(args[3]) : 500;
        long seed = args.length > 4 ? Long.parseLong(args[4]) : new Random().nextLong();
        generate(output, files, words, vocabulary, seed);
    }

    public static void generate(Path output, int files, int words, int vocabulary, long seed) throws IOException {
        if (files <= 0 || words <= 0 || vocabulary <= 0)
            throw new IllegalArgumentException("Размеры корпуса должны быть положительными");
        // Не перезаписываем существующий корпус во время серии измерений.
        if (Files.exists(output)) {
            try (var entries = Files.list(output)) {
                if (entries.findAny().isPresent()) throw new IOException("Каталог уже содержит данные: " + output);
            }
        }
        Files.createDirectories(output);
        Random random = new Random(seed);
        String[] names = new String[vocabulary];
        double[] probabilities = new double[vocabulary];
        double[] cumulative = new double[vocabulary];
        long[] expected = new long[vocabulary];
        double sum = 0;
        for (int i = 0; i < vocabulary; i++) {
            names[i] = String.format(Locale.ROOT, "word%04d", i);
            probabilities[i] = 0.05 + random.nextDouble();
            sum += probabilities[i];
        }
        double accumulated = 0;
        for (int i = 0; i < vocabulary; i++) {
            probabilities[i] /= sum;
            cumulative[i] = accumulated += probabilities[i];
        }
        cumulative[vocabulary - 1] = 1;
        String[] punctuation = {"", "", "", ".", ",", ";", ":", "!", "?"};
        for (int f = 1; f <= files; f++) {
            Path file = output.resolve(String.format(Locale.ROOT, "text-%03d.txt", f));
            try (BufferedWriter writer = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
                for (int i = 0; i < words; i++) {
                    int index = Arrays.binarySearch(cumulative, random.nextDouble());
                    if (index < 0) index = -index - 1;
                    expected[index]++;
                    String word = names[index];
                    int casing = random.nextInt(4);
                    if (casing == 0) word = word.toUpperCase(Locale.ROOT);
                    else if (casing == 1) word = "W" + word.substring(1);
                    writer.write(word);
                    writer.write(punctuation[random.nextInt(punctuation.length)]);
                    writer.write((i + 1) % 20 == 0 ? '\n' : ' ');
                }
            }
        }
        try (BufferedWriter writer = Files.newBufferedWriter(output.resolve("expected-statistics.txt"), StandardCharsets.UTF_8)) {
            writer.write("word count\n");
            for (int i = 0; i < vocabulary; i++)
                if (expected[i] != 0) writer.write(names[i] + " " + expected[i] + "\n");
        }
        try (BufferedWriter writer = Files.newBufferedWriter(output.resolve("distribution.txt"), StandardCharsets.UTF_8)) {
            writer.write("word probability\n");
            for (int i = 0; i < vocabulary; i++)
                writer.write(String.format(Locale.ROOT, "%s %.17g%n", names[i], probabilities[i]));
        }
        Files.writeString(output.resolve("generation.properties"),
                "seed=" + seed + "\nfiles=" + files + "\nwordsPerFile=" + words
                        + "\nvocabulary=" + vocabulary + "\n", StandardCharsets.UTF_8);
        System.out.printf("Создан корпус %s: файлов=%d, слов=%d, seed=%d%n",
                output, files, (long) files * words, seed);
    }
}
