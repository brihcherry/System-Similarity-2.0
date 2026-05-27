# System Similarity — Developer Guide

This document covers the SPARQL queries, scoring algorithms, data model, Pixel API, and internal logic for the Java reactors powering this application. All data access is pure SPARQL against the `TAP_Core_Data` RDF engine. The two-reactor pipeline separates expensive data fetching (Stage 1) from flexible score aggregation (Stage 2), caching Stage 1 results in the SEMOSS insight var-store so Stage 2 can be re-run cheaply on Refresh.

---

## Reactor Summaries

| Reactor | Pixel Command | Purpose |
|---|---|---|
| `GetSystemSimilarityDataSourcesReactor` | `GetSystemSimilarityDataSources` | Runs 7 SPARQL queries to fetch the canonical system list and per-variable pairwise raw scores for all 6 similarity categories. Stores results in the insight var-store for Stage 2 to consume. |
| `ComputeSimilarityScoresReactor` | `ComputeSimilarityScores` | Reads cached var-store data from Stage 1, aggregates per-variable scores into a weighted average, applies minimum score thresholds, and returns the final scored pair table to the frontend. |

---

## Project Structure

```
java/
└── src/
    ├── domain/
    │   └── base/
    │       ├── ErrorCode.java          HTTP-style error code enum
    │       └── ProjectException.java   Unchecked exception with error map serialization
    ├── reactors/
    │   ├── AbstractProjectReactor.java          Base class for all project reactors
    │   ├── systemSimilarity/
    │   │   ├── GetSystemSimilarityDataSourcesReactor.java   Stage 1 — SPARQL data fetch
    │   │   └── ComputeSimilarityScoresReactor.java          Stage 2 — score aggregation
    │   └── utils/
    │       ├── QueryExecutor.java          SPARQL SELECT wrapper
    │       ├── SimilarityFunctions.java    Pairwise scoring algorithms
    │       └── SimilarityChartingUtils.java  Score → chart-ready cell transforms
    └── util/
        └── ProjectProperties.java          Config singleton (currently skeleton)
```

---

## Two-Stage Pipeline

The scoring pipeline is split into two Pixel calls that the frontend makes sequentially:

```
Stage 1: GetSystemSimilarityDataSources(database=[...], [systemList=[...]])
  → Runs 7 SPARQL queries
  → Computes pairwise raw scores for 6 categories via SimilarityFunctions
  → Transforms raw scores via SimilarityChartingUtils.processHashForCharting
  → Caches paramDataHash, keyHash, systemLabelMap, allSystems, rawScores, dbsMode
    into insight var-store
  → Returns paramDataHash + systemLabelMap to frontend (for preview/debug use)

Stage 2: ComputeSimilarityScores([selectedVars=[...]], [specifiedWeights={...}], [minimumScore=[N]])
  → Reads cached data from var-store (no SPARQL)
  → Filters to selectedVars (default: all 6)
  → Applies per-variable minimum score filters (specifiedWeights)
  → Computes simple average across selected variables
  → Applies global minimumScore threshold
  → Returns headers + data rows + metadata to frontend
```

Stage 2 is cheap — it is re-run on every Refresh Heatmap click without re-querying the RDF engine.

---

## Reactor: `GetSystemSimilarityDataSourcesReactor`

**Package:** `reactors.systemSimilarity`  
**Pixel command:** `GetSystemSimilarityDataSources`

### Parameters

| Key | Type | Required | Default | Description |
|---|---|---|---|---|
| `database` | String (UUID) | Yes | `133db94b-4371-4763-bff9-edf7e5ed021b` | UUID of the RDF engine to query |
| `systemList` | `List<String>` | No | — | System URIs or plain display labels to filter the comparison universe |
| `systemQuery` | String | No | — | Raw SPARQL BINDINGS clause; used if `systemList` is absent |

`systemList` and `systemQuery` are mutually exclusive — if both are provided, `systemQuery` is ignored.

### System Filter Modes

**Mode 1 — All systems** (no `systemList`): queries the full system universe with no SPARQL filter.

**Mode 2 — URI list** (`systemList` contains `http://` or `https://` URIs): builds `BINDINGS ?System {(<uri1>)...}` and appends it to every SPARQL query.

**Mode 3 — Label list / DBS mode** (`systemList` contains plain names like `"MHS_GENESIS"`): queries all systems (no SPARQL filter), then post-filters `allSystems` by label (case-insensitive). Systems in the list with no RDF data get a synthetic URI (`http://semoss.org/temp/system/{label}`) so they appear as empty rows/columns on the heatmap. Sets `VARSTORE_DBS_MODE = true`.

### SPARQL Queries (7 total)

**Query 0 — Canonical System List** (used to build the matrix axes):

```sparql
SELECT DISTINCT ?System WHERE {
  {?System <http://www.w3.org/1999/02/22-rdf-syntax-ns#type>
           <http://semoss.org/ontologies/Concept/System>}
  {?System ?UsedBy ?SystemUser}
}
[+ optional BINDINGS ?System {(<uri1>)...}]
```

**Query 1 — Business Processes Supported** → bucket `Business_Processes_Supported`  
Scoring: set-overlap

```sparql
SELECT DISTINCT ?System ?BusinessProcess WHERE {
  {?System rdf:type <http://semoss.org/ontologies/Concept/System>}
  {?BusinessProcess rdf:type <http://semoss.org/ontologies/Concept/BusinessProcess>}
  {?System <http://semoss.org/ontologies/Relation/Supports> ?BusinessProcess}
  {?System ?UsedBy ?SystemUser}
}
[+ BINDINGS]
```

**Query 2 — Activities Supported** → bucket `Activities_Supported`  
Scoring: set-overlap

```sparql
SELECT DISTINCT ?System ?Activity WHERE {
  {?System rdf:type <http://semoss.org/ontologies/Concept/System>}
  {?Activity rdf:type <http://semoss.org/ontologies/Concept/Activity>}
  {?System <http://semoss.org/ontologies/Relation/Supports> ?Activity}
  {?System ?UsedBy ?SystemUser}
}
[+ BINDINGS]
```

**Query 3 — Data Subject Area** → bucket `Data_Subject_Area`  
Scoring: set-overlap

```sparql
SELECT DISTINCT ?System ?Data WHERE {
  {?System rdf:type <http://semoss.org/ontologies/Concept/System>}
  {?Data rdf:type <http://semoss.org/ontologies/Concept/DataObject>}
  {?Provide rdfs:subPropertyOf <http://semoss.org/ontologies/Relation/Provide>}
  {?System ?Provide ?Data}
  {?System ?UsedBy ?SystemUser}
}
[+ BINDINGS]
```

**Query 4 — Interfaces** → bucket `Interfaces`  
Scoring: set-overlap. Captures both provider and consumer directions.

```sparql
SELECT DISTINCT ?System ?SystemInterface WHERE {
  {?System rdf:type <http://semoss.org/ontologies/Concept/System>}
  {?SystemInterface rdf:type <http://semoss.org/ontologies/Concept/SystemInterface>}
  { {?System <http://semoss.org/ontologies/Relation/Provide> ?SystemInterface}
    UNION
    {?SystemInterface <http://semoss.org/ontologies/Relation/Consume> ?System} }
  {?System ?UsedBy ?SystemUser}
}
[+ BINDINGS]
```

**Query 5 — Environment** → bucket `Environment`  
Scoring: binary category match (Theater / Garrison / Both)

```sparql
SELECT DISTINCT ?System ?Theater WHERE {
  {?System rdf:type <http://semoss.org/ontologies/Concept/System>}
  {?System <http://semoss.org/ontologies/Relation/Contains/GarrisonTheater> ?Theater}
  {?System ?UsedBy ?SystemUser}
}
[+ BINDINGS]
```

**Query 6 — User Types** → bucket `User_Types`  
Scoring: set-overlap

```sparql
SELECT DISTINCT ?System ?Personnel WHERE {
  {?System rdf:type <http://semoss.org/ontologies/Concept/System>}
  {?Personnel rdf:type <http://semoss.org/ontologies/Concept/Personnel>}
  {?System <http://semoss.org/ontologies/Relation/UsedBy> ?Personnel}
  {?System ?UsedBy ?SystemUser}
}
[+ BINDINGS]
```

### Var-Store Keys Written

| Var-store key | Java constant | Type | Content |
|---|---|---|---|
| `SYS_SIM_PARAM_DATA_HASH` | `VARSTORE_PARAM_DATA_HASH` | `MAP` | `Map<bucket, Map<pairKey, Map<"Score", 0–100>>>` |
| `SYS_SIM_KEY_HASH` | `VARSTORE_KEY_HASH` | `MAP` | `Map<pairKey, Map<"System1"/"System2", label>>` |
| `SYS_SIM_SYSTEM_LABEL_MAP` | `VARSTORE_SYSTEM_LABEL_MAP` | `MAP` | `Map<URI, displayLabel>` |
| `SYS_SIM_ALL_SYSTEMS` | `VARSTORE_ALL_SYSTEMS` | `MAP` | `List<String>` of canonical system URIs |
| `SYS_SIM_RAW_SCORES` | `VARSTORE_RAW_SCORES` | `MAP` | `Map<bucket, Map<sysA_URI, Map<sysB_URI, 0–1>>>` |
| `SYS_SIM_DBS_MODE` | `VARSTORE_DBS_MODE` | `BOOLEAN` | `true` if plain labels were passed |

### Return Value

`NounMetadata(Map<String, Object>, PixelDataType.MAP)`

```json
{
  "paramDataHash": {
    "Business_Processes_Supported": { "SYSA-SYSB": { "Score": 75.0 }, ... },
    "Activities_Supported":  { ... },
    "Data_Subject_Area":     { ... },
    "Interfaces":            { ... },
    "Environment":           { ... },
    "User_Types":            { ... }
  },
  "systemLabelMap": {
    "http://semoss.org/ontologies/.../SystemA": "SYSA"
  }
}
```

### Frontend Pixel Calls

```js
// All systems (default)
actions.run('GetSystemSimilarityDataSources(database=["133db94b-4371-4763-bff9-edf7e5ed021b"]);')

// DBS-only mode (plain system name labels)
actions.run('GetSystemSimilarityDataSources(database=["133db94b-..."], systemList=["MHS_GENESIS","AHLTA",...]);')
```

---

## Reactor: `ComputeSimilarityScoresReactor`

**Package:** `reactors.systemSimilarity`  
**Pixel command:** `ComputeSimilarityScores`

`GetSystemSimilarityDataSources` must be called first. This reactor reads exclusively from the insight var-store — it runs no SPARQL queries.

### Parameters

| Key | Type | Required | Default | Description |
|---|---|---|---|---|
| `selectedVars` | `List<String>` | No | All 6 buckets | Names of the scoring variables to include in aggregation |
| `specifiedWeights` | `Map<String, Object>` | No | null | Per-variable minimum score filters. Pairs scoring below the specified value in that variable are excluded entirely. Despite the name, these are **minimum filters**, not multiplier weights. |
| `minimumScore` | double | No | `0.0` | Global minimum average score threshold; pairs below this are excluded. Only applied in non-DBS mode. |

### Aggregation Algorithm

```
1. Parse selectedVars (default: all keys of paramDataHash)
2. Parse minimumWeights map (varName → minScore threshold)
3. Parse minimumScore global threshold
4. Load paramDataHash, keyHash, dbsMode from var-store

5. Sort selectedVars by bucket size ascending, then alphabetically as tie-break
   → ensures the smallest bucket is used as the set of masterKeys

6. masterKeys = keys of the smallest selected bucket

7. For each pairKey in masterKeys:
   a. score = 0.0; storeCell = true
   b. For each var in orderedVars:
      - varScore = paramDataHash[var][pairKey]["Score"]   (0–100 scale)
      - if pairKey absent in this var's bucket → exclude pair (break)
      - if minimumWeights[var] exists && varScore < that threshold → exclude pair (break)
      - score += varScore / totalVars
   c. totalPairsEvaluated++
   d. if !dbsMode && score < minimumScore → skip (don't emit)
   e. pairsAboveThreshold++
   f. Resolve pairKey → [System1, System2] via keyHash
   g. Emit row: [System1, System2, score, Map<varName, varScore>]
```

The score formula is a **simple equal-weight mean** across selected variables:

$$\text{score} = \frac{1}{N} \sum_{i=1}^{N} \text{varScore}_i \quad \text{(each varScore is 0–100)}$$

> **Note on `specifiedWeights`:** Despite the name, these values behave as per-variable **minimum score thresholds**, not multiplier weights. A pair is excluded if any included variable's score falls below its corresponding threshold.

### Return Value

`NounMetadata(Map<String, Object>, PixelDataType.MAP, PixelOperationType.OPERATION)`

```json
{
  "headers": ["System1", "System2", "Score"],
  "data": [
    ["SYSA", "SYSB", 75.5, { "Business_Processes_Supported": 80.0, "User_Types": 71.0 }],
    ["SYSA", "SYSC", 62.0, { "Business_Processes_Supported": 60.0, "User_Types": 64.0 }]
  ],
  "variablesUsed": ["Business_Processes_Supported", "Activities_Supported"],
  "minimumWeightsUsed": { "Environment": 90.0 },
  "totalPairsEvaluated": 156,
  "pairsAboveThreshold": 48,
  "allSystems": ["http://semoss.org/.../SystemA", "http://semoss.org/.../SystemB"],
  "systemLabelMap": { "http://semoss.org/.../SystemA": "SYSA" }
}
```

Each `data` row is `Object[]`: `[String system1, String system2, double score, Map<String,Double> varScores]`.

### Frontend Pixel Calls

```js
// Initial load (default all variables, no weights)
actions.run('ComputeSimilarityScores(minimumScore=[50]);')

// Refresh with selected variables and minimum score
actions.run('ComputeSimilarityScores(selectedVars=["Environment","User_Types"], minimumScore=[60]);')

// Refresh with per-variable minimum filters
actions.run('ComputeSimilarityScores(selectedVars=["Environment","Interfaces"], specifiedWeights={"Environment": 80}, minimumScore=[50]);')
```

---

## Utility Classes

### `SimilarityFunctions`

**Package:** `reactors.utils`

Implements the two pairwise scoring algorithms used by `GetSystemSimilarityDataSourcesReactor`. Maintains mutable class-level state (last SPARQL result rows, comparison object list) — **not thread-safe**.

#### `compareObjectParameterScore(String dbName, String query, String option)`

**Set-Overlap Similarity** — used by: Business Processes, Activities, Data Subject Area, Interfaces, User Types.

1. Executes SPARQL via `QueryExecutor`, groups rows by `?System` into a per-system element list.
2. For each pair `(systemA, systemB)`: score = `|elementsA ∩ elementsB| / |elementsA|`
3. Returns `Map<systemA_URI, Map<systemB_URI, score_0_to_1>>`

`option` parameter: `"Value"` → fractional score (used in pipeline); `"Count"` → raw match count.

#### `stringCompareBinaryResultGetter(String dbName, String query, String valueCheckA, String valueCheckB, String doubleOverlapCheck)`

**Binary Category Match** — used by: Environment (called with `"Theater"`, `"Garrison"`, `"Both"`).

Score = 1.0 if:
- Both systems are Theater–Theater, or Garrison–Garrison
- One or both systems are "Both" (matches either Theater or Garrison)
- Self-comparison

Score = 0.0 otherwise. Returns `Map<systemA, Map<systemB, 0.0_or_1.0>>`.

#### Legacy Methods (not active in current pipeline)

| Method | Description |
|---|---|
| `makeComparisonWithCRM(...)` | CRM-aware scoring (C/R/M compatibility rules); retained for legacy compatibility |
| `getDataBLUDataSet(...)` | Combined Data + BLU scoring path using CRM |
| `getDataSet(...)` | Data-only CRM variant |

---

### `SimilarityChartingUtils`

**Package:** `reactors.utils`  
Final utility class (no instantiation).

#### `processHashForCharting(dataHash, keyHash, typeX, typeY, systemLabelMap)`

Transforms raw pairwise scores into chart-ready cells:
- Input: `Map<sysA_URI, Map<sysB_URI, score_0_to_1>>`
- Translates URIs to display labels via `toSystemLabel()`
- Skips self-comparison pairs
- Multiplies score by 100.0 (converts 0–1 → 0–100)
- Output key format: `"labelA-labelB"` (hyphen-delimited)
- Populates `keyHash` (passed by reference): `{ "System1": labelA, "System2": labelB }` per pair
- Output: `Map<"labelA-labelB", { "Score": double_0_to_100 }>`

#### `buildSystemLabelMap(List<String> systemUris)`

Builds a collision-aware URI → label map. Extracts the last path segment after `/` or `#`, URL-decodes it. Collision resolution: `token`, `token_2`, `token_3`, etc. Both raw and normalized URI forms are inserted as map keys.

#### `toSystemLabel(String rawSystemValue, Map<String, String> systemLabelMap)`

Resolution order:
1. Exact map lookup
2. Normalized URI lookup (strips `<`, `>`, `"`)
3. Full map scan (normalized comparison)
4. Fallback: `deriveSystemAcronym()` (last path segment, no map needed)

---

### `QueryExecutor`

**Package:** `reactors.utils`

Thin wrapper around SEMOSS's `WrapperManager.getRawWrapper()`. Resolves engine aliases via `MasterDatabaseUtility.testDatabaseIdIfAlias()` and retrieves the engine via `Utility.getDatabase()`. Each result row is stored in a `TreeMap<String, String>` for deterministic alphabetical key ordering. Prefers `statement.getRawValues()`, falls back to `statement.getValues()`. Closes the wrapper in `finally`.

```java
// Construction — resolves engine in constructor; throws IllegalArgumentException if unresolvable
new QueryExecutor(String engineId)

// Query execution — throws RuntimeException on SPARQL failure
List<Map<String, String>> executeSelect(String query)

// Accessors
IDatabaseEngine getEngine()
String getEngineId()
```

---

### `ProjectProperties`

**Package:** `util`  
Singleton. Loads `{projectAssetsFolder}/java/project.properties` on first access via `getInstance(String projectId)`. Currently a skeleton — no property fields are extracted. `getInstance()` (no-arg) throws `ProjectException(INTERNAL_SERVER_ERROR)` if not yet initialized. An `IOException` during load sets `INSTANCE = null` and logs a warning without throwing.

---

## Error Handling

### `ProjectException` and `ErrorCode`

`ProjectException` extends `RuntimeException` and carries an `ErrorCode` enum value:

| Code | HTTP | Default message |
|---|---|---|
| `BAD_REQUEST` | 400 | Invalid request |
| `FORBIDDEN` | 403 | User is unauthorized to perform this operation |
| `NOT_FOUND` | 404 | Resource not found |
| `CONFLICT` | 409 | Conflicting resource update found |
| `INTERNAL_SERVER_ERROR` | 500 | Error during operation |

`AbstractProjectReactor.execute()` catches any uncaught `ProjectException` and returns it serialized as a `PixelDataType.MAP` error response:

```json
{ "code": 500, "message": "Error during operation" }
```

All other exceptions are wrapped in `ProjectException(INTERNAL_SERVER_ERROR, e.getMessage(), e)` before serialization.

### Per-Reactor Error Conditions

| Location | Condition | Behavior |
|---|---|---|
| `GetSystemSimilarityDataSourcesReactor` | `database` param missing | Falls back to hardcoded default UUID |
| `GetSystemSimilarityDataSourcesReactor` | SPARQL query fails | `RuntimeException` from `QueryExecutor`, caught by base class |
| `ComputeSimilarityScoresReactor` | `SYS_SIM_PARAM_DATA_HASH` absent in var-store | Throws `IllegalStateException` (caught → `INTERNAL_SERVER_ERROR`) |
| `ComputeSimilarityScoresReactor` | `SYS_SIM_KEY_HASH` absent in var-store | Throws `IllegalStateException` (caught → `INTERNAL_SERVER_ERROR`) |
| `QueryExecutor` constructor | Engine ID null or blank | `IllegalArgumentException` |
| `QueryExecutor` constructor | Engine UUID unresolvable | `IllegalArgumentException` |
| `QueryExecutor.executeSelect()` | SPARQL execution failure | `RuntimeException` wrapping original cause |
| `ProjectProperties.getInstance()` | Not yet initialized | `ProjectException(INTERNAL_SERVER_ERROR)` |
| `ProjectProperties.loadProp()` | File missing/unreadable | Sets `INSTANCE = null`, logs WARN, does not throw |

---

## Data Model

### Pairwise Score Flow

```
SPARQL result rows
  → SimilarityFunctions (set-overlap or binary match)
  → Map<sysA_URI, Map<sysB_URI, score_0_to_1>>         ← rawScores (cached)
  → SimilarityChartingUtils.processHashForCharting()
  → Map<"labelA-labelB", { "Score": score_0_to_100 }>  ← paramDataHash bucket (cached)
  → ComputeSimilarityScoresReactor
  → [system1, system2, avgScore, varScores]              ← final data row
  → Frontend: matrix[row][col] = { score, percentile, categoryScores }
```

### Var-Store Cache Keys

All keys are `public static final String` constants on `GetSystemSimilarityDataSourcesReactor`:

```java
VARSTORE_PARAM_DATA_HASH = "SYS_SIM_PARAM_DATA_HASH"
VARSTORE_KEY_HASH        = "SYS_SIM_KEY_HASH"
VARSTORE_SYSTEM_LABEL_MAP = "SYS_SIM_SYSTEM_LABEL_MAP"
VARSTORE_ALL_SYSTEMS     = "SYS_SIM_ALL_SYSTEMS"
VARSTORE_RAW_SCORES      = "SYS_SIM_RAW_SCORES"
VARSTORE_DBS_MODE        = "SYS_SIM_DBS_MODE"
```

### Bucket Name Constants

```java
BUCKET_BP        = "Business_Processes_Supported"
BUCKET_ACT       = "Activities_Supported"
BUCKET_THEATER   = "Environment"
BUCKET_USERS     = "User_Types"
BUCKET_DATA_OBJ  = "Data_Subject_Area"
BUCKET_INTERFACE = "Interfaces"
```

> **Note:** The `README.md` in the `java/` folder documents different bucket names. The constants above reflect the actual compiled code.

---

## Pixel API Summary

| Pixel Command | Class | Called When |
|---|---|---|
| `GetSystemSimilarityDataSources` | `GetSystemSimilarityDataSourcesReactor` | Page load (all systems or DBS mode); Refresh when DBS toggle changes |
| `ComputeSimilarityScores` | `ComputeSimilarityScoresReactor` | After every `GetSystemSimilarityDataSources`; on every Refresh Heatmap click |

---

## Key Developer Notes

1. **Two-call protocol is mandatory.** `ComputeSimilarityScores` reads from var-store written by `GetSystemSimilarityDataSources`. Calling Stage 2 without Stage 1 throws `IllegalStateException`.

2. **`specifiedWeights` are minimum filters, not multipliers.** Despite the name, passing `specifiedWeights={"Environment": 80}` means "exclude any pair where `Environment` scores below 80" — not "multiply `Environment` by 80."

3. **Var-store is insight-scoped.** The cache in `SYS_SIM_PARAM_DATA_HASH` etc. is bound to the current SEMOSS insight session. Refreshing the browser starts a fresh session and requires a new Stage 1 call.

4. **DBS mode synthetic URIs.** When DBS label mode is active and a requested system has no RDF data, a synthetic URI `http://semoss.org/temp/system/{label}` is inserted. These appear as empty rows/columns on the heatmap. The frontend uses `VARSTORE_DBS_MODE` to activate DBS-specific axis handling.

5. **`SimilarityFunctions` is not thread-safe.** It holds mutable class-level state (`list`, `comparisonObjectList`). Each reactor instantiation creates its own `SimilarityFunctions` instance, so concurrent requests are safe at the reactor level but not within a shared instance.

6. **Bucket size sorting in Stage 2.** `ComputeSimilarityScoresReactor` sorts the selected variables by bucket size ascending before iterating. The smallest bucket defines the `masterKeys` set, which acts as an inner-join: only pairs present in all selected buckets are scored. This means adding a sparsely populated variable can significantly reduce the number of output pairs.

7. **Pixel naming convention.** Drop the `Reactor` suffix when calling from the frontend. `ComputeSimilarityScoresReactor` → `ComputeSimilarityScores(...)`.
