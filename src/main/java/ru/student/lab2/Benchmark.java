package ru.student.lab2;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;

/** Чтение/нормализация/подсчёт/объединение измеряются; проверка и вывод — нет. */
public final class Benchmark {
    private record Sample(double seconds, boolean correct, int mismatches, long total, String error) { }
    private record Row(String variant, int threads, double median, double min, int correctRuns,
                       int runs, int mismatches, long total) { }

    private static Sample sample(WordAnalyzer analyzer, Path directory, int threads,
                                 Map<String, Long> expected) throws Exception {
        long start = System.nanoTime();
        Map<String, Long> actual;
        try {
            actual = analyzer.analyze(directory, threads);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw interrupted;
        } catch (Exception error) {
            return new Sample((System.nanoTime() - start) / 1e9, false, -1, -1,
                    error.getClass().getSimpleName() + ": " + error.getMessage());
        }
        double seconds = (System.nanoTime() - start) / 1e9;
        Set<String> keys = new HashSet<>(expected.keySet());
        keys.addAll(actual.keySet());
        int mismatches = 0;
        for (String key : keys) if (!Objects.equals(expected.get(key), actual.get(key))) mismatches++;
        long total = actual.values().stream().mapToLong(Long::longValue).sum();
        return new Sample(seconds, actual.equals(expected), mismatches, total, "");
    }

    public static void run(Path directory, Path output, List<String> variants, int[] threads,
                           int warmups, int runs) throws Exception {
        if (warmups < 0 || runs < 1 || Arrays.stream(threads).anyMatch(t -> t < 1))
            throw new IllegalArgumentException("Нужны warmups >= 0, runs >= 1, threads >= 1");
        Files.createDirectories(output);
        Path generation = directory.resolve("generation.properties");
        if (Files.isRegularFile(generation))
            Files.copy(generation, output.resolve("generation.properties"), StandardCopyOption.REPLACE_EXISTING);
        Map<String, Long> expected = TextFiles.expected(directory);
        List<Path> inputs = new ArrayList<>(TextFiles.list(directory));
        inputs.add(directory.resolve("expected-statistics.txt"));
        String fingerprint = fingerprint(inputs);
        long expectedWords = expected.values().stream().mapToLong(Long::longValue).sum();
        Files.writeString(output.resolve("environment.txt"),
                "date=" + java.time.OffsetDateTime.now(java.time.ZoneId.of("Asia/Yakutsk"))
                + "\njava=" + System.getProperty("java.runtime.version")
                + "\nvm=" + System.getProperty("java.vm.name")
                + "\nos=" + System.getProperty("os.name") + " " + System.getProperty("os.arch")
                + "\nprocessors=" + Runtime.getRuntime().availableProcessors()
                + "\nfiles=" + (inputs.size() - 1) + "\nexpectedWords=" + expectedWords
                + "\nwarmups=" + warmups + "\nruns=" + runs
                + "\ninputSha256=" + fingerprint + "\nnormalization=Unicode letters/digits, Locale.ROOT\n"
                + "timed=file listing, UTF-8 reading, normalization, counting, pool lifecycle, merging\n"
                + "cache=OS file cache is not cleared; variants run sequentially in listed order\n",
                StandardCharsets.UTF_8);
        List<Row> rows = new ArrayList<>();
        boolean safeFailure = false;
        try (PrintWriter raw = new PrintWriter(Files.newBufferedWriter(output.resolve("samples.csv"), StandardCharsets.UTF_8))) {
            raw.println("variant,threads,phase,run,time_seconds,correct,mismatched_words,total_words,error");
            for (String variant : variants) {
                WordAnalyzer analyzer = Analyzers.get(variant);
                int[] counts = variant.equals("sequential") ? new int[]{1} : threads;
                for (int count : counts) {
                    System.out.printf("%s: %s, потоков=%d ...%n", directory.getFileName(), variant, count);
                    double[] times = new double[runs];
                    int ok = 0, maxMismatch = 0;
                    long total = 0;
                    for (int i = -warmups; i < runs; i++) {
                        Sample s = sample(analyzer, directory, count, expected);
                        raw.printf(Locale.ROOT, "%s,%d,%s,%d,%.9f,%b,%d,%d,\"%s\"%n", variant, count,
                                i < 0 ? "warmup" : "measurement", i < 0 ? i + warmups + 1 : i + 1,
                                s.seconds, s.correct, s.mismatches, s.total, s.error.replace("\"", "\"\""));
                        raw.flush();
                        if (Analyzers.isSafe(variant) && !s.correct) safeFailure = true;
                        if (i >= 0) {
                            times[i] = s.seconds;
                            if (s.correct) ok++;
                            maxMismatch = s.mismatches < 0 ? Integer.MAX_VALUE : Math.max(maxMismatch, s.mismatches);
                            total = s.total;
                        }
                    }
                    Arrays.sort(times);
                    double median = runs % 2 == 0 ? (times[runs / 2 - 1] + times[runs / 2]) / 2 : times[runs / 2];
                    rows.add(new Row(variant, count, median, times[0], ok, runs, maxMismatch, total));
                    System.out.printf(Locale.ROOT, "  %.6f с; совпало %d/%d измерений%n", median, ok, runs);
                }
            }
        }
        double baseline = rows.stream().filter(r -> r.variant.equals("sequential"))
                .mapToDouble(Row::median).findFirst().orElse(Double.NaN);
        try (PrintWriter csv = new PrintWriter(Files.newBufferedWriter(output.resolve("measurements.csv"), StandardCharsets.UTF_8));
             PrintWriter md = new PrintWriter(Files.newBufferedWriter(output.resolve("timings.md"), StandardCharsets.UTF_8))) {
            csv.println("variant,threads,time_seconds,min_seconds,speedup,efficiency_percent,correct,correct_runs,runs,max_mismatched_words,last_total_words");
            md.println("# Измерения\n\nСлов: " + expectedWords + ". Прогревов: " + warmups + ". Измерений: " + runs + ".\n");
            md.println("| Вариант | Потоков | Медиана, с | Ускорение | Эффективность, % | Совпало запусков |\n|---|---:|---:|---:|---:|---:|");
            for (Row row : rows) {
                double speedup = baseline / row.median;
                csv.printf(Locale.ROOT, "%s,%d,%.9f,%.9f,%.6f,%.4f,%b,%d,%d,%d,%d%n", row.variant, row.threads,
                        row.median, row.min, speedup, speedup / row.threads * 100,
                        row.correctRuns == runs, row.correctRuns, runs, row.mismatches, row.total);
                md.printf(Locale.ROOT, "| %s | %d | %.6f | %.3f | %.2f | %d/%d |%n", row.variant, row.threads,
                        row.median, speedup, speedup / row.threads * 100, row.correctRuns, runs);
            }
            md.println("\nУскорение некорректного варианта не является ускорением корректного решения. Совпадение отдельных запусков не доказывает потокобезопасность.");
        }
        if (!fingerprint.equals(fingerprint(inputs))) throw new IOException("Входные файлы изменились во время измерения");
        if (safeFailure) throw new IllegalStateException("Корректная реализация не прошла проверку; см. samples.csv");
    }

    private static String fingerprint(List<Path> files) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        byte[] buffer = new byte[65536];
        for (Path file : files) {
            digest.update(file.getFileName().toString().getBytes(StandardCharsets.UTF_8));
            try (InputStream input = Files.newInputStream(file)) {
                int n;
                while ((n = input.read(buffer)) != -1) digest.update(buffer, 0, n);
            }
        }
        return HexFormat.of().formatHex(digest.digest());
    }
    private Benchmark() { }
}
