package reactors.systemSimilarity;

import java.util.List;
import java.util.Map;

import prerna.sablecc2.om.GenRowStruct;
import prerna.sablecc2.om.PixelDataType;
import prerna.sablecc2.om.PixelOperationType;
import prerna.sablecc2.om.ReactorKeysEnum;
import prerna.sablecc2.om.nounmeta.NounMetadata;
import reactors.AbstractProjectReactor;

/**
 * Orchestration reactor that internally runs Stage 1 (GetSystemSimilarityDataSources)
 * and Stage 2 (ComputeSimilarityScores) in a single Pixel call, returning a consolidated
 * similarity heatmap payload.
 *
 * <p>This reactor removes the two-stage var-store dependency burden from Playground
 * model prompts by executing the full pipeline in one call.
 *
 * <h3>Pixel call</h3>
 * <pre>
 *   RunSystemSimilarityHeatmap(
 *     database=["133db94b-4371-4763-bff9-edf7e5ed021b"],
 *     systemList=["http://...", "http://..."],        // optional
 *     systemQuery=null,                                // optional alternative
 *     selectedVars=["Environment", "User_Types"],      // optional
 *     specifiedWeights={"Environment": 2.0},           // optional
 *     minimumScore=[50]                                // optional
 *   );
 * </pre>
 *
 * <h3>Parameters</h3>
 * <ul>
 *   <li>{@code database} — <b>optional</b> — RDF engine UUID (default: TAP_Core_Data)</li>
 *   <li>{@code systemList} — <b>optional</b> — List of system URIs or labels to filter</li>
 *   <li>{@code systemQuery} — <b>optional</b> — SPARQL BINDINGS clause (alternative to systemList)</li>
 *   <li>{@code selectedVars} — <b>optional</b> — List of variable names to aggregate (default: all 6)</li>
 *   <li>{@code specifiedWeights} — <b>optional</b> — Map of variable name to weight multiplier (default: 1.0 each)</li>
 *   <li>{@code minimumScore} — <b>optional</b> — Composite score threshold 0-100 (default: 0)</li>
 * </ul>
 *
 * <h3>Output contract</h3>
 * Same as {@link ComputeSimilarityScoresReactor}:
 * <pre>
 * {
 *   "headers": ["System1", "System2", "Score"],
 *   "data": [[sys1, sys2, score, categoryScores], ...],
 *   "partialPairs": [[sys1, sys2, partialCategoryScores], ...],
 *   "variablesUsed": [...],
 *   "specifiedWeightsUsed": {...},
 *   "totalPairsEvaluated": int,
 *   "pairsAboveThreshold": int,
 *   "allSystems": [...],
 *   "systemLabelMap": {...}
 * }
 * </pre>
 *
 * @see GetSystemSimilarityDataSourcesReactor
 * @see ComputeSimilarityScoresReactor
 */
public class RunSystemSimilarityHeatmapReactor extends AbstractProjectReactor {

  public RunSystemSimilarityHeatmapReactor() {
    this.keysToGet = new String[] {
        ReactorKeysEnum.DATABASE.getKey(),   // optional (defaults to TAP_Core_Data)
        "systemList",                        // optional
        "systemQuery",                       // optional alternative
        "selectedVars",                      // optional
        "specifiedWeights",                  // optional
        "minimumScore"                       // optional
    };
    this.keyRequired = new int[] {0, 0, 0, 0, 0, 0};
  }

  @Override
  protected NounMetadata doExecute() {
    try {
      // ── 1. Parse inputs ──────────────────────────────────────────────────
      String database = this.keyValue.get(ReactorKeysEnum.DATABASE.getKey());
      List<String> systemList = getListParam("systemList");
      String systemQuery = getStringParam("systemQuery");
      List<String> selectedVars = getListParam("selectedVars");
      Map<String, Object> specifiedWeights = getMap("specifiedWeights");
      String minimumScoreStr = getStringParam("minimumScore");

      // ── 2. Instantiate and configure Stage 1 reactor ────────────────────
      GetSystemSimilarityDataSourcesReactor stage1 = new GetSystemSimilarityDataSourcesReactor();
      stage1.setInsight(this.insight);
      stage1.setNounStore(this.store);

      // Manually populate Stage 1 parameters via reflection (keyValue map)
      setReactorParam(stage1, ReactorKeysEnum.DATABASE.getKey(), database);
      if (systemList != null && !systemList.isEmpty()) {
        setReactorListParam(stage1, "systemList", systemList);
      }
      if (systemQuery != null && !systemQuery.isEmpty()) {
        setReactorParam(stage1, "systemQuery", systemQuery);
      }

      // ── 3. Run Stage 1 (populates var-store) ────────────────────────────
      stage1.execute();

      // ── 4. Instantiate and configure Stage 2 reactor ────────────────────
      ComputeSimilarityScoresReactor stage2 = new ComputeSimilarityScoresReactor();
      stage2.setInsight(this.insight);
      stage2.setNounStore(this.store);

      // Manually populate Stage 2 parameters
      if (selectedVars != null && !selectedVars.isEmpty()) {
        setReactorListParam(stage2, "selectedVars", selectedVars);
      }
      if (specifiedWeights != null && !specifiedWeights.isEmpty()) {
        setReactorMapParam(stage2, "specifiedWeights", specifiedWeights);
      }
      if (minimumScoreStr != null && !minimumScoreStr.isEmpty()) {
        setReactorParam(stage2, "minimumScore", minimumScoreStr);
      }

      // ── 5. Run Stage 2 and return its result ────────────────────────────
      return stage2.execute();

    } catch (Exception e) {
      throw new RuntimeException("RunSystemSimilarityHeatmap orchestration failed: " + e.getMessage(), e);
    }
  }

  // ── Helper methods for parameter transfer ─────────────────────────────────

  /**
   * Set a string parameter on a child reactor via reflection.
   */
  private void setReactorParam(AbstractProjectReactor reactor, String key, String value) {
    if (value == null || value.isEmpty()) {
      return;
    }
    try {
      java.lang.reflect.Field keyValueField = findField(reactor.getClass(), "keyValue");
      keyValueField.setAccessible(true);
      @SuppressWarnings("unchecked")
      Map<String, String> keyValueMap = (Map<String, String>) keyValueField.get(reactor);
      if (keyValueMap == null) {
        keyValueMap = new java.util.HashMap<>();
        keyValueField.set(reactor, keyValueMap);
      }
      keyValueMap.put(key, value);
    } catch (Exception e) {
      throw new RuntimeException("Failed to set reactor parameter: " + key, e);
    }
  }

  /**
   * Set a list parameter on a child reactor via reflection (store field).
   */
  private void setReactorListParam(AbstractProjectReactor reactor, String key, List<String> value) {
    if (value == null || value.isEmpty()) {
      return;
    }
    try {
      java.lang.reflect.Field storeField = findField(reactor.getClass(), "store");
      storeField.setAccessible(true);
      prerna.sablecc2.om.NounStore nounStore = (prerna.sablecc2.om.NounStore) storeField.get(reactor);

      GenRowStruct grs = new GenRowStruct();
      for (String item : value) {
        grs.addLiteral(item);
      }
      prerna.sablecc2.om.GenRowStruct existingGrs = nounStore.makeNoun(key);
      for (NounMetadata noun : grs.getVector()) {
        existingGrs.add(noun);
      }
    } catch (Exception e) {
      throw new RuntimeException("Failed to set reactor list parameter: " + key, e);
    }
  }

  /**
   * Set a map parameter on a child reactor via reflection (store field).
   */
  private void setReactorMapParam(AbstractProjectReactor reactor, String key, Map<String, Object> value) {
    if (value == null || value.isEmpty()) {
      return;
    }
    try {
      java.lang.reflect.Field storeField = findField(reactor.getClass(), "store");
      storeField.setAccessible(true);
      prerna.sablecc2.om.NounStore nounStore = (prerna.sablecc2.om.NounStore) storeField.get(reactor);

      GenRowStruct grs = new GenRowStruct();
      grs.add(new NounMetadata(value, PixelDataType.MAP));
      prerna.sablecc2.om.GenRowStruct existingGrs = nounStore.makeNoun(key);
      for (NounMetadata noun : grs.getVector()) {
        existingGrs.add(noun);
      }
    } catch (Exception e) {
      throw new RuntimeException("Failed to set reactor map parameter: " + key, e);
    }
  }

  /**
   * Find a field in the class hierarchy via reflection.
   */
  private java.lang.reflect.Field findField(Class<?> clazz, String fieldName) throws NoSuchFieldException {
    Class<?> current = clazz;
    while (current != null) {
      try {
        return current.getDeclaredField(fieldName);
      } catch (NoSuchFieldException e) {
        current = current.getSuperclass();
      }
    }
    throw new NoSuchFieldException(fieldName);
  }

  /**
   * Retrieve a List parameter from the reactor's input.
   */
  @SuppressWarnings("unchecked")
  private List<String> getListParam(String paramName) {
    GenRowStruct grs = this.store.getGenRowStruct(paramName);
    if (grs != null && !grs.isEmpty()) {
      List<String> strValues = grs.getAllStrValues();
      if (strValues != null && !strValues.isEmpty()) {
        return strValues;
      }
      NounMetadata firstNoun = grs.getNoun(0);
      if (firstNoun != null) {
        Object val = firstNoun.getValue();
        if (val instanceof List) {
          return (List<String>) val;
        }
      }
    }
    return null;
  }

  /**
   * Retrieve a String parameter from the reactor's input.
   */
  private String getStringParam(String paramName) {
    GenRowStruct grs = this.store.getGenRowStruct(paramName);
    if (grs != null && !grs.isEmpty()) {
      List<NounMetadata> stringInputs = grs.getNounsOfType(PixelDataType.CONST_STRING);
      if (stringInputs != null && !stringInputs.isEmpty()) {
        return (String) stringInputs.get(0).getValue();
      }
    }
    return null;
  }

  /**
   * Returns a description of this reactor's purpose, behavior, and output contract.
   * Used by MakePixelMCP to generate high-quality MCP tool schemas.
   *
   * @return reactor description string
   */
  public String getReactorDescription() {
    return "Single-call orchestration reactor that internally runs Stage 1 (GetSystemSimilarityDataSources) "
        + "and Stage 2 (ComputeSimilarityScores) to return a complete similarity heatmap payload. "
        + "Accepts all parameters for both stages (database, systemList, systemQuery, selectedVars, "
        + "specifiedWeights, minimumScore) and returns the same output as ComputeSimilarityScores. "
        + "Side effects: temporarily writes to var-store during orchestration (same keys as Stage 1). "
        + "Best for Playground single-shot usage — removes two-stage dependency complexity.";
  }

  /**
   * Returns a description for a specific parameter key.
   * Used by MakePixelMCP to generate parameter-level MCP tool schema descriptions.
   *
   * @param key the parameter key
   * @return parameter description string, or null if key is not recognized
   */
  public String getDescriptionForKey(String key) {
    switch (key) {
      case "database":
        return "Optional String UUID of the RDF engine to query (default: TAP_Core_Data "
            + "133db94b-4371-4763-bff9-edf7e5ed021b). Must contain System Similarity ontology triples.";
      case "systemList":
        return "Optional List<String> of system URIs or labels to filter results. If omitted, all systems "
            + "are returned. Absolute URIs are used directly in SPARQL BINDINGS; plain labels trigger post-filter mode.";
      case "systemQuery":
        return "Optional String SPARQL BINDINGS or VALUES clause to filter systems (alternative to systemList). "
            + "If both are provided, systemQuery is ignored.";
      case "selectedVars":
        return "Optional List<String> of variable names to include in aggregation (default: all 6 buckets). "
            + "Valid values: Business_Processes_Supported, Activities_Supported, Data_Subject_Area, Interfaces, "
            + "Environment, User_Types.";
      case "specifiedWeights":
        return "Optional Map<String, Number> of per-variable weight multipliers for weighted average calculation "
            + "(default: 1.0 for each). Example: {\"Environment\": 2.0, \"User_Types\": 1.0}.";
      case "minimumScore":
        return "Optional double threshold (0-100) for filtering composite scores (default: 0, no threshold). "
            + "Pairs below this value are excluded from data array.";
      default:
        return null;
    }
  }
}
