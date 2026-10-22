"""Строит графики и выводы из реальных CSV: python tools/report.py."""
from pathlib import Path
import csv
import sys
import argparse

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / '.tools' / 'python'))
import matplotlib
matplotlib.use('Agg')
import matplotlib.pyplot as plt

SAFE = ('concurrent-merge', 'long-adder', 'local-maps')
LABELS = {'concurrent-merge': 'ConcurrentHashMap.merge',
          'long-adder': 'ConcurrentHashMap + LongAdder', 'local-maps': 'Локальные словари'}
plt.rcParams.update({'font.family': 'DejaVu Sans', 'font.size': 10})


def metadata(path):
    return dict(line.split('=', 1) for line in path.read_text(encoding='utf-8').splitlines() if '=' in line)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--results', type=Path, default=ROOT / 'results')
    results = parser.parse_args().results
    sections = ['# Выводы по лабораторной №2', '',
                'Все приведённые числа получены запуском программы. Для каждой конфигурации выполнены '
                '2 прогрева и 5 измерений. Время — медиана; ускорение рассчитано относительно '
                'последовательной версии того же корпуса. Проверены все основные и прогревочные запуски.', '',
                'На графиках показаны только алгоритмически корректные параллельные варианты. '
                'Последовательная версия обозначена горизонтальной линией. '
                'Ошибочные реализации сохранены в таблицах с фактической проверкой.', '']
    datasets = []
    for name in ('small', 'medium', 'large'):
        folder = results / name
        with (folder / 'measurements.csv').open(encoding='utf-8', newline='') as f:
            rows = list(csv.DictReader(f))
        meta = metadata(folder / 'environment.txt')
        datasets.append((name, rows, meta))
        baseline = next(r for r in rows if r['variant'] == 'sequential')
        for column, title, ylabel, filename in (
            ('time_seconds', 'Время обработки', 'Медианное время, с', 'time.png'),
            ('speedup', 'Ускорение относительно последовательной версии', 'Ускорение, раз', 'speedup.png'),
            ('efficiency_percent', 'Эффективность параллельной обработки', 'Эффективность, %', 'efficiency.png')):
            fig, ax = plt.subplots(figsize=(9, 5.4), layout='constrained')
            for variant in SAFE:
                points = [r for r in rows if r['variant'] == variant and r['correct'] == 'true']
                ax.plot([int(r['threads']) for r in points], [float(r[column]) for r in points],
                        marker='o', linewidth=2, label=LABELS[variant])
            ax.set_xscale('log', base=2)
            ax.set_xticks([1, 2, 4, 8, 16, 32, 64], labels=['1', '2', '4', '8', '16', '32', '64'])
            reference = float(baseline[column]) if column != 'efficiency_percent' else 100
            ax.axhline(reference, color='#777777', linestyle='--', linewidth=1,
                       label='Последовательное время' if column == 'time_seconds' else
                             ('Без ускорения: S = 1' if column == 'speedup' else 'Линейное ускорение: E = 100%'))
            ax.set(title=f'{title}\nКорпус {name}: {int(meta["expectedWords"]):,} слов, {meta["files"]} файла',
                   xlabel='Количество потоков', ylabel=ylabel)
            ax.grid(alpha=.25)
            ax.legend(fontsize=9)
            ax.set_ylim(bottom=0)
            fig.savefig(folder / filename, dpi=160)
            plt.close(fig)
        safe_rows = [r for r in rows if r['variant'] in SAFE and r['correct'] == 'true']
        best = min(safe_rows, key=lambda r: float(r['time_seconds']))
        sections += [f'## Корпус {name}', '',
                     f'- Файлов: {meta["files"]}; слов: {meta["expectedWords"]}; словарь: 500 слов.',
                     f'- JVM: {meta["java"]}; ОС: {meta["os"]}; доступно логических процессоров: {meta["processors"]}.',
                     f'- Последовательная версия: {float(baseline["time_seconds"]):.6f} с.',
                     f'- Минимум среди корректных параллельных вариантов: **{best["variant"]}, '
                     f'{best["threads"]} потоков, {float(best["time_seconds"]):.6f} с**, '
                     f'ускорение {float(best["speedup"]):.3f}, эффективность {float(best["efficiency_percent"]):.2f}%.', '']
        for variant in SAFE:
            points = [r for r in safe_rows if r['variant'] == variant]
            fastest = min(points, key=lambda r: float(r['time_seconds']))
            last = max(points, key=lambda r: int(r['threads']))
            sections += [f'- {variant}: лучшее время {float(fastest["time_seconds"]):.6f} с при '
                         f'{fastest["threads"]} потоках; при 64 потоках — {float(last["time_seconds"]):.6f} с.']
        sections += ['', '### Проверка наивных реализаций', '']
        for variant in ('hashmap', 'synchronized-map', 'concurrent-get-put'):
            points = [r for r in rows if r['variant'] == variant and int(r['threads']) > 1]
            good = sum(int(r['correct_runs']) for r in points)
            total = sum(int(r['runs']) for r in points)
            sections += [f'- {variant}: совпало с эталоном {good} из {total} многопоточных измерений.']
        sections += ['', f'[Таблица]({name}/timings.md) · [Все замеры]({name}/samples.csv)', '',
                     f'![Время]({name}/time.png)', f'![Ускорение]({name}/speedup.png)',
                     f'![Эффективность]({name}/efficiency.png)', '']
    sections += ['## Влияние размера задачи', '',
                 '| Вариант | Потоков | Малый: ускорение | Средний: ускорение | Большой: ускорение |',
                 '|---|---:|---:|---:|---:|']
    for variant in SAFE:
        for p in (4, 8):
            values = [next(r for r in rows if r['variant'] == variant and int(r['threads']) == p)
                      for _, rows, _ in datasets]
            sections += [f'| {variant} | {p} | ' + ' | '.join(f'{float(r["speedup"]):.3f}' for r in values) + ' |']
    sections += ['', 'При увеличении корпуса растёт объём полезной работы на один запуск потоков. '
                 'Доля расходов на создание пула, распределение файлов и объединение уменьшается. '
                 'Однако улучшение не обязано быть монотонным: остаются ограничения диска, CPU, '
                 'памяти и конкуренция за частые слова. В этой серии при изменении размера генератор '
                 'создавал новое распределение; поэтому различия обусловлены и объёмом, и составом корпуса.', '',
                 '## Объяснение результатов и ограничения', '',
                 'HashMap не предназначен для конкурентных изменений. synchronizedMap защищает отдельные '
                 'вызовы get и put, но между ними другой поток может обновить тот же счётчик. '
                 'ConcurrentHashMap с get/put имеет ту же проблему составной операции. '
                 'Например, два потока читают 10 и оба записывают 11: одно обновление потеряно. '
                 'Совпадение отдельных запусков synchronizedMap с эталоном не делает алгоритм корректным.', '',
                 'merge атомарно объединяет значение одного ключа. В отличие от единой блокировки '
                 'synchronizedMap, ConcurrentHashMap позволяет конкурентную работу с разными участками '
                 'структуры, хотя обновления одного популярного ключа могут конкурировать. '
                 'LongAdder распределяет обновления счётчика и может уменьшать конкуренцию; sum вызывается '
                 'после завершения всех работников. Локальные словари полностью убирают общий изменяемый '
                 'словарь из основного цикла, но требуют памяти и финального объединения.', '',
                 'Рост числа потоков ограничен числом доступных ядер, пропускной способностью ввода, '
                 'затратами на создание/переключение потоков и работой планировщика. Файлов 64: '
                 'каждый из 64 работников получает файл; при меньшем числе потоков каждый читает '
                 'несколько файлов. Объём данных внутри одной серии не меняется.', '',
                 'Это учебный end-to-end замер с чтением файлов. Дисковый кэш ОС не очищался. '
                 'Варианты выполнялись в фиксированном порядке в одной JVM; JIT и сборка мусора '
                 'могут влиять на сравнение даже после двух прогревов. Поэтому ускорение выше числа '
                 'потоков или E > 100% нельзя считать доказательством сверхлинейного масштабирования. '
                 'Фоновая нагрузка не контролировалась полностью; близкие времена не доказывают '
                 'устойчивого преимущества одного алгоритма.', '',
                 'Ускорения ошибочных алгоритмов приведены только для полноты таблицы: '
                 'они не характеризуют скорость корректного решения.', '']
    (results / 'conclusions.md').write_text('\n'.join(sections), encoding='utf-8')
    print('Готово: 9 графиков и results/conclusions.md')


if __name__ == '__main__':
    main()
