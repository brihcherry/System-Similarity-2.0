# System Similarity MCP Roadmap for SEMOSS Playground

## Goal
Expose System Similarity backend functionality from this app as MCP tools so Playground can call them safely and consistently.

This roadmap is tailored to:
- assets app root: project/System Similarity 2__7bca0db5-8908-4b8a-a38f-bc0ed4996693/app_root/version/assets
- current backend reactors in java/src/reactors/systemSimilarity
- current MCP manifest state: mcp/pixel_mcp.json has an empty tools array

## What the project currently does
System Similarity currently runs as a two-stage backend pipeline:

1. GetSystemSimilarityDataSources
- runs SPARQL queries against TAP_Core_Data
- computes per-variable pairwise values
- caches paramDataHash and keyHash in SEMOSS var-store

2. ComputeSimilarityScores
- reads var-store cache from stage 1
- applies selectedVars, specifiedWeights, minimumScore
- returns final similarity rows + partial pairs + labels

Plus one lookup reactor:

3. GetSystemsByCapabilityGroup
- returns capability-group to systems mapping
- used for client-side filtering

## MCP strategy options

### Option A: Fastest path (expose existing reactors directly)
Expose three MCP tools:
- GetSystemSimilarityDataSources
- ComputeSimilarityScores
- GetSystemsByCapabilityGroup

Pros:
- least code changes
- maps directly to existing implementation

Cons:
- tool call ordering dependency in Playground (stage 1 must run before stage 2)
- less friendly for single-shot model use

### Option B: Recommended path (add one orchestration reactor)
Keep the three tools above, and add one new reactor MCP tool:
- RunSystemSimilarityHeatmap

RunSystemSimilarityHeatmap should:
- accept user-facing inputs (database, systemList, selectedVars, specifiedWeights, minimumScore)
- internally call stage 1 then stage 2
- return one consolidated payload

Pros:
- best Playground usability (single call)
- removes var-store dependency burden from model prompt design
- easier safety and validation handling

Cons:
- one additional backend reactor to implement and test

## Required backend changes before MakePixelMCP
Pixel MCP schema quality depends on reactor descriptions. Add these to each reactor you expose:

1. getReactorDescription()
- clear purpose, side effects, and output shape

2. getDescriptionForKey(String key)
- parameter-level descriptions for database, systemList, systemQuery, selectedVars, specifiedWeights, minimumScore

Without these methods, generated MCP tool schemas will have weak placeholders.

## Proposed tool catalog and execution modes

1. GetSystemSimilarityDataSources
- Purpose: preload and cache datasource buckets in var-store
- Execution mode: ask
- Why ask: expensive query step and modifies cache state

2. ComputeSimilarityScores
- Purpose: aggregate weighted scores from cached data
- Execution mode: auto
- Why auto: deterministic and cheap after cache exists

3. GetSystemsByCapabilityGroup
- Purpose: read capability filters
- Execution mode: auto
- Why auto: read-only lookup

4. RunSystemSimilarityHeatmap (recommended new tool)
- Purpose: one-shot compute for Playground usage
- Execution mode: ask initially, then move to auto after validation
- Why ask initially: protects expensive runs while tuning

## Implementation phases

### Phase 0: Baseline and branch
- Create a feature branch for MCP enablement.
- Snapshot current mcp/pixel_mcp.json.
- Confirm reactors compile before changes.

### Phase 1: Metadata hardening in reactors
- Update the 3 existing reactors in java/src/reactors/systemSimilarity:
  - GetSystemSimilarityDataSourcesReactor.java
  - ComputeSimilarityScoresReactor.java
  - GetSystemsByCapabilityGroupReactor.java
- Add getReactorDescription and getDescriptionForKey implementations.
- Ensure parameter naming in descriptions exactly matches Pixel keys.

### Phase 2: Add orchestration reactor (recommended)
- Create java/src/reactors/systemSimilarity/RunSystemSimilarityHeatmapReactor.java
- Behavior:
  - parse and validate inputs
  - run stage 1 logic (or invoke reactor class logic if architecture allows)
  - run stage 2 with same insight context
  - return a single MAP payload for Playground
- Add robust error handling for:
  - missing database
  - empty selectedVars
  - malformed specifiedWeights
  - stage 1 missing cache outputs

### Phase 3: Generate MCP manifest
From Pro Code terminal, run MakePixelMCP using reactor names without the Reactor suffix.

Example command shape:

MakePixelMCP(
  project=<your_project_id>,
  reactor=["GetSystemSimilarityDataSources", "ComputeSimilarityScores", "GetSystemsByCapabilityGroup", "RunSystemSimilarityHeatmap"],
  mcpExecution=["ask", "auto", "auto", "ask"],
  comment="Add System Similarity MCP tools for Playground"
)

Notes:
- If mcpExecution has fewer entries than reactor list, missing values default to ask.
- Ensure reactor names exactly match class names minus Reactor.

### Phase 4: Compile, publish, deploy
- Recompile reactors in SEMOSS.
- Publish files so manifest changes are versioned.
- Deploy with persistence so Playground can discover MCP metadata.

### Phase 5: Validate in Playground
Checklist:
- mcp/pixel_mcp.json exists and includes all expected tools.
- each tool has name, description, input schema, and _meta.SMSS_MCP_EXECUTION.
- RunSystemSimilarityHeatmap works in one call and returns scored rows.
- direct stage-2 call without stage-1 returns expected guarded error.
- capability group tool returns a non-empty map or safe empty map.

### Phase 6: Promote execution modes
After stability checks:
- keep expensive tools as ask
- consider switching RunSystemSimilarityHeatmap to auto for better UX if cost/perf is acceptable

## Input contract recommendations for Playground prompts
Use strict and explicit contracts in descriptions:

- database: string UUID
- systemList: optional string array (URIs or labels)
- selectedVars: optional string array
- specifiedWeights: optional object map of string to number
- minimumScore: optional number from 0 to 100

Return contract should include:
- headers
- data
- partialPairs
- variablesUsed
- specifiedWeightsUsed
- totalPairsEvaluated
- pairsAboveThreshold
- allSystems
- systemLabelMap

## Risks and mitigations

1. Risk: stage-order dependency breaks tool calls
- Mitigation: provide RunSystemSimilarityHeatmap orchestration tool

2. Risk: weak auto-generated tool schema text
- Mitigation: implement description methods on all exposed reactors

3. Risk: expensive or repeated stage-1 runs
- Mitigation: keep datasource tool in ask mode and use orchestration tool controls

4. Risk: name mismatch in MakePixelMCP
- Mitigation: remove only Reactor suffix and validate generated manifest tool names

## Minimal success definition
MCP enablement is complete when:
- Playground discovers System Similarity tools from this app
- at least one single-call tool can return valid heatmap score output
- tool schemas are human-readable and parameter-accurate
- manifest survives publish/deploy and remains callable

## Suggested first increment
If you want fastest value:
1. add metadata methods to current three reactors
2. generate MCP with those three tools
3. validate in Playground
4. add RunSystemSimilarityHeatmap as a second increment
