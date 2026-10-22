# Лабораторная работа №2
## Многопоточный анализ текстовых данных

### Цель работы

Изучить средства `java.util.concurrent` для совместной работы потоков с изменяемыми данными и исследовать производительность различных способов синхронизации.

В работе необходимо:

- реализовать последовательный подсчёт частоты слов;
- реализовать многопоточные варианты;
- исследовать `HashMap`, `synchronizedMap` и `ConcurrentHashMap`;
- рассмотреть атомарность составных операций;
- применить `ConcurrentHashMap` совместно с `LongAdder`;
- исследовать альтернативный вариант с локальными словарями потоков;
- измерить время выполнения при различном количестве потоков;
- сравнить корректность и производительность реализаций.

---

# 1. Генерация тестовых данных

Для лабораторной работы необходимо использовать одинаковый набор входных данных для всех реализаций.

Тестовые данные рекомендуется генерировать специальной программой. При каждом запуске генератора:

- создаётся новый набор случайных вероятностей появления слов;
- генерируется новый корпус текстовых файлов;
- для каждого выбранного слова увеличивается эталонный счётчик;
- после завершения генерации создаётся файл `expected-statistics.txt` с точной статистикой;
- дополнительно создаётся файл `distribution.txt` с вероятностями, использованными при генерации.

Таким образом, правильность результата программы можно проверять не только относительно другой реализации, но и относительно независимого эталона, созданного генератором.

## 1.1. Пример генератора

Пример ниже рассчитан на Java 21.

```java
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

public class TestDataGenerator {

    private static final int FILE_COUNT = 128;
    private static final int WORDS_PER_FILE = 250_000;
    private static final int VOCABULARY_SIZE = 500;

    private static final int WORDS_PER_LINE = 20;

    private static final List<String> PUNCTUATION =
            Arrays.asList("", "", "", ".", ",", ";", ":", "!", "?");

    public static void main(String[] args) throws IOException {
        Path output = args.length > 0
                ? Paths.get(args[0])
                : Paths.get("generated-data");

        Files.createDirectories(output);

        Random random = new Random();

        List<String> vocabulary = createVocabulary();
        double[] probabilities = createRandomProbabilities(
                vocabulary.size(), random);
        double[] cumulative = createCumulative(probabilities);

        Map<String, Long> statistics = new HashMap<>();

        writeDistribution(output.resolve("distribution.txt"),
                vocabulary, probabilities);

        for (int fileIndex = 1; fileIndex <= FILE_COUNT; fileIndex++) {
            Path file = output.resolve(
                    "text-%03d.txt".formatted(fileIndex));

            generateFile(
                    file,
                    vocabulary,
                    cumulative,
                    statistics,
                    random
            );
        }

        writeStatistics(
                output.resolve("expected-statistics.txt"),
                statistics
        );

        System.out.println("Generated data: " + output.toAbsolutePath());
        System.out.println("Files: " + FILE_COUNT);
        System.out.println("Words per file: " + WORDS_PER_FILE);
        System.out.println("Vocabulary size: " + VOCABULARY_SIZE);
    }

    private static List<String> createVocabulary() {
        List<String> vocabulary = new ArrayList<>();

        for (int i = 0; i < VOCABULARY_SIZE; i++) {
            vocabulary.add("word%04d".formatted(i));
        }

        return vocabulary;
    }

    private static double[] createRandomProbabilities(
            int size,
            Random random) {

        double[] weights = new double[size];
        double sum = 0.0;

        for (int i = 0; i < size; i++) {
            /*
             * Каждому слову назначается новое случайное положительное
             * значение. После нормализации получаются вероятности.
             */
            weights[i] = 0.05 + random.nextDouble();
            sum += weights[i];
        }

        double[] probabilities = new double[size];

        for (int i = 0; i < size; i++) {
            probabilities[i] = weights[i] / sum;
        }

        return probabilities;
    }

    private static double[] createCumulative(double[] probabilities) {
        double[] cumulative = new double[probabilities.length];

        double sum = 0.0;

        for (int i = 0; i < probabilities.length; i++) {
            sum += probabilities[i];
            cumulative[i] = sum;
        }

        cumulative[cumulative.length - 1] = 1.0;

        return cumulative;
    }

    private static void generateFile(
            Path file,
            List<String> vocabulary,
            double[] cumulative,
            Map<String, Long> statistics,
            Random random) throws IOException {

        try (BufferedWriter writer = Files.newBufferedWriter(file)) {

            for (int i = 0; i < WORDS_PER_FILE; i++) {
                int index = selectWord(cumulative, random);

                String word = vocabulary.get(index);

                statistics.merge(word, 1L, Long::sum);

                writer.write(formatWord(word, random));

                if ((i + 1) % WORDS_PER_LINE == 0) {
                    writer.newLine();
                } else {
                    writer.write(' ');
                }
            }
        }
    }

    private static int selectWord(
            double[] cumulative,
            Random random) {

        double value = random.nextDouble();

        int left = 0;
        int right = cumulative.length - 1;

        while (left < right) {
            int middle = (left + right) >>> 1;

            if (value < cumulative[middle]) {
                right = middle;
            } else {
                left = middle + 1;
            }
        }

        return left;
    }

    private static String formatWord(
            String word,
            Random random) {

        String result = word;

        if (random.nextInt(4) == 0) {
            result = result.toUpperCase();
        } else if (random.nextInt(4) == 0) {
            result = Character.toUpperCase(result.charAt(0))
                    + result.substring(1);
        }

        String punctuation =
                PUNCTUATION.get(random.nextInt(PUNCTUATION.size()));

        return result + punctuation;
    }

    private static void writeDistribution(
            Path file,
            List<String> vocabulary,
            double[] probabilities) throws IOException {

        try (BufferedWriter writer = Files.newBufferedWriter(file)) {
            writer.write("word probability");
            writer.newLine();

            for (int i = 0; i < vocabulary.size(); i++) {
                writer.write(
                        vocabulary.get(i)
                                + " "
                                + "%.12f".formatted(probabilities[i])
                );
                writer.newLine();
            }
        }
    }

    private static void writeStatistics(
            Path file,
            Map<String, Long> statistics) throws IOException {

        try (BufferedWriter writer = Files.newBufferedWriter(file)) {

            writer.write("word count");
            writer.newLine();

            statistics.entrySet()
                    .stream()
                    .sorted(Map.Entry.comparingByKey())
                    .forEach(entry -> {
                        try {
                            writer.write(
                                    entry.getKey()
                                            + " "
                                            + entry.getValue()
                            );
                            writer.newLine();
                        } catch (IOException e) {
                            throw new RuntimeException(e);
                        }
                    });
        }
    }
}
```

## 1.2. Результат работы генератора

После запуска каталога с тестовыми данными достаточно для всей лабораторной.

Например:

```text
generated-data/
├── text-001.txt
├── text-002.txt
├── text-003.txt
├── ...
├── text-128.txt
├── distribution.txt
└── expected-statistics.txt
```

Файл `expected-statistics.txt` содержит эталонный результат:

```text
word count
word0000 2187
word0001 431
word0002 3152
...
```

Файл `distribution.txt` содержит вероятности:

```text
word probability
word0000 0.001928431245
word0001 0.000842173511
word0002 0.002614823104
...
```

### Условия использования генератора

Каждый запуск генератора создаёт новое распределение вероятностей и новый корпус.

После начала экспериментов входные файлы изменять нельзя.

Для всех реализаций должны использоваться **одни и те же сгенерированные файлы**.

Для новой серии экспериментов можно заново запустить генератор и получить другой корпус.

---


# 2. Организация проекта и Git-репозитория

Лабораторную рекомендуется выполнять в отдельном проекте и отдельном Git-репозитории.

## 2.1. Рекомендуемая структура проекта

Для проекта на Java 21 удобно использовать Maven и стандартную структуру каталогов:

```text
lab2-concurrent-text-analyzer/
├── pom.xml
├── README.md
├── .gitignore
├── src/
│   ├── main/
│   │   └── java/
│   │       └── ru/yourname/lab2/
│   │           ├── TestDataGenerator.java
│   │           └── Main.java
│   └── test/
│       └── java/
│           └── ru/yourname/lab2/
└── results/
    ├── measurements.csv
    ├── timings.md
    ├── time.png
    ├── speedup.png
    ├── efficiency.png
    └── conclusions.md
```

Название пакета выберите самостоятельно. Например:

```java
package ru.student.lab2;
```

В `Main.java` рекомендуется разместить программу, выполняющую лабораторные эксперименты. Генератор тестовых данных должен оставаться отдельной программой `TestDataGenerator`.

## 2.2. Что не следует смешивать в одном классе

Не рекомендуется помещать генерацию данных, чтение файлов, многопоточную обработку, измерение времени и вывод результатов в один большой класс.

Минимально удобно разделить программу на следующие логические части:

```text
TestDataGenerator
        ↓
генерация входных данных

TextAnalyzer
        ↓
интерфейс алгоритма подсчёта

SequentialAnalyzer
SynchronizedMapAnalyzer
ConcurrentHashMapAnalyzer
LongAdderAnalyzer
LocalMapsAnalyzer
        ↓
конкретные реализации

Benchmark
        ↓
измерение времени и сравнение результатов
```

Конкретная степень декомпозиции остаётся на усмотрение студента. Главное требование — разные реализации алгоритма должны быть легко заменяемыми и сравнимыми.

## 2.3. Вариант интерфейса алгоритма

Для единообразия реализации можно использовать собственный интерфейс:

```java
public interface WordAnalyzer {
    Map<String, Long> analyze(Path directory, int threadCount)
            throws IOException, InterruptedException;
}
```

Тогда различные реализации имеют одинаковый внешний интерфейс:

```text
WordAnalyzer
    ├── SequentialAnalyzer
    ├── SynchronizedMapAnalyzer
    ├── ConcurrentHashMapAnalyzer
    ├── LongAdderAnalyzer
    └── LocalMapsAnalyzer
```

Использование интерфейса не является обязательным. Оно рекомендуется для удобного сравнения реализаций.

## 2.4. Git-репозиторий

После создания проекта необходимо создать отдельный Git-репозиторий для лабораторной.

Рекомендуемое имя:

```text
lab2-concurrent-text-analyzer
```

Первоначальная последовательность:

```bash
git init
git add .
git commit -m "Initial project"
```

После создания репозитория на GitHub добавьте удалённый репозиторий и отправьте проект:

```bash
git branch -M main
git remote add origin <URL-репозитория>
git push -u origin main
```

## 2.5. `.gitignore`

Сгенерированные данные и служебные файлы IDE не следует хранить в Git.

Минимальный `.gitignore`:

```gitignore
# IntelliJ IDEA
.idea/
*.iml

# Maven
target/

# Generated test data
generated-data/

# Local experiment output
results/raw/

# OS files
.DS_Store
Thumbs.db
```

## 2.6. Что должно быть в репозитории

В репозитории должны находиться:

- исходный код генератора;
- исходный код всех реализованных вариантов анализатора;
- файл `pom.xml`, если используется Maven;
- `README.md` с кратким описанием запуска;
- таблицы и графики итогового эксперимента;
- выводы по лабораторной.

Каталог `generated-data/` в Git обычно не добавляется: входные данные можно получить повторным запуском `TestDataGenerator`.

Файл `expected-statistics.txt` также не требуется хранить в репозитории, если он генерируется заново вместе с тестовыми данными.

## 2.7. Фиксация результатов эксперимента

Для каждого набора тестовых данных желательно сохранить в `results/`:

```text
measurements.csv
```

с исходными измерениями, а также итоговые графики и выводы.

Пример CSV:

```text
variant,threads,time_seconds,correct
sequential,1,4.281,true
synchronized-map,2,2.504,true
synchronized-map,4,1.741,true
concurrent-merge,2,2.102,true
concurrent-merge,4,1.183,true
long-adder,4,0.947,true
```

Так результаты можно обрабатывать автоматически и строить графики повторно.

## 2.8. Рекомендуемый `README.md` репозитория

В корне проекта рекомендуется разместить краткую инструкцию:

````markdown
# Лабораторная работа №2

## Требования

- Java 21
- Maven

## Генерация данных

```bash
mvn compile
java -cp target/classes ru.student.lab2.TestDataGenerator generated-data
```

## Запуск эксперимента

```bash
java -cp target/classes ru.student.lab2.Main generated-data
```

## Результаты

Результаты измерений находятся в `results/`.
````

Конкретные команды запуска необходимо адаптировать к структуре собственного проекта.

## 2.9. Работа с Git во время выполнения

Рекомендуется делать отдельные коммиты по этапам работы, например:

```text
Initial project
Add test data generator
Add sequential analyzer
Add synchronized map implementation
Add ConcurrentHashMap implementation
Add LongAdder implementation
Add local maps implementation
Add benchmark
Add experiment results
Add conclusions
```

Не следует делать один большой коммит в самом конце работы.

---
# 3. Постановка задачи

Необходимо обработать все текстовые файлы и построить частотный словарь:

```text
слово → количество вхождений
```

Например:

```text
word0000 → 2187
word0001 → 431
word0002 → 3152
```

Каждое слово должно учитываться столько раз, сколько оно встретилось во всех входных файлах.

Задачу необходимо решить несколькими способами и сравнить результаты.

---

# 4. Нормализация текста

При чтении файла необходимо привести каждое слово к единому виду.

Для данных, созданных генератором, требуется:

- игнорировать регистр;
- удалить знаки пунктуации;
- считать `word0001`, `WORD0001` и `Word0001,` одним словом `word0001`.

Например:

```text
"Word0001 WORD0001, word0001!"
```

должно дать:

```text
word0001
word0001
word0001
```

В результате:

```text
word0001 → 3
```

Способ выделения и нормализации слов выбирается самостоятельно.

---

# 5. Проверка корректности

Результат каждой реализации необходимо сравнивать с файлом:

```text
expected-statistics.txt
```

Проверять необходимо:

- наличие всех слов;
- отсутствие лишних слов;
- точное значение счётчика каждого слова.

Рекомендуется загружать эталон и результат в две структуры данных и сравнивать их.

Программа должна явно сообщать об обнаружении несовпадения.

---

# 6. Последовательная реализация

Сначала необходимо реализовать последовательную версию.

Для хранения результата используется:

```java
HashMap<String, Integer>
```

Схема:

```text
файлы
  ↓
один поток
  ↓
HashMap
  ↓
результат
```

Пример обновления счётчика:

```java
Map<String, Integer> frequencies = new HashMap<>();

for (String word : words) {
    frequencies.merge(word, 1, Integer::sum);
}
```

Последовательная реализация используется для:

- проверки алгоритма;
- проверки результатов;
- измерения базового времени `T1`.

---

# 7. Многопоточная обработка файлов

Файлы необходимо распределить между потоками.

Например:

```text
Thread 1 → text-001.txt, text-002.txt
Thread 2 → text-003.txt, text-004.txt
Thread 3 → text-005.txt, text-006.txt
Thread 4 → text-007.txt, text-008.txt
```

Количество файлов и количество потоков могут не совпадать.

Каждый поток должен самостоятельно обработать назначенные ему файлы.

Главная задача лабораторной — организовать корректное совместное обновление частотного словаря.

---

# 8. Вариант 1. Общий `HashMap`

Создайте многопоточную реализацию с одним общим:

```java
HashMap<String, Integer>
```

Схема:

```text
Thread 1 ─┐
Thread 2 ─┤
Thread 3 ─┼──→ HashMap
Thread 4 ─┤
```

Проверьте результат относительно `expected-statistics.txt`.

Повторите запуск несколько раз.

### Вопрос

Почему `HashMap` нельзя безопасно использовать для одновременного изменения из нескольких потоков?

Отдельно рассмотрите:

- изменение общего состояния;
- возможность потери обновлений;
- недетерминированность результата.

Правильность одного или нескольких запусков не является доказательством потокобезопасности.

---

# 9. Вариант 2. `Collections.synchronizedMap`

Используйте:

```java
Map<String, Integer> frequencies =
        Collections.synchronizedMap(new HashMap<>());
```

Повторите эксперимент.

Сравните с предыдущим вариантом:

- корректность;
- время выполнения;
- изменение времени при увеличении количества потоков.

---

# 10. Составные операции и атомарность

Рассмотрите выражение:

```java
map.put(word, map.getOrDefault(word, 0) + 1);
```

Оно состоит как минимум из:

```text
get
 ↓
вычисление нового значения
 ↓
put
```

Два потока могут одновременно прочитать одно и то же старое значение:

```text
Thread A: get → 10
Thread B: get → 10

Thread A: put(11)
Thread B: put(11)
```

В результате два увеличения дают одно фактическое увеличение.

Это пример ошибки типа **lost update**.

---

# 11. Вариант 3. `ConcurrentHashMap` с `get` + `put`

Используйте:

```java
ConcurrentHashMap<String, Integer>
```

и вариант:

```java
map.put(word, map.getOrDefault(word, 0) + 1);
```

Проверьте его корректность.

### Важный вывод

`ConcurrentHashMap` обеспечивает потокобезопасный конкурентный доступ к структуре данных, но это не означает, что произвольная последовательность операций над ней автоматически становится одной атомарной операцией.

---

# 12. Вариант 4. `ConcurrentHashMap.merge`

Измените реализацию на:

```java
map.merge(word, 1, Integer::sum);
```

Проверьте корректность и производительность.

Сравните с вариантом:

```java
get + put
```

Объясните, почему `merge` подходит для атомарного обновления счётчика.

---

# 13. Вариант 5. `ConcurrentHashMap` + `LongAdder`

Используйте:

```java
ConcurrentHashMap<String, LongAdder>
```

Логика обновления:

```java
map.computeIfAbsent(word, key -> new LongAdder())
   .increment();
```

После завершения обработки получите итоговые значения через:

```java
adder.sum()
```

Итоговый результат должен быть приведён к форме:

```text
Map<String, Long>
```

или к другой структуре, которую удобно сравнивать с `expected-statistics.txt`.

---

# 14. Вариант 6. Локальные словари потоков

Реализуйте дополнительный вариант без общего изменяемого словаря.

Каждый поток создаёт собственный:

```java
HashMap<String, Integer>
```

Схема:

```text
Thread 1 → Map 1 ──┐
Thread 2 → Map 2 ──┤
Thread 3 → Map 3 ──┼──→ объединение → итоговый Map
Thread 4 → Map 4 ──┘
```

После завершения всех потоков локальные словари объединяются в один итоговый словарь.

Сравните этот вариант с `ConcurrentHashMap`.

---

# 15. Количество потоков

Для каждого варианта исследуйте несколько значений:

```text
1
2
4
8
16
32
64
```

Диапазон можно изменить с учётом характеристик используемой машины.

Для последовательной версии используется `1` поток.

Количество потоков не обязано совпадать с количеством логических процессоров.

---

# 16. Измерение времени

Используйте:

```java
long start = System.nanoTime();

runCalculation();

long end = System.nanoTime();

double seconds =
        (end - start) / 1_000_000_000.0;
```

Измеряемый участок должен содержать именно обработку входных данных.

В измерение не должны входить:

- вывод большого объёма данных в консоль;
- построение графиков;
- ручной ввод;
- вывод полного частотного словаря.

---

# 17. Прогрев JVM и повторные измерения

Для каждого варианта выполните несколько пробных запусков перед измерениями.

Например:

```text
warm-up
warm-up
warm-up

measurement
measurement
measurement
measurement
measurement
```

Используйте одну и ту же методику для всех реализаций.

В таблицу заносите медиану измерений или другое заранее выбранное одинаковое статистическое значение.

---

# 18. Экспериментальные результаты

Соберите результаты для всех вариантов.

Например:

| Вариант | Потоков | Время, с | Корректность |
|---|---:|---:|---|
| Sequential | 1 | | ✓ / ✗ |
| synchronizedMap | 2 | | ✓ / ✗ |
| synchronizedMap | 4 | | ✓ / ✗ |
| ConcurrentHashMap + merge | 2 | | ✓ / ✗ |
| ConcurrentHashMap + merge | 4 | | ✓ / ✗ |
| CHM + LongAdder | 2 | | ✓ / ✗ |
| CHM + LongAdder | 4 | | ✓ / ✗ |
| Local maps | 2 | | ✓ / ✗ |
| Local maps | 4 | | ✓ / ✗ |

Для наивных вариантов с `HashMap` и `ConcurrentHashMap` + `get/put` обязательно фиксируйте фактический результат проверки, даже если он оказался корректным на конкретном запуске.

---

# 19. Ускорение

Пусть:

- `T1` — время последовательной версии;
- `Tp` — время версии с `p` потоками.

Ускорение:

$$
S_p = \frac{T_1}{T_p}
$$

---

# 20. Эффективность

Эффективность:

$$
E_p = \frac{S_p}{p}
$$

В процентах:

$$
E_p = \frac{S_p}{p}\cdot100\%
$$

---

# 21. Графики

Постройте минимум три графика:

1. количество потоков → время выполнения;
2. количество потоков → ускорение;
3. количество потоков → эффективность.

Для сравнения желательно отображать несколько корректных реализаций на одном графике.

---

# 22. Дополнительный эксперимент с размером задачи

Проведите измерения для разных объёмов входных данных.

Например, после изменения параметров генератора:

```text
малый корпус
средний корпус
большой корпус
```

Для каждого корпуса исследуйте несколько вариантов количества потоков.

Сравните масштабируемость.

---

# 23. Вопросы для анализа

Ответьте на следующие вопросы.

### 1.

Корректна ли реализация с общим `HashMap`?

Почему ошибка может проявляться не при каждом запуске?

### 2.

Как изменяется производительность при использовании `synchronizedMap`?

### 3.

Почему `ConcurrentHashMap` + `get`/`put` не гарантирует корректный подсчёт?

### 4.

Почему `merge` подходит для обновления счётчика?

### 5.

Чем `ConcurrentHashMap` отличается от `Collections.synchronizedMap` с точки зрения конкурентного доступа?

### 6.

Как влияет `LongAdder` на производительность?

### 7.

Как изменяется производительность локальных словарей при увеличении количества потоков?

### 8.

Какая из корректных реализаций показывает наименьшее время на вашей машине?

Ответ должен основываться на измерениях.

### 9.

При каком количестве потоков достигается минимальное время?

### 10.

Почему увеличение количества потоков после некоторого значения перестаёт давать пропорциональное ускорение?

### 11.

Как размер входного набора влияет на эффективность параллельной обработки?

---

# 24. Требования к эксперименту

Во всех сравниваемых запусках должны сохраняться одинаковыми:

- набор входных файлов;
- содержимое входных файлов;
- правила нормализации;
- JVM;
- машина;
- методика измерения;
- критерий проверки результата.

При изменении количества потоков объём работы изменяться не должен.

Во время измерений не следует запускать другие тяжёлые задачи, способные существенно изменить загрузку процессора или диска.

---

# 25. Что должно быть в программе

Программа должна позволять задавать:

- каталог с входными данными;
- количество потоков;
- вариант реализации.

Например:

```text
sequential
hashmap
synchronized-map
concurrent-get-put
concurrent-merge
long-adder
local-maps
```

Способ передачи параметров выбирается самостоятельно.

---

# 26. Что сдаётся

Необходимо предоставить:

### Исходный код

Все реализованные варианты.

### Результаты экспериментов

Таблицы с измерениями времени, количеством потоков и результатом проверки корректности.

### Графики

Минимум:

- время выполнения;
- ускорение;
- эффективность.

### Выводы

В выводах необходимо:

- сравнить корректные варианты;
- описать поведение некорректных вариантов;
- сравнить производительность;
- указать влияние количества потоков;
- указать влияние размера входных данных;
- объяснить причины наблюдаемого поведения.

---

# 27. Требования к выводам

Вывод должен опираться на полученные данные.

Недостаточно написать:

> `ConcurrentHashMap` быстрее.

Необходимо указать условия эксперимента и результаты, например:

```text
При 4 потоках реализация A выполнила обработку за ...
При 8 потоках время изменилось до ...
При дальнейшем увеличении количества потоков ускорение перестало расти.
```

После этого необходимо объяснить возможную причину наблюдаемого поведения.

---

# 28. Чек-лист

- [ ] Сгенерирован собственный набор тестовых данных.
- [ ] Используется `expected-statistics.txt`.
- [ ] Реализована последовательная версия.
- [ ] Проверяется корректность каждого варианта.
- [ ] Исследован общий `HashMap`.
- [ ] Исследован `synchronizedMap`.
- [ ] Исследован `ConcurrentHashMap` с `get` + `put`.
- [ ] Исследован `ConcurrentHashMap.merge`.
- [ ] Исследован `ConcurrentHashMap + LongAdder`.
- [ ] Реализован вариант с локальными словарями.
- [ ] Проведено исследование для нескольких количеств потоков.
- [ ] Используется одинаковый входной набор для сравниваемых запусков.
- [ ] Выполнены повторные измерения.
- [ ] Рассчитано ускорение.
- [ ] Рассчитана эффективность.
- [ ] Построены графики.
- [ ] Сделаны выводы на основе экспериментальных данных.
