# Запускать из папки laba2. Каждый новый эксперимент использует отдельный каталог.
param([string]$Java = 'java', [string]$Series = (Get-Date -Format 'yyyyMMdd-HHmmss'))
$ErrorActionPreference = 'Stop'
foreach ($corpus in @(@('small',500), @('medium',2000), @('large',8000))) {
    $label = $corpus[0]
    $data = "generated-data/$Series/$label"
    $out = "results/$Series/$label"
    & $Java '-Dstdout.encoding=UTF-8' -cp build/classes ru.student.lab2.TestDataGenerator $data 64 $corpus[1] 500
    if ($LASTEXITCODE -ne 0) { throw "Ошибка генератора: $label" }
    & $Java '-Dstdout.encoding=UTF-8' -Xmx1g -cp build/classes ru.student.lab2.Main "--data=$data" "--out=$out" --warmups=2 --runs=5
    if ($LASTEXITCODE -ne 0) { throw "Ошибка измерения: $label" }
}
