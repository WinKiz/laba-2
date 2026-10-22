package ru.student.lab2;

import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.Consumer;

/** Семь реализаций; первые три общие get/put намеренно демонстрируют гонки. */
public final class Analyzers {
    public static final List<String> NAMES = List.of("sequential", "hashmap", "synchronized-map",
            "concurrent-get-put", "concurrent-merge", "long-adder", "local-maps");

    public static boolean isSafe(String name) {
        return Set.of("sequential", "concurrent-merge", "long-adder", "local-maps").contains(name);
    }

    public static WordAnalyzer get(String name) {
        return switch (name) {
            case "sequential" -> new SequentialAnalyzer();
            case "hashmap" -> new SharedMapAnalyzer(0);
            case "synchronized-map" -> new SharedMapAnalyzer(1);
            case "concurrent-get-put" -> new SharedMapAnalyzer(2);
            case "concurrent-merge" -> new SharedMapAnalyzer(3);
            case "long-adder" -> new LongAdderAnalyzer();
            case "local-maps" -> new LocalMapsAnalyzer();
            default -> throw new IllegalArgumentException("Неизвестный вариант: " + name);
        };
    }

    @FunctionalInterface
    interface FileGroupTask<T> { T run(List<Path> files) throws Exception; }

    // Ровно threads задач: у каждого работника своя группа файлов.
    // Общая защёлка разрешает им начать обработку вместе.
    static <T> List<T> parallel(List<Path> files, int threads, FileGroupTask<T> task) throws Exception {
        if (threads <= 0) throw new IllegalArgumentException("threads должен быть положительным");
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<T>> futures = new ArrayList<>();
        try {
            for (int t = 0; t < threads; t++) {
                List<Path> group = new ArrayList<>();
                for (int i = t; i < files.size(); i += threads) group.add(files.get(i));
                futures.add(pool.submit(() -> { start.await(); return task.run(group); }));
            }
            start.countDown();
            List<T> result = new ArrayList<>();
            for (Future<T> future : futures) {
                try { result.add(future.get()); }
                catch (ExecutionException e) {
                    if (e.getCause() instanceof Exception cause) throw cause;
                    throw e;
                }
            }
            return result;
        } finally {
            start.countDown();
            pool.shutdownNow();
            if (!pool.awaitTermination(1, TimeUnit.MINUTES))
                throw new IllegalStateException("Рабочие потоки не завершились");
        }
    }

    static void process(List<Path> files, Consumer<String> update) throws Exception {
        for (Path file : files) TextFiles.read(file, update);
    }

    private Analyzers() { }
}

final class SequentialAnalyzer implements WordAnalyzer {
    @Override public Map<String, Long> analyze(Path directory, int threads) throws Exception {
        Map<String, Integer> map = new HashMap<>();
        Analyzers.process(TextFiles.list(directory), w -> map.put(w, map.getOrDefault(w, 0) + 1));
        return TextFiles.toLong(map);
    }
}

final class SharedMapAnalyzer implements WordAnalyzer {
    private final int mode;
    SharedMapAnalyzer(int mode) { this.mode = mode; }

    @Override public Map<String, Long> analyze(Path directory, int threads) throws Exception {
        Map<String, Integer> map = switch (mode) {
            case 0 -> new HashMap<>(); // Намеренно небезопасно при конкурентных изменениях.
            case 1 -> Collections.synchronizedMap(new HashMap<>());
            default -> new ConcurrentHashMap<>();
        };
        Consumer<String> update = mode == 3
                ? w -> map.merge(w, 1, Integer::sum)
                // synchronizedMap защищает отдельные вызовы, но не пару get + put!
                : w -> map.put(w, map.getOrDefault(w, 0) + 1);
        Analyzers.parallel(TextFiles.list(directory), threads, group -> {
            Analyzers.process(group, update);
            return Boolean.TRUE;
        });
        return TextFiles.toLong(map); // Читаем только после завершения работников.
    }
}

final class LongAdderAnalyzer implements WordAnalyzer {
    @Override public Map<String, Long> analyze(Path directory, int threads) throws Exception {
        ConcurrentHashMap<String, LongAdder> map = new ConcurrentHashMap<>();
        Analyzers.parallel(TextFiles.list(directory), threads, group -> {
            Analyzers.process(group, w -> map.computeIfAbsent(w, key -> new LongAdder()).increment());
            return Boolean.TRUE;
        });
        Map<String, Long> result = new HashMap<>();
        map.forEach((w, adder) -> result.put(w, adder.sum()));
        return result;
    }
}

final class LocalMapsAnalyzer implements WordAnalyzer {
    @Override public Map<String, Long> analyze(Path directory, int threads) throws Exception {
        List<Map<String, Integer>> locals = Analyzers.parallel(TextFiles.list(directory), threads, group -> {
            Map<String, Integer> map = new HashMap<>();
            Analyzers.process(group, w -> map.put(w, map.getOrDefault(w, 0) + 1));
            return map;
        });
        Map<String, Long> result = new HashMap<>();
        for (Map<String, Integer> local : locals)
            local.forEach((w, count) -> result.merge(w, count.longValue(), Long::sum));
        return result;
    }
}
