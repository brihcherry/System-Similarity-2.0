# System Similarity — Developer Guide

> **Scope:** Java backend reactors, TypeScript/React frontend, SPARQL data model, Pixel API contracts, build & test workflow, and known gaps. All data access is pure SPARQL against the `TAP_Core_Data` RDF engine.
> **Last updated:** 2026-07-06 (weighted-average semantics refresh)

---

## Table of Contents

1. [Overview](#overview)
2. [Project Structure](#project-structure)
3. [Technology Stack](#technology-stack)
4. [Architecture](#architecture)
   - [System Context](#system-context)
   - [Module Diagram](#module-diagram)
   - [Two-Stage Pipeline](#two-stage-pipeline)
   - [Frontend Provider Tree](#frontend-provider-tree)
5. [Backend — Java Reactors](#backend--java-reactors)
   - [AbstractProjectReactor](#abstractprojectreactor)
   - [GetSystemSimilarityDataSourcesReactor](#getsystemsimilaritydatasourcesreactor)
   - [ComputeSimilarityScoresReactor](#computesimilarityscoresreactor)
   - [GetSystemsByCapabilityGroupReactor](#getsystemsbycapabilitygroupreactor)
   - [Utility Classes](#utility-classes)
6. [Frontend — React/TypeScript](#frontend--reacttypescript)
   - [Feature Structure](#feature-structure)
   - [API Layer](#api-layer)
   - [Types](#types)
   - [Utils](#utils)
   - [Components](#components)
7. [Data & Pixel Contracts](#data--pixel-contracts)
   - [Pixel Commands](#pixel-commands)
   - [Var-Store Key Catalog](#var-store-key-catalog)
   - [Bucket Name Constants](#bucket-name-constants)
   - [SPARQL Query Inventory](#sparql-query-inventory)
   - [Scoring Algorithms](#scoring-algorithms)
   - [Aggregation Algorithm](#aggregation-algorithm)
8. [Build, Test, Run](#build-test-run)
   - [Prerequisites & Environment Variables](#prerequisites--environment-variables)
   - [Scripts](#scripts)
   - [Running Tests](#running-tests)
   - [Quality Gates](#quality-gates)
9. [Known Gaps & Deferred Work](#known-gaps--deferred-work)

---

## Overview

The app answers: **"How similar are military health IT systems to one another?"**

It loads pairwise similarity scores for systems in the `TAP_Core_Data` RDF knowledge graph and renders them as an interactive, color-coded heatmap. The scoring pipeline is split into two Pixel reactor calls:

- **Stage 1 (`GetSystemSimilarityDataSources`)** — runs 7 SPARQL queries, computes per-variable pairwise raw scores, and caches results in the SEMOSS insight var-store.
- **Stage 2 (`ComputeSimilarityScores`)** — reads the var-store cache, aggregates scores, and returns the final scored-pair table to the frontend.

Stage 2 is cheap — it reruns on every **Refresh Heatmap** click without re-querying the RDF engine.

---

## Project Structure

```
assets/
├── pom.xml                          Maven build (Java 21, JUnit Jupiter 6)
├── package.json                     Root pnpm scripts (dev, build, fix)
├── biome.json                       Biome lint / format config
├── java/
│   └── src/
│       ├── domain/base/
│       │   ├── ErrorCode.java            HTTP-style error code enum
│       │   └── ProjectException.java     Unchecked exception with error-map serialization
│       ├── reactors/
│       │   ├── AbstractProjectReactor.java          Base class for all project reactors
│       │   └── systemSimilarity/
│       │       ├── GetSystemSimilarityDataSourcesReactor.java   Stage 1 — SPARQL data fetch
│       │       ├── ComputeSimilarityScoresReactor.java          Stage 2 — score aggregation
│       │       └── GetSystemsByCapabilityGroupReactor.java      Capability-group dropdown data
│       ├── reactors/utils/
│       │   ├── QueryExecutor.java              SPARQL SELECT wrapper
│       │   ├── SimilarityFunctions.java        Pairwise scoring algorithms
│       │   └── SimilarityChartingUtils.java    Score -> chart-ready cell transforms
│       └── util/
│           └── ProjectProperties.java          Config singleton (skeleton — no active getters)
├── client/                          React + TypeScript SPA
│   └── src/
│       ├── index.tsx / App.tsx               Entry + env init
│       ├── contexts/AppContext.tsx            runPixel, login, logout
│       ├── pages/                            Router, layout guards, page shells
│       ├── features/systemSimilarityNew/     <- sole feature
│       │   ├── api/systemSimilarityApi-New.ts
│       │   ├── components/
│       │   ├── types/index.ts
│       │   └── utils/
│       ├── components/base + ui/             Nav, shadcn primitives
│       ├── hooks/useLoadingState.ts
│       └── lib/                              cn(), formatDisplayName()
├── test/reactors/                   JUnit test tree
│   ├── BaseReactorTest.java
│   ├── ReactorTestSuite.java        !! @SelectClasses({}) is empty — runs zero tests
│   └── systemSimilarity/
│       ├── ComputeSimilarityScoresReactorTest.java
│       └── GetSystemsByCapabilityGroupReactorTest.java
├── docs/
│   ├── README-developer.md          <- this file
│   └── README-user.md
└── mcp/pixel_mcp.json               !! "tools":[] — no MCP tools registered
```

---

## Technology Stack

| Layer | Technology | Version |
|---|---|---|
| Java | Java (source/target) | 21 |
| Build | Maven Compiler Plugin | 3.8.1 |
| Test (Java) | JUnit Jupiter | 6.0.0 |
| Test (Java) | JUnit Platform Suite | 6.0.0 |
| Test (Java) | Mockito Core | 5.18.0 |
| Frontend | React / React DOM | 18.3.1 |
| Frontend | react-router-dom | 7.13.1 |
| Build | Vite | 7.3.1 |
| Build | @vitejs/plugin-react | 5.1.4 |
| Language | TypeScript | 5.9.3 (strict=false) |
| Styling | Tailwind CSS | 4.2.1 |
| UI Primitives | Radix UI / shadcn layout | v1/v2 family |
| SDK | @semoss/sdk | 1.0.0-beta.38 |
| Lint/Format | Biome | 2.4.6 |
| Test (FE) | Vitest | 2.1.8 |
| Package Mgr | pnpm | — |
| Hooks | Husky / lint-staged | 9.1.7 / 16.3.2 |

**Version-sensitive constraints:**

- Keep Java source/target at **21** unless explicitly changed.
- Preserve the Vite root/env/build contract used by the SEMOSS portal output pipeline.
- Keep Biome schema aligned with **2.4.6** to avoid rule drift.
- Treat `@semoss/sdk` as project-coupled — no unreviewed major upgrades.

---

## Architecture

### System Context

The SEMOSS runtime is `provided` scope in `pom.xml` and is **not** embedded in this project.

```
Browser user
  --HTTPS-->
Vite-built SPA (portals/index.html)
  --runPixel via @semoss/sdk-->
SEMOSS Insight runtime
  --Pixel dispatch-->
Project reactors (reactors.systemSimilarity.*)
  --SPARQL SELECT-->  TAP_Core_Data RDF engine (UUID 133db94b...)
  --put/get-->        Insight var-store (SYS_SIM_* keys)
  <--NounMetadata MAP--
  <--JSON--
Vite SPA
```

### Module Diagram

```
java/src/
  domain.base          ErrorCode, ProjectException
  reactors             AbstractProjectReactor
  reactors.systemSimilarity
    GetSystemSimilarityDataSourcesReactor
    ComputeSimilarityScoresReactor
    GetSystemsByCapabilityGroupReactor
  reactors.utils       QueryExecutor, SimilarityFunctions, SimilarityChartingUtils
  util                 ProjectProperties

client/src/
  index.tsx + App.tsx
  contexts/AppContext    runPixel, login, logout
  pages/                Router, layouts, page shells
  features/systemSimilarityNew/  <- sole feature
    api/  components/  types/  utils/
  components/base + ui/
  hooks/  lib/

test/reactors/
  BaseReactorTest
  systemSimilarity/
    ComputeSimilarityScoresReactorTest
    GetSystemsByCapabilityGroupReactorTest
```

### Two-Stage Pipeline

```
Stage 1: GetSystemSimilarityDataSources(database=[...], [systemList=[...]])
  -> Runs 7 SPARQL queries against TAP_Core_Data
  -> SimilarityFunctions: set-overlap / binary match -> 0..1 raw scores
  -> SimilarityChartingUtils.processHashForCharting -> 0..100 chart cells
  -> Writes to insight var-store:
       SYS_SIM_PARAM_DATA_HASH, SYS_SIM_KEY_HASH, SYS_SIM_SYSTEM_LABEL_MAP,
       SYS_SIM_ALL_SYSTEMS, SYS_SIM_RAW_SCORES, SYS_SIM_DBS_MODE
  -> Returns {paramDataHash, systemLabelMap} (frontend ignores the return value)

Stage 2: ComputeSimilarityScores([selectedVars=[...]], [specifiedWeights={...}], [minimumScore=[N]])
  -> Reads cached data from var-store (NO SPARQL)
  -> Sorts selectedVars by bucket size ascending
  -> For each pair in master set: computes weighted average using specifiedWeights
     (default weight 1.0 for omitted vars)
  -> Coerces negative weights to 0; if all selected weights are 0, falls back to uniform mean
  -> Drops pairs only by completeness and global minimumScore
  -> Collects partial pairs (present in >= 1 bucket but excluded from data)
  -> Returns {headers, data, partialPairs, variablesUsed, specifiedWeightsUsed,
              totalPairsEvaluated, pairsAboveThreshold, allSystems, systemLabelMap}

Refresh click: passes skipDataSourcesReload=true -> only Stage 2 reruns.
```

**Var-store key flow (Stage 1 -> Stage 2):**

| Key constant | String value | Type | Notes |
|---|---|---|---|
| `VARSTORE_PARAM_DATA_HASH` | `SYS_SIM_PARAM_DATA_HASH` | MAP | `Map<bucket, Map<"labelA-labelB", {"Score": 0..100}>>` |
| `VARSTORE_KEY_HASH` | `SYS_SIM_KEY_HASH` | MAP | `Map<"labelA-labelB", {"System1": labelA, "System2": labelB}>` |
| `VARSTORE_SYSTEM_LABEL_MAP` | `SYS_SIM_SYSTEM_LABEL_MAP` | MAP | `Map<URI, displayLabel>` (both raw + normalized forms stored) |
| `VARSTORE_ALL_SYSTEMS` | `SYS_SIM_ALL_SYSTEMS` | MAP | Carries a `List<String>` of canonical system URIs |
| `VARSTORE_RAW_SCORES` | `SYS_SIM_RAW_SCORES` | MAP | `Map<bucket, Map<sysA_URI, Map<sysB_URI, 0..1>>>` — debug only |
| `VARSTORE_DBS_MODE` | `SYS_SIM_DBS_MODE` | BOOLEAN | `true` when a plain-label `systemList` was passed |

### Frontend Provider Tree

```
main (createRoot, StrictMode)
  App.tsx
    Env.update(import.meta.env)
    InsightProvider (@semoss/sdk/react)
      AppContextProvider (runPixel, login, logout)
        Router (createHashRouter)
          InitializedLayout  <- shows LoadingScreen until isInitialized
            AuthorizedLayout <- redirects to /login if not isAuthorized
              SystemSimilarityHeatmapPage (= SystemSimilarityPageNew)
          LoginPage
          Catch-all redirect -> /heatmap
      Toaster (sonner)
```

---

## Backend — Java Reactors

> **Naming convention:** The SEMOSS runtime strips the trailing `Reactor` suffix.
> `GetSystemSimilarityDataSourcesReactor` is dispatched as `GetSystemSimilarityDataSources(...)`.

### AbstractProjectReactor

| Attribute | Value |
|---|---|
| File | `java/src/reactors/AbstractProjectReactor.java` |
| Extends | `prerna.reactor.AbstractReactor` |
| Pixel command | (abstract — never dispatched directly) |

**Lifecycle (`execute()` flow):**

1. `preExecute()` — resolves `projectId`, lazily loads `ProjectProperties`, captures the authenticated `User`, calls `organizeKeys()` so subclasses can read from `this.keyValue` and `this.store`.
2. `doExecute()` — implemented by each subclass.
3. Any uncaught `Exception` from `doExecute()` is caught and serialized via `ProjectException.getAsMap()` into a `NounMetadata(map, PixelDataType.MAP, PixelOperationType.ERROR)`.

**Helper:** `getMap(paramName)` resolves `Map<String, Object>` parameters from the noun store.

---

### GetSystemSimilarityDataSourcesReactor

| Attribute | Value |
|---|---|
| File | `java/src/reactors/systemSimilarity/GetSystemSimilarityDataSourcesReactor.java` |
| Pixel command | `GetSystemSimilarityDataSources` |
| Test coverage | **NONE** — highest-risk surface with no direct tests |

#### Parameters

| Key | Type | Required | Default | Description |
|---|---|---|---|---|
| `database` | String (UUID) | Yes | `133db94b-4371-4763-bff9-edf7e5ed021b` | UUID of the RDF engine |
| `systemList` | `List<String>` | No | `null` | System URIs **or** plain display labels |
| `systemQuery` | String | No | `null` | Raw SPARQL BINDINGS clause (ignored if `systemList` is also set) |

`systemList` and `systemQuery` are mutually exclusive — if both are supplied `systemQuery` is cleared.

#### System Filter Modes

| Mode | Condition | Behavior |
|---|---|---|
| **All systems** | No `systemList` | Full system universe; no SPARQL filter appended |
| **URI list** | `systemList` contains `http(s)://` URIs | Appends `BINDINGS ?System {(<uri1>)...}` to every query |
| **Label list (DBS mode)** | `systemList` contains plain names (e.g. `"MHS_GENESIS"`) | No BINDINGS; post-filters `allSystems` by label (case-insensitive); synthesizes `http://semoss.org/temp/system/{label}` URIs for unmatched labels; sets `SYS_SIM_DBS_MODE = true` |

#### SPARQL Queries

| # | Bucket | Columns | Scoring |
|---|---|---|---|
| 0 | (Canonical system list — matrix axes) | `?System` | — |
| 1 | `Business_Processes_Supported` | `?System ?BusinessProcess` | Set overlap |
| 2 | `Activities_Supported` | `?System ?Activity` | Set overlap |
| 3 | `Data_Subject_Area` | `?System ?Data` | Set overlap |
| 4 | `Interfaces` | `?System ?SystemInterface` | Set overlap (Provide OR Consume) |
| 5 | `Environment` | `?System ?Theater` | Binary match (Theater/Garrison/Both) |
| 6 | `User_Types` | `?System ?Personnel` | Set overlap |

All queries are dispatched via `QueryExecutor.executeSelect()`. The SPARQL BINDINGS clause is appended at runtime in URI-list mode.

#### Var-Store Writes

| Key | Payload |
|---|---|
| `SYS_SIM_PARAM_DATA_HASH` | `Map<bucket, Map<"labelA-labelB", {"Score": 0..100}>>` |
| `SYS_SIM_KEY_HASH` | `Map<"labelA-labelB", {"System1": labelA, "System2": labelB}>` |
| `SYS_SIM_SYSTEM_LABEL_MAP` | `Map<URI, displayLabel>` |
| `SYS_SIM_ALL_SYSTEMS` | `List<String>` of canonical (or synthetic) system URIs |
| `SYS_SIM_RAW_SCORES` | `Map<bucket, Map<sysA_URI, Map<sysB_URI, 0..1>>>` |
| `SYS_SIM_DBS_MODE` | `Boolean` |

#### Return Value (`NounMetadata(Map, PixelDataType.MAP)`)

```json
{
  "paramDataHash": {
    "Business_Processes_Supported": { "SYSA-SYSB": { "Score": 75.0 } },
    "Activities_Supported":         { "..." },
    "Data_Subject_Area":            { "..." },
    "Interfaces":                   { "..." },
    "Environment":                  { "..." },
    "User_Types":                   { "..." }
  },
  "systemLabelMap": {
    "http://semoss.org/ontologies/.../SystemA": "SYSA"
  }
}
```

> **Note:** The frontend ignores the Stage-1 return value; only the var-store side-effects matter.

---

### ComputeSimilarityScoresReactor

| Attribute | Value |
|---|---|
| File | `java/src/reactors/systemSimilarity/ComputeSimilarityScoresReactor.java` |
| Pixel command | `ComputeSimilarityScores` |
| Test coverage | `ComputeSimilarityScoresReactorTest.java` (partial — see gaps) |

`GetSystemSimilarityDataSources` **must** be called first. This reactor runs no SPARQL queries.

#### Parameters

| Key | Type | Required | Default | Description |
|---|---|---|---|---|
| `selectedVars` | `List<String>` | No | All 6 bucket keys | Variable names to include in aggregation |
| `specifiedWeights` | `Map<String, Number>` | No | `null` | Per-variable multipliers for weighted-average aggregation (default `1.0` when absent; negative values are coerced to `0`) |
| `minimumScore` | double | No | `0.0` | Global minimum average score threshold applied **in all modes** |




> **`minimumScore` modes:** The global `minimumScore` filter is applied regardless of DBS mode. The server-side default is `0.0`; the frontend always supplies `50` on initial load.

#### Behavior

1. Read `SYS_SIM_PARAM_DATA_HASH` and `SYS_SIM_KEY_HASH` from var-store (throws `IllegalStateException` if either is missing).
2. Sort `selectedVars` by bucket size ascending, alphabetical tie-break. The smallest bucket's pair keys become the **master set**.
3. For each pair key in the master set:
  - Accumulate `weightedSum += (specifiedWeight * varScore)` and `totalWeight += specifiedWeight` for each selected variable.
  - Use `Math.max(0, specifiedWeight)` and treat missing/non-finite specified weights as default `1.0`.
  - Compute `score = weightedSum / totalWeight` when `totalWeight > 0`; otherwise fall back to a uniform mean.
  - Skip the pair if any variable is missing it.
  - Drop pairs whose final mean falls below `minimumScore`.
4. A second pass collects every pair present in at least one selected bucket but absent from the final `data` list — these become `partialPairs` for tooltip display.

#### Return Value (`NounMetadata(Map, PixelDataType.MAP, PixelOperationType.OPERATION)`)

```json
{
  "headers":               ["System1", "System2", "Score"],
  "data":                  [["SYSA", "SYSB", 75.5, {"Business_Processes_Supported": 80.0, "User_Types": 71.0}]],
  "partialPairs":          [["SYSA", "SYSC", {"Business_Processes_Supported": 60.0}]],
  "variablesUsed":         ["Business_Processes_Supported", "Activities_Supported"],
  "specifiedWeightsUsed":  {"Environment": 90.0},
  "totalPairsEvaluated":   156,
  "pairsAboveThreshold":   48,
  "allSystems":            ["http://semoss.org/.../SystemA"],
  "systemLabelMap":        {"http://semoss.org/.../SystemA": "SYSA"}
}
```

Each `data` row is `Object[]`: `[String system1, String system2, double score, Map<String,Double> varScores]`.
Each `partialPairs` row is `Object[]`: `[String system1, String system2, Map<String,Double> availableVarScores]`.

---

### GetSystemsByCapabilityGroupReactor

| Attribute | Value |
|---|---|
| File | `java/src/reactors/systemSimilarity/GetSystemsByCapabilityGroupReactor.java` |
| Pixel command | `GetSystemsByCapabilityGroup` |
| Test coverage | `GetSystemsByCapabilityGroupReactorTest.java` (happy path, empty result, missing-database fallback) |

Called by the frontend on initial mount to populate the **Capability Group** dropdown. Runs a single SPARQL query joining `?System` to `?CapabilityGroup` in `TAP_Core_Data`, then returns a map of group label to list of system labels.

#### Parameters

| Key | Type | Required | Default |
|---|---|---|---|
| `database` | String (UUID) | Yes | `133db94b-4371-4763-bff9-edf7e5ed021b` |

#### Return Value

```json
{
  "CapabilityGroupA": ["SYSA", "SYSB"],
  "CapabilityGroupB": ["SYSC"]
}
```

The frontend injects a synthetic `"DBS Systems"` entry into this map client-side using the hardcoded `DBS_SYSTEMS` constant in `systemSimilarityApi-New.ts` — it is **not** returned by the reactor.

---

### Utility Classes

#### `SimilarityFunctions` (`reactors.utils`)

Pairwise scoring algorithms. **Not thread-safe** (mutable class-level state). Each reactor instantiation creates its own instance, so concurrent requests are safe at the reactor level.

| Method | Algorithm | Used by buckets |
|---|---|---|
| `compareObjectParameterScore(db, query, "Value")` | Set overlap: score = `|A ∩ B| / |A|` | BP, Activities, Data Subject Area, Interfaces, User Types |
| `stringCompareBinaryResultGetter(db, query, "Theater", "Garrison", "Both")` | Binary match: 1.0 if same category or either is "Both"; 0.0 otherwise | Environment |

> **Legacy dead code:** `makeComparisonWithCRM`, `getDataBLUDataSet`, `getDataSet`, `makeDataHash` are retained but are **not invoked** by the active pipeline.

#### `SimilarityChartingUtils` (`reactors.utils`) — final utility class

| Method | Purpose |
|---|---|
| `processHashForCharting(dataHash, keyHash, typeX, typeY, systemLabelMap)` | URI -> label, drop self-comparisons, multiply by 100, key as `"labelA-labelB"` |
| `buildSystemLabelMap(List<String> systemUris)` | Last path segment, URL-decoded, collision suffix `_2`, `_3`, etc. |
| `toSystemLabel(raw, map)` | Exact match -> normalized lookup -> full scan -> fallback to last-path-segment |

#### `QueryExecutor` (`reactors.utils`)

Thin wrapper around `WrapperManager.getRawWrapper()`. Resolves engine aliases via `MasterDatabaseUtility.testDatabaseIdIfAlias()`. Each result row is stored in a `TreeMap<String,String>` for deterministic key ordering. Closes wrapper in `finally`.

```java
new QueryExecutor(String engineId)                    // throws IllegalArgumentException if unresolvable
List<Map<String,String>> executeSelect(String sparql) // throws RuntimeException on failure
```

#### `ProjectProperties` (`util`)

Singleton that loads `project.properties` from the project asset directory. Currently a skeleton — no active getters. Initialization failure is logged as `warn` and does **not** throw.

---

## Frontend — React/TypeScript

### Feature Structure

The entire heatmap feature lives under `client/src/features/systemSimilarityNew/`:

```
features/systemSimilarityNew/
├── index.ts                       Barrel export
├── api/
│   └── systemSimilarityApi-New.ts Pixel call helpers + response shaping
├── components/
│   ├── HeatmapGrid-New.tsx        Renders the scored matrix grid
│   └── RefreshHeatmapWidget-New.tsx Variable checklist + per-var weights
├── types/
│   └── index.ts                   Domain type definitions
└── utils/
    ├── transformHeatmap-New.ts    OutputResponse -> HeatmapMatrix
    ├── ComputePercentiles-New.tsx Percentile ranking (average-rank-for-ties)
    ├── filterHeatmapBySystems-New.ts Capability-group client-side filter
    └── formatRefreshPayload-New.ts   Widget state -> RefreshHeatmapRequest
```

The controller page `SystemSimilarityPage-New.tsx` lives under `pages/` and imports everything via the `@/features/systemSimilarityNew` barrel.

### API Layer

All Pixel strings are template literals dispatched through `AppContext.runPixel`, which wraps `@semoss/sdk`'s `runPixel`.

| Exported function | Pixel emitted | Notes |
|---|---|---|
| `ensureDataSourcesLoaded(runPixel)` | `GetSystemSimilarityDataSources(database=["133db94b-..."]);` | Stage 1 only |
| `fetchInitialHeatmapFromReactor(runPixel, options?)` | Stage 1 then Stage 2 | `minimumScore` defaults to 50 |
| `refreshHeatmapOutput(payload, runPixel, options?)` | `ComputeSimilarityScores(selectedVars=[...], specifiedWeights={...}, minimumScore=[N]);` | Stage 2 only |
| `fetchCapabilityGroups(runPixel)` | `GetSystemsByCapabilityGroup(database=["133db94b-..."]);` | Catches errors -> returns `{}` |

`DATABASE_ID` is hardcoded as `"133db94b-4371-4763-bff9-edf7e5ed021b"` and matches the Java reactor defaults.

### Types

Key types from `features/systemSimilarityNew/types/index.ts`:

| Type | Shape |
|---|---|
| `HeatmapMatrix` | `{xSystems, ySystems, matrix, minScore, maxScore, variablesUsed?, allSystems?}` |
| `HeatmapCell` | `{score?, percentile?, categoryScores?, isPartial?}` |
| `SimilarityRow` | `[string, string, number, Record<string,number>?]` |
| `PartialSimilarityRow` | `[string, string, Record<string,number>]` |
| `OutputResponse` | `{headers, data, allSystems?, systemLabelMap?, variablesUsed?, partialPairs?}` |
| `RefreshHeatmapRequest` | `{selectedVars, specifiedWeights?}` |
| `HeatmapRequestOptions` | `{skipDataSourcesReload?, minimumScore?}` |
| `CapabilityGroupMap` | `Record<string, string[]>` |

### Utils

| File | Key export | Behavior highlights |
|---|---|---|
| `transformHeatmap-New.ts` | `transformHeatmap(output, allSystems?, systemLabelMap?)` | With `allSystems`: normalize URIs -> labels, pre-populate axes even with no score. Without: derive axes from data (legacy compact). Calls `buildOutputWithPercentiles` first. |
| `ComputePercentiles-New.tsx` | `buildOutputWithPercentiles(output)` | Average-rank-for-ties; single-row returns 100; rounds to 2 decimals. |
| `filterHeatmapBySystems-New.ts` | `filterHeatmapBySystems(matrix, allowedSystems)` | Pure client-side; recomputes min/max from remaining cells. |
| `formatRefreshPayload-New.ts` | `formatRefreshHeatmapPayload(choices)` | Only checked vars in `selectedVars`; weights included only when input parses as integer. |
| `transformHeatmap-New.ts` | `scoreToColor(score, min, max, scheme)` | HSL-space gradients for `red`, `blue`, `green`, `traffic-light` schemes. |

### Components

| Component | File | Role |
|---|---|---|
| `HeatmapGridNew` | `HeatmapGrid-New.tsx` | CSS grid with sticky headers, single-cell hover tooltip. Cell status: `self`, `no-data`, `partial-data`, `filtered-out`, or scored. |
| `RefreshHeatmapWidgetNew` | `RefreshHeatmapWidget-New.tsx` | Variable checklist + per-variable weight inputs + Refresh button. Emits `RefreshHeatmapRequest` via `onRefresh` prop. |

**`DEFAULT_REFRESH_VARIABLES`** (must match Stage-1 bucket names exactly):
`Environment`, `Business_Processes_Supported`, `User_Types`, `Data_Subject_Area`, `Interfaces`, `Activities_Supported`

---

## Data & Pixel Contracts

### Pixel Commands

| # | Pixel template | Producer | Target reactor |
|---|---|---|---|
| 1 | `GetSystemSimilarityDataSources(database=["${DATABASE_ID}"]);` | `systemSimilarityApi-New.ts` | `GetSystemSimilarityDataSourcesReactor` |
| 2 | `ComputeSimilarityScores(minimumScore=[${n}]);` | Same | `ComputeSimilarityScoresReactor` |
| 3 | `ComputeSimilarityScores(selectedVars=${json}, minimumScore=[${n}]);` | Same | Same |
| 4 | `ComputeSimilarityScores(selectedVars=${json}, specifiedWeights=${json}, minimumScore=[${n}]);` | Same | Same |
| 5 | `GetSystemsByCapabilityGroup(database=["${DATABASE_ID}"]);` | Same | `GetSystemsByCapabilityGroupReactor` |

### Var-Store Key Catalog

| Constant | String value | PixelDataType | Producer | Consumer | Payload |
|---|---|---|---|---|---|
| `VARSTORE_PARAM_DATA_HASH` | `SYS_SIM_PARAM_DATA_HASH` | MAP | Stage 1 | Stage 2 (required) | `Map<bucket, Map<pairKey, {"Score":0..100}>>` |
| `VARSTORE_KEY_HASH` | `SYS_SIM_KEY_HASH` | MAP | Stage 1 | Stage 2 (required) | `Map<pairKey, {"System1":labelA, "System2":labelB}>` |
| `VARSTORE_SYSTEM_LABEL_MAP` | `SYS_SIM_SYSTEM_LABEL_MAP` | MAP | Stage 1 | Stage 2 (optional) | `Map<URI, displayLabel>` |
| `VARSTORE_ALL_SYSTEMS` | `SYS_SIM_ALL_SYSTEMS` | MAP | Stage 1 | Stage 2 (optional) | `List<String>` of URIs |
| `VARSTORE_RAW_SCORES` | `SYS_SIM_RAW_SCORES` | MAP | Stage 1 | None (observability) | `Map<bucket, Map<sysA_URI, Map<sysB_URI, 0..1>>>` |
| `VARSTORE_DBS_MODE` | `SYS_SIM_DBS_MODE` | BOOLEAN | Stage 1 | Stage 2 (read, no branch post-refactor) | `Boolean` |

> **`SYS_SIM_DBS_MODE` note:** After the symmetric-axis refactor, Stage 2 reads this value but the main scoring loop no longer branches on it. Partial-pair gathering runs unconditionally in both modes.

### Bucket Name Constants

These string values flow across the FE/BE boundary as `selectedVars` / `specifiedWeights` keys.

| Java constant | String value | Frontend `DEFAULT_REFRESH_VARIABLES` index |
|---|---|---|
| `BUCKET_BP` | `Business_Processes_Supported` | 1 |
| `BUCKET_ACT` | `Activities_Supported` | 5 |
| `BUCKET_THEATER` | `Environment` | 0 |
| `BUCKET_USERS` | `User_Types` | 2 |
| `BUCKET_DATA_OBJ` | `Data_Subject_Area` | 3 |
| `BUCKET_INTERFACE` | `Interfaces` | 4 |

> **`BUCKET_THEATER`** — the constant name is misleading; the emitted string is `"Environment"`.

### SPARQL Query Inventory

All queries live in `GetSystemSimilarityDataSourcesReactor`. All IRIs are under `http://semoss.org/ontologies/...`.

| Purpose | Result columns | Notes |
|---|---|---|
| Canonical system list | `?System` | Populates systemLabelMap |
| Business processes per system | `?System ?BusinessProcess` | `Supports` relation |
| Activities per system | `?System ?Activity` | `Supports` relation |
| Data subject areas per system | `?System ?Data` | `rdfs:subPropertyOf Provide` |
| System interfaces per system | `?System ?SystemInterface` | `Provide OR Consume` (UNION) |
| Theater/Garrison env per system | `?System ?Theater` | `Contains/GarrisonTheater` |
| Personnel/user types per system | `?System ?Personnel` | `UsedBy` |
| Capability-group to system join | `?System ?CapabilityGroup` | In `GetSystemsByCapabilityGroupReactor` |

URI filter injection: `BINDINGS ?System {(<uri1>)...}` is appended in URI-list mode. In plain-label mode, post-filtering happens in `fetchAllSystems`.

### Scoring Algorithms

**Set overlap** (`compareObjectParameterScore`):

  score(A, B) = |E_A ∩ E_B| / |E_A|

Returns 0.0-1.0. Used by: Business Processes, Activities, Data Subject Area, Interfaces, User Types.

**Binary category match** (`stringCompareBinaryResultGetter`):

  score(A, B) = 1.0 if same category or either is "Both"; 0.0 otherwise

Used by: Environment (Theater / Garrison / Both).

### Aggregation Algorithm

Implemented in `ComputeSimilarityScoresReactor`:

  score_pair = sum(w_v * score_v_pair for v in V) / sum(w_v for v in V)

where V is `selectedVars` (default: all 6 buckets), sorted by ascending bucket size, and `w_v` is from `specifiedWeights` with default `1.0` when omitted. Non-finite weights are ignored (default applies), and negative weights are coerced to `0`.

If all selected weights resolve to `0`, the reactor falls back to a uniform mean to avoid division by zero.

Per-variable scores are on the [0, 100] scale. A pair is excluded if:
- any selected variable does not contain the pair, **or**
- the final mean is below the global `minimumScore` (applies in all modes).

Pairs excluded from `data` but present in at least one selected bucket appear in `partialPairs`.

**Frontend-only computations:**

| Computation | File | Notes |
|---|---|---|
| Percentile (average-rank-for-ties) | `ComputePercentiles-New.tsx` | Operates on `data` only; partial pairs excluded |
| Capability-group view filter | `filterHeatmapBySystems-New.ts` | Pure client-side; no Pixel re-call |
| Display-range gating | `HeatmapGrid-New.tsx` | Color only; underlying score unchanged |
| Color scheme interpolation | `transformHeatmap-New.ts` | HSL gradients |

---

## Build, Test, Run

### Prerequisites & Environment Variables

#### 1. Install dependencies

```bash
# From assets/
pnpm i

# From assets/client/
pnpm i
```

#### 2. Configure environment variables

Vite reads `.env` files from `assets/client/` (`envDir: "../"` in `vite.config.ts`). The committed `client/.env` provides defaults. Create `client/.env.local` to override locally:

| Variable | Required | How injected | Description |
|---|---|---|---|
| `ENDPOINT` | Dev only | `vite.config.ts` proxy | SEMOSS server URL (e.g. `http://localhost:9090/`) |
| `MODULE` | Yes | `define()` -> `import.meta.env.MODULE` | SEMOSS module path (e.g. `/Monolith`) |
| `APP` | Yes | `define()` -> `import.meta.env.APP` | App UUID (e.g. `7bca0db5-8908-4b8a-a38f-bc0ed4996693`) |
| `VITE_ACCESS_KEY` | Dev only | Vite standard | Dev access key |
| `VITE_SECRET_KEY` | Dev only | Vite standard | Dev secret key |

> **IMPORTANT — Onboarding fix:** The env key is `APP`, **not** `VITE_APP`. Using `VITE_APP=` in `.env.local` will not work because `App.tsx` reads `import.meta.env.APP` via Vite's `define()` — not the standard `VITE_` prefix mechanism. The `client/README.md` incorrectly documents this as `VITE_APP=`; use `APP=` instead.

#### 3. TypeScript configuration highlights

| Option | Value |
|---|---|
| `strict` | `false` (prefer explicit domain types over `any`) |
| `moduleResolution` | `bundler` |
| `paths` | `{"@/*": ["src/*"]}` — use `@/` for cross-folder imports |
| `outDir` | `../portals` |

### Scripts

| Command | Working dir | Purpose |
|---|---|---|
| `pnpm dev` | `assets/` | Starts Vite dev server with HMR (`pnpm --dir client dev`) |
| `pnpm build` | `assets/` | Vite production build -> `portals/` |
| `pnpm fix` | `assets/` | Biome format + lint + pre-commit |
| `pnpm test` | `assets/client/` | Run Vitest (`vitest run --config vitest.config.mts`) |
| `pnpm javadoc` | `assets/` | Generate Javadoc and open at localhost:1227 |
| `mvn test` | `assets/` | All JUnit Jupiter reactor tests |
| `mvn test -Dtest=ComputeSimilarityScoresReactorTest` | `assets/` | Single reactor test class |
| `mvn test -Dtest=ReactorTestSuite` | `assets/` | !! Runs ZERO tests — `@SelectClasses({})` is empty |

### Running Tests

#### Java

```bash
# From assets/
mvn test                                              # all reactor tests
mvn test -Dtest=ComputeSimilarityScoresReactorTest    # Stage-2 reactor only
mvn test -Dtest=GetSystemsByCapabilityGroupReactorTest
```

Test infrastructure:

- **`BaseReactorTest`** — provides shared SEMOSS mocking (Insight, User, NounStore, PyTranslator, AssetUtility static mocking, temp project assets).
- Extend `BaseReactorTest` for any new reactor test; use its parameter-injection and Python-stub helpers.
- Tests must be hermetic — no live network or engine dependency; use mocks/fakes.
- Name new test files `*ReactorTest.java` to maintain suite discoverability.

#### Frontend (Vitest)

```bash
# From assets/client/
pnpm test
```

Vitest 2 in node environment picks up `src/**/*.test.{ts,tsx}`. Currently only `filterHeatmapBySystems-New.test.ts` exists.

#### Coverage status

| Surface | Coverage |
|---|---|
| `ComputeSimilarityScoresReactor` | Partial (partial-pair emission only; no `minimumScore`, `specifiedWeights`, or `IllegalStateException` paths) |
| `GetSystemsByCapabilityGroupReactor` | Good (happy path, empty result, fallback database) |
| `GetSystemSimilarityDataSourcesReactor` | **None** |
| `filterHeatmapBySystems` | 4 Vitest cases |
| All other frontend code | **None** |

> Overall coverage is far below the org target of >=80%. See [Known Gaps](#known-gaps--deferred-work) for the prioritized test backlog.

### Quality Gates

| Gate | Mechanism |
|---|---|
| Biome format + lint on staged `js/jsx/ts/tsx/json/html/css` | `lint-staged` (runs on `git commit`) |
| Husky `pre-commit` | Runs `lint-staged` then `pre-commit run --all-files` |
| Husky `commit-msg` | Pre-commit hooks for commit-msg stage |

**Biome rules of note (errors that block commit):**

| Rule | Severity |
|---|---|
| `correctness/noUnusedVariables` | error |
| `correctness/noUnusedImports` | error |
| `correctness/useExhaustiveDependencies` | error |
| `style/useConst` | error |
| `suspicious/noExplicitAny` | error |
| `suspicious/noVar` | error |
| `complexity/noUselessFragments` | error |
| `suspicious/noConsole` | warn |

---

## Known Gaps & Deferred Work

Tracked in `_bmad-output/implementation-artifacts/deferred-work.md`.

### Priority fixes

| Priority | Area | Issue |
|---|---|---|
| P1 | Onboarding | `client/README.md` documents `VITE_APP=` but the correct key is `APP=` (blocks new contributors) |
| P2 | Backend docs | `java/README.md` lists 7 variables and a Stage-1 pruning step; current code has 6 buckets and pruning is Stage-2 only — update or delete |
| P3 | Test coverage | `GetSystemSimilarityDataSourcesReactor` has zero tests — highest-risk surface in the system |
| P4 | Null safety | `AbstractProjectReactor.getMap()` dereferences `this.curRow` unconditionally; make it null-safe |

### Test coverage gaps (backend)

| Surface | Gap |
|---|---|
| `GetSystemSimilarityDataSourcesReactor` | All paths — no test file exists |
| `ComputeSimilarityScoresReactor` | `minimumScore` filter branch |
| `ComputeSimilarityScoresReactor` | weighted-aggregation edge paths (all-zero weights fallback, negative/non-finite input handling) |
| `ComputeSimilarityScoresReactor` | `IllegalStateException` on missing var-store keys |
| `AbstractProjectReactor` | Error-wrap path in `execute()` |

### Test coverage gaps (frontend)

- `transformHeatmap`, `ComputePercentiles`, `formatRefreshPayload`, `scoreToColor` — no tests
- All UI components — no tests

### Code health items

| Item | Detail |
|---|---|
| `ComputeSimilarityScoresReactor` unchecked cast | `cellData.get("Score")` cast to `Number` without `instanceof` guard; malformed bucket data causes `ClassCastException` |
| `ReactorTestSuite` | `@SelectClasses({})` is empty — invoking the suite by name runs zero tests. Populate or remove. |
| `HomePage.tsx` orphan | Component is defined and exported but not routed anywhere. Wire to `/` or delete. |
| Dead code — CRM helpers | `makeComparisonWithCRM`, `getDataBLUDataSet`, `getDataSet`, `makeDataHash` not invoked by active pipeline. Delete or move to `legacy/` subpackage. |
| Vestigial debug types | `PlaysheetFieldInfo`, `IntrospectResponse`, `ParamDataHashResponse` in `types/index.ts` reference Pixel calls with no reactor or caller. Remove if no longer planned. |
| MCP manifest empty | `mcp/pixel_mcp.json` has `"tools": []`; no reactor implements `getDescriptionForKey()` |

### Open questions

1. Is `"DBS Systems"` capability-group the final UX, replacing the All Systems/DBS Only toggle?
2. Should `specifiedWeights` be renamed to reflect its actual semantics (aggregation multipliers)?
3. Was the 7-variable -> 6-variable collapse intentional or a regression?
4. Should the server-side `minimumScore` default be aligned with the frontend's `50`?
5. `assets/AGENTS.md` documents Python MCP tooling that does not exist in this repo — trim or replace?
