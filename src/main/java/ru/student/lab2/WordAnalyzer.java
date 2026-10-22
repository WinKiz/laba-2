package ru.student.lab2;

import java.nio.file.Path;
import java.util.Map;

/** Единый интерфейс для всех способов подсчёта. */
public interface WordAnalyzer {
    Map<String, Long> analyze(Path directory, int threads) throws Exception;
}
