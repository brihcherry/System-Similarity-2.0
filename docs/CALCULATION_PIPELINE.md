# System Similarity Heatmap: Calculation Pipeline

## Overview

This document describes how the System Similarity heatmap score is computed for any given pair of systems in the legacy backend. It traces data from raw SPARQL query results through the `paramDataHash` cache to the final heatmap display value.

The pipeline has three stages, each handled by a different function in the legacy playsheet (`SysSimHeatMapSheet` / `SimilarityHeatMapSheet`):

```
SPARQL Queries (7)       processHashForCharting()         calculateHash()
─────────────────── ──► ──────────────────────────── ──► ─────────────────── ──► Heatmap
Raw system-attribute      Per-variable pairwise scores     Weighted average
mappings                  stored in paramDataHash          across variables
```

---

## Stage 1: Data Collection — `createData()` in `SysSimHeatMapSheet`

**When it runs:** Once, on initial page load only.

`createData()` executes 7 SPARQL queries against the TAP_Core_Data RDF database. Each query retrieves a different category of system attributes:

| Query | Variable Name | What It Returns |
|-------|--------------|-----------------|
| 1 | Data_and_Business_Logic_Supported | System → set of data objects/BLU items |
| 2 | Deployment_(Theater/Garrison) | System → set of deployment locations |
| 3 | Transactional_(Yes/No) | System → "Yes" or "No" |
| 4 | Business_Processes_Supported | System → set of business processes |
| 5 | Activities_Supported | System → set of activities |
| 6 | User_Types | System → set of user types |
| 7 | User_Interface_Types_(PC/Mobile/etc.) | System → set of UI types (PC, Mobile, etc.) |

**Output:** 7 raw result sets, each mapping systems to their attributes for that variable.

---

## Stage 2: Pairwise Scoring — `processHashForCharting()` in `SimilarityHeatMapSheet`

**When it runs:** Once per query result, called by `createData()` immediately after each query completes. Results are stored in `paramDataHash` and never recomputed during the session.

**What it does:** For each variable, takes the raw system-to-attribute mapping and computes a **pairwise similarity score** for every possible pair of systems that both have data for that variable.

### Similarity Metric

For set-based variables (most of them), the score is a **set overlap percentage**:

$$\text{Score} = \frac{|\text{Attributes}(A) \cap \text{Attributes}(B)|}{\max(|\text{Attributes}(A)|, |\text{Attributes}(B)|)} \times 100$$

For binary variables (Transactional), the score is an exact-match check:
- Both "Yes" → 100
- Both "No" → 100  
- Mismatch → 0

### Output: `paramDataHash`

Type: `Hashtable<String, Hashtable<String, Hashtable<String, Object>>>`

Structure:
```
paramDataHash = {
    "Variable_Name": {
        "SystemA-SystemB": { "Score": <number 0–100> },
        "SystemA-SystemC": { "Score": <number 0–100> },
        ...
    },
    ...  (7 variables total)
}
```

Each system pair key is formed by joining the two system names with a hyphen (e.g., `"AFWEBHA-ACS_ECG"`).

### Worked Example: AFWEBHA-ACS_ECG in `paramDataHash`

This pair appears 7 times — once under each variable:

| Variable | Score | Interpretation |
|----------|-------|----------------|
| Deployment_(Theater/Garrison) | **100** | Both systems deploy to the same locations (complete overlap) |
| Business_Processes_Supported | **100** | Both systems support the exact same set of business processes |
| User_Types | **20** | 20% overlap in the user types they serve |
| Data_and_Business_Logic_Supported | **42.11** | ~42% overlap in the data objects / BLU items they handle |
| User_Interface_Types_(PC/Mobile/etc.) | **100** | Both systems support the same UI platforms |
| Activities_Supported | **10.53** | ~11% overlap in the activities they perform |
| Transactional_(Yes/No) | **100** | Both systems have the same transactional classification |

Fractional scores like 42.11 and 10.53 reflect partial set overlap. For example, if AFWEBHA supports 19 activities and ACS_ECG supports 8, and they share 2, the score would be:

$$\frac{2}{\max(19, 8)} \times 100 = \frac{2}{19} \times 100 \approx 10.53$$

---

## Stage 3: Heatmap Aggregation — `calculateHash()` in `SimilarityHeatMapSheet`

**When it runs:** On initial load (with all variables, equal weights) and on every refresh (with user-selected variables and weights).

**What it does:** For each system pair, computes a **weighted average** of the per-variable scores from `paramDataHash`.

### Algorithm

```
Input:
  - paramDataHash          (from Stage 2, cached)
  - selectedVars[]         (list of variable names the user has checked)
  - specifiedWeights{}     (map of variable name → numeric weight)

For each system pair across all selected variables:
    1. Look up the pair's Score under each selected variable in paramDataHash
    2. If the pair is MISSING from ANY selected variable → skip entirely
    3. Compute weighted average:

        finalScore = Σ(weight_i × score_i) / Σ(weight_i)

    4. Store result
```

### Worked Example: AFWEBHA-ACS_ECG on Initial Load

On initial load, all 7 variables are selected with equal weight (1.0 each):

| Variable | Weight | Score | Weighted Score |
|----------|--------|-------|----------------|
| Deployment_(Theater/Garrison) | 1.0 | 100.00 | 100.00 |
| Business_Processes_Supported | 1.0 | 100.00 | 100.00 |
| User_Types | 1.0 | 20.00 | 20.00 |
| Data_and_Business_Logic_Supported | 1.0 | 42.11 | 42.11 |
| User_Interface_Types_(PC/Mobile/etc.) | 1.0 | 100.00 | 100.00 |
| Activities_Supported | 1.0 | 10.53 | 10.53 |
| Transactional_(Yes/No) | 1.0 | 100.00 | 100.00 |
| **Totals** | **7.0** | | **472.63** |

$$\text{Heatmap Score} = \frac{472.63}{7.0} \approx \textbf{67.5}$$

This matches the ~68 displayed on the heatmap (minor rounding differences occur during display).

### Worked Example: Refresh With Custom Weights

If the user selects only Business_Processes (weight=2) and Activities (weight=1):

| Variable | Weight | Score | Weighted Score |
|----------|--------|-------|----------------|
| Business_Processes_Supported | 2.0 | 100.00 | 200.00 |
| Activities_Supported | 1.0 | 10.53 | 10.53 |
| **Totals** | **3.0** | | **210.53** |

$$\text{Heatmap Score} = \frac{210.53}{3.0} \approx \textbf{70.2}$$

---

## Stage 4: Filtering — `flattenData()` in `SimilarityHeatMapSheet`

**When it runs:** Immediately after `calculateHash()`.

**What it does:**
1. Filters out all pairs with a heatmap score **below 50**
2. Splits each pair key (e.g., `"AFWEBHA-ACS_ECG"`) into System1 and System2
3. Flattens to tabular rows: `[System1, System2, Score]`

**Output:** The `data` array returned to the frontend:
```json
{
  "headers": ["System1", "System2", "Score"],
  "data": [
    ["AFWEBHA", "ACS_ECG", 67.52],
    ...
  ]
}
```

Pairs scoring below 50 never appear in the heatmap.

---

## Legacy Code Map

| Stage | Class | Method | Stateful? | Notes |
|-------|-------|--------|-----------|-------|
| 1 — Data Collection | `SysSimHeatMapSheet` | `createData()` | Yes — populates `paramDataHash` | Runs 7 SPARQL queries; called once per session |
| 2 — Pairwise Scoring | `SimilarityHeatMapSheet` | `processHashForCharting()` | Yes — writes to `paramDataHash` | Called by `createData()` for each query result |
| 3 — Aggregation | `SimilarityHeatMapSheet` | `calculateHash(selectedVars, specifiedWeights)` | No — reads `paramDataHash`, produces new output | Called on initial load and every refresh |
| 4 — Filtering | `SimilarityHeatMapSheet` | `flattenData()` | No — pure transformation | Threshold hardcoded at 50 |
| Refresh entry point | `SysSimHeatMapSheet` | `refreshSysSimData(Map payload)` | No — orchestrates Stages 3+4 | Skips Stages 1+2 entirely |

### Class Hierarchy

```
SimilarityHeatMapSheet (base)
├── paramDataHash field (Hashtable)
├── processHashForCharting()    ← Stage 2
├── calculateHash()             ← Stage 3
└── flattenData()               ← Stage 4

SysSimHeatMapSheet extends SimilarityHeatMapSheet
├── createData()                ← Stage 1 (7 SPARQL queries + calls processHashForCharting)
└── refreshSysSimData()         ← Refresh entry point (Stages 3+4 only)
```

---

## Migration Implications

To fully replace the legacy playsheet:

1. **Stage 1 (SPARQL queries):** Port the 7 queries into a new reactor. The queries are embedded in `SysSimHeatMapSheet.createData()`. Each returns system-to-attribute mappings in RDF.

2. **Stage 2 (`processHashForCharting`):** This is the core logic to reverse-engineer. It converts raw query results into the pairwise score format. The set-overlap metric (likely Jaccard or max-denominator) must be confirmed by examining the decompiled class or by comparing known attribute sets against observed scores.

3. **Stage 3 (`calculateHash`):** Straightforward weighted-average arithmetic. Can be implemented directly from the algorithm described above.

4. **Stage 4 (`flattenData`):** Trivial filter + flatten. The threshold (50) should be made configurable.

5. **Caching strategy:** In the legacy system, `paramDataHash` lives on the playsheet object in server memory. In the migrated system, the architectural plan calls for the frontend to cache the Stage 2 output and pass it back on refresh calls, making the backend stateless.
