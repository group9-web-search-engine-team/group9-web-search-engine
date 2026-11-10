$ErrorActionPreference = "Stop"
$sub = "data\beir\trec-covid-10k"

# 1. subset corpus doc count + unique ids
$ids = New-Object System.Collections.Generic.HashSet[string]
foreach ($line in [System.IO.File]::ReadLines((Resolve-Path "$sub\corpus.jsonl"))) {
    if ([string]::IsNullOrWhiteSpace($line)) { continue }
    try { $id = [string]($line | ConvertFrom-Json)._id; [void]$ids.Add($id) } catch {}
}

# 2. subset documents.jsonl count (what eval actually loaded as --meta)
$docN = (Get-Content "$sub\documents.jsonl" | Measure-Object -Line).Lines

# 3. subset qrels: judgments, relevant (grade>=1), queries, distinct judged docs
$q = Get-Content "$sub\qrels\test.tsv"
$judg = 0; $rel = 0
$queries = New-Object System.Collections.Generic.HashSet[string]
$judgedDocs = New-Object System.Collections.Generic.HashSet[string]
$relDocs = New-Object System.Collections.Generic.HashSet[string]
for ($i = 1; $i -lt $q.Count; $i++) {
    $c = $q[$i] -split "`t"
    if ($c.Count -lt 3) { continue }
    $qid = $c[0].Trim(); $doc = $c[1].Trim(); $g = [int]$c[2].Trim()
    $judg++; [void]$queries.Add($qid); [void]$judgedDocs.Add($doc)
    if ($g -ge 1) { $rel++; [void]$relDocs.Add($doc) }
}

# 4. every judged doc must be inside the subset corpus (no full-corpus leakage)
$judgedOutsideSubset = 0
foreach ($d in $judgedDocs) { if (-not $ids.Contains($d)) { $judgedOutsideSubset++ } }

# 5. subset idmap count
$idmapN = (Get-Content "$sub\idmap-generated.tsv" -ErrorAction SilentlyContinue | Measure-Object -Line).Lines
if (-not $idmapN) { $idmapN = (Get-Content "$sub\idmap.tsv" | Measure-Object -Line).Lines }

$r = @()
$r += "subset_corpus_unique_ids=$($ids.Count)"
$r += "subset_documents_jsonl_lines=$docN"
$r += "subset_idmap_lines=$idmapN"
$r += "subset_qrels_judgments=$judg"
$r += "subset_qrels_relevant(grade>=1)=$rel"
$r += "subset_qrels_queries=$($queries.Count)"
$r += "subset_qrels_distinct_judged_docs=$($judgedDocs.Count)"
$r += "subset_qrels_distinct_relevant_docs=$($relDocs.Count)"
$r += "judged_docs_OUTSIDE_subset_corpus=$judgedOutsideSubset   (must be 0)"
$r | Out-File -Encoding utf8 "tools\_scope_check.txt"
$r -join "`n"