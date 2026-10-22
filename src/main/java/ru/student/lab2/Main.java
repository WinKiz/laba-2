package ru.student.lab2;

import java.nio.file.*;
import java.util.*;

public final class Main {
    public static void main(String[] args) throws Exception {
        Map<String, String> options = new HashMap<>();
        for (String arg : args) {
            if (arg.equals("--help")) {
                System.out.println("--data=каталог --out=каталог --variant=all|имя --threads=1,2,4,8,16,32,64 --warmups=2 --runs=5");
                System.out.println("Варианты: " + Analyzers.NAMES);
                return;
            }
            String[] parts = arg.split("=", 2);
            if (parts.length != 2 || !Set.of("--data", "--out", "--variant", "--threads", "--warmups", "--runs").contains(parts[0]))
                throw new IllegalArgumentException("Неизвестный параметр: " + arg);
            options.put(parts[0], parts[1]);
        }
        Path data = Path.of(options.getOrDefault("--data", "generated-data/default"));
        Path output = Path.of(options.getOrDefault("--out", "results/latest"));
        if (output.toAbsolutePath().normalize().startsWith(data.toAbsolutePath().normalize()))
            throw new IllegalArgumentException("Результаты нужно сохранять вне входного каталога");
        int[] threads = Arrays.stream(options.getOrDefault("--threads", "1,2,4,8,16,32,64").split(","))
                .map(String::trim).mapToInt(Integer::parseInt).distinct().toArray();
        String variant = options.getOrDefault("--variant", "all");
        List<String> variants = variant.equals("all") ? Analyzers.NAMES : List.of(variant);
        // База всегда последовательная, даже если выбран один параллельный вариант.
        if (!variants.contains("sequential")) variants = List.of("sequential", variant);
        Benchmark.run(data, output, variants, threads,
                Integer.parseInt(options.getOrDefault("--warmups", "2")),
                Integer.parseInt(options.getOrDefault("--runs", "5")));
        System.out.println("Готово. Результаты: " + output.toAbsolutePath());
    }
}
