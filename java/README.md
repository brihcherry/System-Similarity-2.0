## Purpose

The `java/` folder contains the custom reactors consumed by the application from `client/`.

## System Similarity Pipeline

The pipeline computes pairwise similarity scores between systems in an RDF knowledge graph. It runs in two stages: **data sourcing & per-variable scoring** followed by **aggregation & filtering**.

### Stage 1 — `GetSystemSimilarityDataSources`

This reactor queries the RDF engine and produces per-variable similarity scores for every system pair.

#### Inputs

| Parameter | Required | Description |
|-----------|----------|-------------|
| `database` | Yes | RDF engine UUID to query against |
| `systemList` | No | List of system URIs to restrict comparison |
| `systemQuery` | No | Custom SPARQL BINDINGS clause (alternative to `systemList`) |

#### Query Execution

The reactor executes 7 SPARQL query groups against the RDF engine. Each query retrieves a different facet of each system, and a dedicated scoring function converts the raw query results into pairwise similarity scores (0–1 range):

| Variable (Bucket) | Scoring Function | Description |
|--------------------|-----------------|-------------|
| Business_Processes_Supported | Set overlap | Shared business processes / total |
| Activities_Supported | Set overlap | Shared activities / total |
| Data_and_Business_Logic_Supported | CRM-aware matching | Considers CRUD semantics — Read satisfies Create/Modify, Modify satisfies Create |
| Deployment_(Theater/Garrison) | Binary category match | Same deployment category → 1.0, different → 0.0 |
| Transactional_(Yes/No) | Binary category match | Same transactional status → 1.0, different → 0.0 |
| User_Types | Set overlap | Shared personnel types / total |
| User_Interface_Types_(PC/Mobile/etc.) | Set overlap | Shared UI types / total |

#### Post-Processing

1. **Chart transform** — Raw scores (0–1) are scaled to 0–100, self-comparison pairs are excluded, and results are keyed as `"System1-System2"` for heatmap display.
2. **Pruning** — Pairs that exist in *all* 7 variables and whose simple average score is ≤ 50 are removed from every variable. Pairs present in only some variables are left untouched.
3. **Caching** — The processed `paramDataHash` (7 buckets of chart-ready pair scores) and `keyHash` (pair key → system name mapping) are stored in the insight var-store for Stage 2.

### Stage 2 — `ComputeSimilarityScores`

This reactor aggregates the per-variable scores from Stage 1 into a single overall similarity score per system pair.

#### Inputs

| Parameter | Required | Description |
|-----------|----------|-------------|
| `selectedVars` | No | Subset of the 7 variables to include (default: all) |
| `specifiedWeights` | No | Per-variable *minimum score* filters (e.g., `{"Deployment_(Theater/Garrison)": 90}` excludes pairs scoring below 90 on that variable) |

#### Aggregation Algorithm

1. Load `paramDataHash` from the var-store.
2. Sort variables by bucket size (smallest first) for deterministic floating-point accumulation.
3. Use the smallest variable's pair keys as the master set.
4. For each pair key:
   - If the pair is **missing from any** selected variable → skip it.
   - If any variable's score is **below its minimum filter** → skip it.
   - Accumulate: `score += varScore / totalVars` (simple average).
5. Keep the pair only if the final score is **strictly > 50**.
6. Resolve pair keys back to human-readable system names via the `keyHash`.

#### Output

```json
{
  "headers": ["System1", "System2", "Score"],
  "data": [["SystemA", "SystemB", 75.5], ...],
  "variablesUsed": ["Business_Processes_Supported", ...],
  "minimumWeightsUsed": { ... },
  "totalPairsEvaluated": 156,
  "pairsAboveThreshold": 48
}
```

### Pipeline Flow

```
GetSystemSimilarityDataSources(database, systemList?)
  │  Fetch canonical system list from RDF engine
  │  Execute 7 SPARQL queries
  │  Compute raw pairwise scores per variable (0–1)
  │  Scale to 0–100 and shape for charting
  │  Prune pairs with avg ≤ 50 across all variables
  │  Cache paramDataHash + keyHash in var-store
  ▼
ComputeSimilarityScores(selectedVars?, specifiedWeights?)
     Load cached paramDataHash
     Apply variable selection and minimum-score filters
     Compute simple average across selected variables
     Return pairs with score > 50
```