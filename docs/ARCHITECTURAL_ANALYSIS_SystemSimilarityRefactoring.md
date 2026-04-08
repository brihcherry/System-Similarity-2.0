# System Similarity Heatmap Refactoring: Legacy Playsheet to Project-Specific Reactors

## Executive Summary

**Objective:** Migrate the calculation logic from `SimilarityHeatMapSheet.java` and `SysSimHeatMapSheet.java` (legacy browser playsheets in the Monolith) into dedicated, project-specific reactors within the new React/TypeScript SystemSimilarity UI project.

**Current State:** The legacy architecture uses two playsheets that perform RDF queries, data transformation, similarity calculations, and state caching inside the browser process. The SystemSimilarity project currently delegates to `RunPlaysheetReactor`, which invokes these playsheets server-side.

**Target State:** Replace all playsheet logic with a suite of specialized reactors that:
- Execute queries and transformations server-side (not in browser context)
- Are stateless and data-driven (all parameters passed via pixel calls)
- Support caching/persistence at the API layer (TypeScript), not on the backend
- Maintain separation of concerns: data collection, transformation, calculation, aggregation

---

## Part 1: Understanding the Legacy Architecture

### 1.1 Calculation Flow in SimilarityHeatMapSheet

The playsheet operates as a **three-phase pipeline**:

```
Phase 1: BUILD PARAMETER DATA
├─ paramDataHash: Hashtable<String, Hashtable<String, Hashtable<String, Object>>>
│  └─ Structure: {
│       "Variable_Name_1": {
│         "System1-System2": { "Score": 0.75 },
│         "System1-System3": { "Score": 0.82 },
│         ...
│       },
│       "Variable_Name_2": { ... },
│       ...
│     }
└─ This is pre-calculated and stored per session (stateful)

Phase 2: CALCULATE HEATMAP (on demand or refresh)
├─ Input: selectedVars (list), specifiedWeights (map), user selections
├─ Algorithm: calculateHash(selectedVars, specifiedWeights)
│  └─ Iterate over all comparison cells
│  └─ For each cell, sum weighted scores from selected variables
│  └─ Only include cells where ALL variables have valid data
│  └─ Average score: (score1/n + score2/n + ... + scoreN/n)
└─ Output: List<Map<String, Map<String, Double>>>

Phase 3: TRANSFORM FOR UI
├─ flattenData(): Convert nested hashes to table rows
├─ Filters: Remove low-scoring pairs (< 50)
├─ Output: List<Object[]> for HTML table display
```

### 1.2 Calculation Flow in SysSimHeatMapSheet

The subclass **extends** the above with data-collection logic:

```
Data Collection Phase (createData method)
├─ Query 1: Systems + Data/BLU Score
├─ Query 2: Theater/Garrison Deployment
├─ Query 3: Transactional (Yes/No)
├─ Query 4: Business Processes Supported
├─ Query 5: Activities Supported
├─ Query 6: User Types
├─ Query 7: User Interface Types
│  
└─ Transform all 7 results via processHashForCharting()
   └─ Output: Populate paramDataHash with 7 different variables
```

Key insight: **The playsheet caches query results in `paramDataHash` for the session lifetime.** Refresh operations only recalculate `calculateHash()`, NOT the queries.

---

## Part 2: Logic Decomposition into Reactors

To migrate this, we decompose into **6 specialized, stateless reactors**:

### Reactor 1: `GetSystemSimilarityDataSourcesReactor`
**Purpose:** Execute all 7 RDF queries and return raw data.

**Inputs:**
```
GetSystemSimilarityDataSources(
  database=["133db94b-4371-4763-bff9-edf7e5ed021b"],
  systemFilter=[] // optional SPARQL bindings query result
);
```

**Logic:**
- Execute all 7 SPARQL queries concurrently
- Use `SimilarityFunctions` helper class (or port its methods to reactor)
- Return raw query results for each data source

**Output:**
```json
{
  "dataBLUDataSet": { "System1": { "Data1": 0.8, ... }, ... },
  "theaterData": { "System1": { "System2": 0.7, ... }, ... },
  "transactionalData": { ... },
  "businessProcessesData": { ... },
  "activitiesData": { ... },
  "userTypesData": { ... },
  "userInterfaceData": { ... }
}
```

**Complexity Level:** HIGH
- Must handle RDF queries
- Must parallelize 7 queries for performance
- Must handle null/empty results gracefully
- Pixel call: moderate complexity

---

### Reactor 2: `TransformDataForSimilarityReactor`
**Purpose:** Transform raw query results into the `paramDataHash` format.

**Inputs:**
```
TransformDataForSimilarity(
  dataBLUDataSet=[{"System1": "Data1", "Score": 0.8}, ...],
  theaterData=[...],
  transactionalData=[...],
  businessProcessesData=[...],
  activitiesData=[...],
  userTypesData=[...],
  userInterfaceData=[...]
);
```

**Logic:**
- For each input data source, call `processHashForCharting()`
- Transform each result-set into `{key: {...}}` structure
- Aggregate all 7 transformed datasets

**Output:**
```json
{
  "Business_Processes_Supported": { "System1-System2": { "Score": 0.7 }, ... },
  "Activities_Supported": { ... },
  "Data_and_Business_Logic_Supported": { ... },
  "Deployment_(Theater/Garrison)": { ... },
  "Transactional_(Yes/No)": { ... },
  "User_Types": { ... },
  "User_Interface_Types_(PC/Mobile/etc.)": { ... }
}
```

**Complexity Level:** MEDIUM
- Pure data transformation (no queries)
- Can be tested in isolation
- Outputs format compatible with next reactor

---

### Reactor 3: `CalculateSystemSimilarityHeatmapReactor`
**Purpose:** Calculate weighted similarity scores across selected variables.

**Inputs:**
```
CalculateSystemSimilarityHeatmap(
  paramDataHash=[...], // output from Reactor 2
  selectedVars=["Business_Processes_Supported", "User_Types"],
  specifiedWeights={"Business_Processes_Supported": 1.0, "User_Types": 0.5}
);
```

**Logic:**
- Implement the core `calculateHash()` algorithm
- For each comparison cell, sum weighted scores
- Filter: only include cells where ALL variables exist
- Average: divide by number of selected variables

**Output:**
```json
{
  "System1-System2": { "System1": "System1", "System2": "System2", "Score": 0.72 },
  "System1-System3": { "System1": "System1", "System3": "System3", "Score": 0.68 },
  ...
}
```

**Complexity Level:** MEDIUM-LOW
- Pure calculation logic (no IO)
- Highest test coverage opportunity
- Port existing algorithm directly

---

### Reactor 4: `FilterAndFlattenSystemSimilarityReactor`
**Purpose:** Filter low-scoring pairs and flatten nested structure to table rows.

**Inputs:**
```
FilterAndFlattenSystemSimilarity(
  calculatedHeatmap=[...], // output from Reactor 3
  minScoreThreshold=50, // filter out scores below this
  comparisonObjectTypeX="System1",
  comparisonObjectTypeY="System2"
);
```

**Logic:**
- Iterate over calculated heatmap
- Filter: remove pairs with score < minScoreThreshold
- Flatten: convert nested map to `[System1, System2, Score]` rows
- Optionally remove corresponding entries from paramDataHash for memory optimization

**Output:**
```json
{
  "headers": ["System1", "System2", "Score"],
  "data": [
    ["SystemA", "SystemB", 75.2],
    ["SystemA", "SystemC", 68.9],
    ...
  ]
}
```

**Complexity Level:** MEDIUM-LOW
- Simple filtering and iteration
- Output directly consumable by React component

---

### Reactor 5: `RefreshSystemSimilarityHeatmapReactor`
**Purpose:** Combine Reactors 3 + 4 into a single call for UX refresh operations.

**Inputs:**
```
RefreshSystemSimilarityHeatmap(
  paramDataHash=[...], // cached from initial load, passed by client
  selectedVars=["Business_Processes_Supported"],
  specifiedWeights={"Business_Processes_Supported": 0.8},
  minScoreThreshold=50
);
```

**Logic:**
- Call Reactor 3's calculation algorithm
- Call Reactor 4's filtering algorithm
- Return flattened output in one operation

**Output:** Same as Reactor 4

**Complexity Level:** LOW
- Orchestration wrapper
- Reduces round-trips on refresh

---

### Reactor 6: `GetSystemListForSimilarityReactor` (Optional)
**Purpose:** When a custom system filter is provided, execute the filter query and return system URIs for binding.

**Inputs:**
```
GetSystemListForSimilarity(
  database=["133db94b-4371-4763-bff9-edf7e5ed021b"],
  customQuery="SELECT ?System WHERE { ... }"
);
```

**Logic:**
- Execute the custom RDF query
- Extract system URIs
- Return formatted SPARQL bindings string

**Output:**
```json
{
  "systemCount": 42,
  "bindingsString": "BINDINGS ?System { (<http://...>) (<http://...>) ... }"
}
```

**Complexity Level:** MEDIUM
- Only needed if custom system filters are supported
- Can be implemented incrementally

---
