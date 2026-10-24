# Independent validator for the stratified 10k TREC-COVID subset.
# Verifies: exact count, uniqueness, corpus/idmap consistency, qrels containment, and the
# actual sampling COMPOSITION. FAILS (exit 1) if the subset is accidentally all-relevant again.
$ErrorActionPreference = "Stop"
$root = "data\beir\trec-covid"
$o    = "data\beir\trec-covid-10k"
$Target = 10000

# Positive-relevance pool from the ORIGINAL qrels (grade >= 1 for any query).
$positivePool = New-Object System.Collections.Generic.HashSet[string]
$judgedPool   = New-Object System.Collections.Generic.HashSet[string]
$origQrels = Get-Content "$root\qrels\test.tsv"
for ($i = 1; $i -lt $origQrels.Count; $i++) {
    $c = $origQrels[$i] -split "`t"
    if ($c.Count -lt 3) { continue }
    $doc = $c[1].Trim(); $grade = [int]$c[2].Trim()
    [void]$judgedPool.Add($doc)
    if ($grade -ge 1) { [void]$positivePool.Add($doc) }
}

# Subset corpus: ids, uniqueness, JSON validity.
$ids = New-Object System.Collections.Generic.HashSet[string]
$bad = 0
foreach ($line in [System.IO.File]::ReadLines((Resolve-Path "$o\corpus.jsonl"))) {
    if ([string]::IsNullOrWhiteSpace($line)) { continue }
    try {
        $id = [string]($line | ConvertFrom-Json)._id
        if ([string]::IsNullOrEmpty($id)) { $bad++; continue }
        if (-not $ids.Add($id)) { $bad++ }
    } catch { $bad++ }
}
$corpusN = $ids.Count

# idmap coverage.
$idmapIds = New-Object System.Collections.Generic.HashSet[string]
foreach ($line in [System.IO.File]::ReadLines((Resolve-Path "$o\idmap.tsv"))) {
    if ([string]::IsNullOrWhiteSpace($line)) { continue }
    $c = $line -split "`t"
    if ($c.Count -ge 1) { [void]$idmapIds.Add($c[0].Trim()) }
}
$idsMissingIdmap = 0
foreach ($id in $ids) { if (-not $idmapIds.Contains($id)) { $idsMissingIdmap++ } }
$idmapN = $idmapIds.Count

# Every subset id must exist in the ORIGINAL corpus + idmap (no invented ids).
$origIdmap = New-Object System.Collections.Generic.HashSet[string]
foreach ($line in [System.IO.File]::ReadLines((Resolve-Path "$root\idmap.tsv"))) {
    if ([string]::IsNullOrWhiteSpace($line)) { continue }
    $c = $line -split "`t"; if ($c.Count -ge 1) { [void]$origIdmap.Add($c[0].Trim()) }
}
$idsNotInOrigIdmap = 0
foreach ($id in $ids) { if (-not $origIdmap.Contains($id)) { $idsNotInOrigIdmap++ } }

# qrels containment: every subset qrels doc must be in the selected set.
$subQrels = Get-Content "$o\qrels\test.tsv"
$qrelsOutside = 0
$retainedJudgments = 0
for ($i = 1; $i -lt $subQrels.Count; $i++) {
    $c = $subQrels[$i] -split "`t"
    if ($c.Count -lt 2) { continue }
    $retainedJudgments++
    if (-not $ids.Contains($c[1].Trim())) { $qrelsOutside++ }
}

# Composition.
$selPositive = 0; $selNonrel = 0; $selUnjudged = 0
foreach ($id in $ids) {
    if ($positivePool.Contains($id)) { $selPositive++ }
    elseif ($judgedPool.Contains($id)) { $selNonrel++ }
    else { $selUnjudged++ }
}
$selOutside = $selNonrel + $selUnjudged

# ---- Pass/fail ----
$fail = @()
if ($corpusN -ne $Target) { $fail += "count != $Target (got $corpusN)" }
if ($bad -ne 0) { $fail += "duplicate/malformed corpus records: $bad" }
if ($idsMissingIdmap -ne 0) { $fail += "subset ids missing from subset idmap: $idsMissingIdmap" }
if ($idsNotInOrigIdmap -ne 0) { $fail += "subset ids not in ORIGINAL idmap: $idsNotInOrigIdmap" }
if ($qrelsOutside -ne 0) { $fail += "subset qrels docs outside selection: $qrelsOutside" }
# The anti-bias check: must NOT be all-relevant. Require a substantial outside-pool share.
if ($selOutside -le 0) { $fail += "COMPOSITION BIAS: zero outside-positive-pool docs (all relevant)" }
if ($selPositive -eq $Target) { $fail += "COMPOSITION BIAS: 100% positive-relevance (biased subset)" }
# Sanity: outside pool should be the majority given the ~2500/7500 design (allow slack).
if ($selOutside -lt 5000) { $fail += "WARNING-as-FAIL: outside-pool share $selOutside < 5000 (expected ~7500)" }

$r = @()
$r += "=== stratified subset validation ==="
$r += "corpusLines/uniqueIds=$corpusN"
$r += "dupOrMalformed=$bad"
$r += "idmapLines=$idmapN"
$r += "subsetIdsMissingFromSubsetIdmap=$idsMissingIdmap"
$r += "subsetIdsNotInOriginalIdmap=$idsNotInOrigIdmap"
$r += "qrelsRetainedJudgments=$retainedJudgments"
$r += "qrelsDocsOutsideSubset=$qrelsOutside"
$r += "composition.positiveRelevance=$selPositive"
$r += "composition.outsidePositivePool=$selOutside"
$r += "   outside.qrelsNonrelevant(grade0)=$selNonrel"
$r += "   outside.unjudged=$selUnjudged"
if ($fail.Count -eq 0) { $r += "RESULT=PASS" } else { $r += "RESULT=FAIL"; $fail | ForEach-Object { $r += "  - $_" } }
$r | Out-File -Encoding utf8 "tools\_subset_validation.txt"
$r -join "`n"
if ($fail.Count -ne 0) { exit 1 }