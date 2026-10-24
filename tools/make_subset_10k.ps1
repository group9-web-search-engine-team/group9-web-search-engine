# Deterministic, STRATIFIED 10,000-document subset of BEIR TREC-COVID.
#
# Fixes the earlier biased subset (which was 100% qrels-relevant) by sampling two strata:
#   Group A (positive-relevance pool): documents with a qrels grade >= 1 for ANY query.
#   Group B (outside positive pool):   all other corpus documents (qrels grade 0, or unjudged).
#
# Selection is reproducible via a stable hash rank (SHA-256 of SEED + "|" + _id). Within each
# group we take the documents with the smallest hash value -- NOT the first N in file order --
# so there is no file-order or query-specific bias. The SEED is fixed below.
#
# Targets: ~2,500 from Group A and ~7,500 from Group B, totalling exactly 10,000. If a group is
# short, the shortfall is transparently reallocated to the other group and recorded in the
# manifest (we never silently change the total or fabricate documents).
#
# Writes ONLY under data\beir\trec-covid-10k\. Never modifies the originals. Idempotent:
# re-running reproduces identical output.
param(
    [int]$Target = 10000,
    [int]$PositiveTarget = 2500,
    [string]$Seed = "trec-covid-10k-v2"
)
$ErrorActionPreference = "Stop"
$root   = "data\beir\trec-covid"
$outDir = "data\beir\trec-covid-10k"
$qrelsOutDir = Join-Path $outDir "qrels"
New-Item -ItemType Directory -Force -Path $outDir | Out-Null
New-Item -ItemType Directory -Force -Path $qrelsOutDir | Out-Null

$corpusPath = Join-Path $root "corpus.jsonl"
$idmapPath  = Join-Path $root "idmap.tsv"
$qrelsPath  = Join-Path $root "qrels\test.tsv"

# ---- Stable hash rank: first 16 hex chars of SHA-256(seed|id) as an unsigned Int64 ordering key ----
$sha = [System.Security.Cryptography.SHA256]::Create()
function Get-HashKey([string]$id) {
    $bytes = [System.Text.Encoding]::UTF8.GetBytes($Seed + "|" + $id)
    $h = $sha.ComputeHash($bytes)
    # Use first 8 bytes as a hex string key; lexicographic compare on fixed-width hex == numeric.
    $sb = New-Object System.Text.StringBuilder
    for ($i = 0; $i -lt 8; $i++) { [void]$sb.Append($h[$i].ToString("x2")) }
    return $sb.ToString()
}

# ---- 1. Parse qrels (skip header). Positive pool = any doc with grade>=1 for any query. ----
$qrelsLines = Get-Content $qrelsPath
$header = $qrelsLines[0]
$positivePool = New-Object System.Collections.Generic.HashSet[string]
$judgedPool   = New-Object System.Collections.Generic.HashSet[string]   # any judgment (incl grade 0)
$relByQueryFull = @{}   # qid -> count of relevant (for excluded reporting)
for ($i = 1; $i -lt $qrelsLines.Count; $i++) {
    $line = $qrelsLines[$i]
    if ([string]::IsNullOrWhiteSpace($line)) { continue }
    $cols = $line -split "`t"
    if ($cols.Count -lt 3) { $cols = $line -split "\s+" }
    if ($cols.Count -lt 3) { continue }
    $qid = $cols[0].Trim(); $doc = $cols[1].Trim(); $grade = [int]$cols[2].Trim()
    [void]$judgedPool.Add($doc)
    if ($grade -ge 1) {
        [void]$positivePool.Add($doc)
        if (-not $relByQueryFull.ContainsKey($qid)) { $relByQueryFull[$qid] = 0 }
        $relByQueryFull[$qid]++
    }
}

# ---- 2. Stream corpus once: collect valid ids + raw lines, split into groups. ----
$idToLine = @{}
$malformed = 0
$groupA = New-Object System.Collections.Generic.List[string]   # in positive pool AND in corpus
$groupB = New-Object System.Collections.Generic.List[string]   # everything else in corpus
foreach ($line in [System.IO.File]::ReadLines((Resolve-Path $corpusPath))) {
    if ([string]::IsNullOrWhiteSpace($line)) { continue }
    try {
        $obj = $line | ConvertFrom-Json
        $id = [string]$obj._id
        if ([string]::IsNullOrEmpty($id)) { $malformed++; continue }
    } catch { $malformed++; continue }
    if ($idToLine.ContainsKey($id)) { continue }   # dedup
    $idToLine[$id] = $line
    if ($positivePool.Contains($id)) { $groupA.Add($id) } else { $groupB.Add($id) }
}

# ---- 3. Load idmap; a selected id must exist in idmap too. ----
$idmap = @{}
foreach ($line in [System.IO.File]::ReadLines((Resolve-Path $idmapPath))) {
    if ([string]::IsNullOrWhiteSpace($line)) { continue }
    $cols = $line -split "`t"
    if ($cols.Count -lt 2) { continue }
    $idmap[$cols[0].Trim()] = $cols[1].Trim()
}
# Keep only ids present in idmap (consistency guarantee).
$groupA = @($groupA | Where-Object { $idmap.ContainsKey($_) })
$groupB = @($groupB | Where-Object { $idmap.ContainsKey($_) })

# ---- 4. Deterministic hash-rank within each group; take smallest-hash N. ----
$rankedA = $groupA | Sort-Object { Get-HashKey $_ }
$rankedB = $groupB | Sort-Object { Get-HashKey $_ }

# Allocation with transparent reallocation if a group is short.
$posTake = [Math]::Min($PositiveTarget, $rankedA.Count)
$remaining = $Target - $posTake
$negTake = [Math]::Min($remaining, $rankedB.Count)
# If Group B short, backfill from remaining Group A (documented).
$backfillA = 0
if (($posTake + $negTake) -lt $Target) {
    $need = $Target - $posTake - $negTake
    $backfillA = [Math]::Min($need, $rankedA.Count - $posTake)
    $posTake += $backfillA
}
$totalTake = $posTake + $negTake
if ($totalTake -lt $Target) {
    Write-Error "INSUFFICIENT DOCUMENTS: groupA=$($rankedA.Count) groupB=$($rankedB.Count) total available=$($rankedA.Count + $rankedB.Count), need $Target. No files written."
    exit 1
}

$selectedA = @($rankedA | Select-Object -First $posTake)
$selectedB = @($rankedB | Select-Object -First $negTake)

# Final selected set; deterministic output order = hash order within A then B.
$finalOrder = New-Object System.Collections.Generic.List[string]
$finalSet   = New-Object System.Collections.Generic.HashSet[string]
foreach ($id in $selectedA) { if ($finalSet.Add($id)) { $finalOrder.Add($id) } }
foreach ($id in $selectedB) { if ($finalSet.Add($id)) { $finalOrder.Add($id) } }

# ---- 5. Write corpus.jsonl, idmap.tsv ----
$corpusOut = Join-Path $outDir "corpus.jsonl"
$idmapOut  = Join-Path $outDir "idmap.tsv"
$qrelsOut  = Join-Path $qrelsOutDir "test.tsv"
$utf8 = New-Object System.Text.UTF8Encoding($false)

$swC = New-Object System.IO.StreamWriter($corpusOut, $false, $utf8)
$swI = New-Object System.IO.StreamWriter($idmapOut, $false, $utf8)
foreach ($id in $finalOrder) {
    $swC.WriteLine($idToLine[$id])
    $swI.WriteLine("$id`t$($idmap[$id])")
}
$swC.Close(); $swI.Close()

# ---- 6. qrels filtered to selected ids (preserve header, grades, qids). ----
$swQ = New-Object System.IO.StreamWriter($qrelsOut, $false, $utf8)
$swQ.WriteLine($header)
$retainedJudgments = 0
$retainedRelByQuery = @{}
$queriesWithRel = New-Object System.Collections.Generic.HashSet[string]
for ($i = 1; $i -lt $qrelsLines.Count; $i++) {
    $line = $qrelsLines[$i]
    if ([string]::IsNullOrWhiteSpace($line)) { continue }
    $cols = $line -split "`t"
    if ($cols.Count -lt 3) { continue }
    $qid = $cols[0].Trim(); $doc = $cols[1].Trim(); $grade = [int]$cols[2].Trim()
    if ($finalSet.Contains($doc)) {
        $swQ.WriteLine($line)
        $retainedJudgments++
        if ($grade -ge 1) {
            if (-not $retainedRelByQuery.ContainsKey($qid)) { $retainedRelByQuery[$qid] = 0 }
            $retainedRelByQuery[$qid]++
            [void]$queriesWithRel.Add($qid)
        }
    }
}
$swQ.Close()

# ---- 7. Composition + excluded-relevant stats ----
$selectedPositive = 0
foreach ($id in $finalOrder) { if ($positivePool.Contains($id)) { $selectedPositive++ } }
$selectedOutside = $finalOrder.Count - $selectedPositive
$selectedJudgedNonrel = 0
$selectedUnjudged = 0
foreach ($id in $finalOrder) {
    if ($positivePool.Contains($id)) { continue }
    if ($judgedPool.Contains($id)) { $selectedJudgedNonrel++ } else { $selectedUnjudged++ }
}
$excludedRelevant = 0
foreach ($id in $positivePool) { if ($idToLine.ContainsKey($id) -and -not $finalSet.Contains($id)) { $excludedRelevant++ } }

# ---- 8. Manifest ----
$manifest = Join-Path $outDir "subset-manifest.txt"
$ts = (Get-Date).ToString("o")
$m = @()
$m += "BEIR TREC-COVID 10k STRATIFIED subset (diagnostic experiment)"
$m += "generated: $ts"
$m += "PURPOSE: diagnostic experiment on a reduced corpus. NOT directly comparable with"
$m += "         full-corpus benchmark results (coverage and relevance density differ)."
$m += "selection algorithm: stratified, deterministic hash-rank sampling."
$m += "  stable key = SHA-256( seed + '|' + _id ), take smallest-hash N within each stratum."
$m += "  Group A = positive-relevance pool (qrels grade>=1 for any query)."
$m += "  Group B = outside positive pool (qrels grade 0, or no qrels judgment)."
$m += "  NOT selected by qrels membership alone; NOT first-N in file order."
$m += "fixed seed: '$Seed'"
$m += "target total: $Target"
$m += "positive-pool target: $PositiveTarget ; outside-pool target: $($Target - $PositiveTarget)"
$m += "--- available pool sizes (present in corpus AND idmap) ---"
$m += "Group A available: $($rankedA.Count)"
$m += "Group B available: $($rankedB.Count)"
$m += "--- actual selection composition ---"
$m += "selected total: $($finalOrder.Count)"
$m += "selected from Group A (positive-relevance): $selectedPositive"
$m += "  of which backfilled beyond positive target (Group B short): $backfillA"
$m += "selected from Group B (outside positive pool): $selectedOutside"
$m += "    - qrels-nonrelevant (grade 0): $selectedJudgedNonrel"
$m += "    - unjudged (no qrels entry): $selectedUnjudged"
$m += "malformed corpus records skipped: $malformed"
$m += "--- qrels ---"
$m += "retained qrels judgments: $retainedJudgments"
$m += "queries with >=1 retained relevant doc: $($queriesWithRel.Count) of 50"
$m += "relevant docs excluded by subset: $excludedRelevant"
$m += "--- retained relevant per query (qid: count) ---"
foreach ($qid in ($retainedRelByQuery.Keys | Sort-Object { [int]$_ })) {
    $m += "  $qid : $($retainedRelByQuery[$qid])"
}
$m += "--- documents.jsonl note ---"
$m += "The subset changes the selected ID set, so any existing week3-export\documents.jsonl"
$m += "(full corpus) is NOT a subset of this selection. Regenerate a subset documents.jsonl"
$m += "from this corpus.jsonl via CorpusAdapter before indexing/evaluating (not done here)."
$m += "--- validation (see validate_subset_10k.ps1 for independent check) ---"
$m += "exactly target size: $([bool]($finalOrder.Count -eq $Target))"
$m += "no duplicate ids: $([bool]($finalOrder.Count -eq $finalSet.Count))"
$m += "not all-relevant (composition balanced): $([bool]($selectedOutside -gt 0))"
$m | Out-File -Encoding utf8 $manifest

$sha.Dispose()
Write-Output "DONE"
Write-Output ("total=" + $finalOrder.Count + " groupA_selected=" + $selectedPositive + " groupB_selected=" + $selectedOutside + " nonrel=" + $selectedJudgedNonrel + " unjudged=" + $selectedUnjudged + " backfillA=" + $backfillA + " retainedJudgments=" + $retainedJudgments + " queriesWithRel=" + $queriesWithRel.Count + " excludedRelevant=" + $excludedRelevant + " malformed=" + $malformed)