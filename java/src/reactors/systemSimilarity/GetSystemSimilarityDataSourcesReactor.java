package reactors.systemSimilarity;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import prerna.sablecc2.om.GenRowStruct;
import prerna.sablecc2.om.PixelDataType;
import prerna.sablecc2.om.ReactorKeysEnum;
import prerna.sablecc2.om.nounmeta.NounMetadata;
import reactors.AbstractProjectReactor;
import reactors.utils.QueryExecutor;
import reactors.utils.SimilarityChartingUtils;
import reactors.utils.SimilarityFunctions;

/**
 * Reactor that executes the 7 RDF SPARQL queries for System Similarity analysis
 * and returns raw, per-bucket similarity data sources.
 *
 * <p>This reactor is the first step in the refactored System Similarity pipeline.
 * Its sole responsibility is to execute queries and convert results into canonical
 * per-system value sets. Scoring, aggregation, and weighting are handled downstream.
 *
 * <h3>Pixel call (from frontend {@code runPixel})</h3>
 * <pre>
 *   GetSystemSimilarityDataSources(
 *     database=["133db94b-4371-4763-bff9-edf7e5ed021b"],
 *     systemList=["http://...", "http://..."],  // optional
 *     systemQuery=null                           // optional alternative
 *   );
 * </pre>
 *
 * <h3>Parameters</h3>
 * <ul>
 *   <li>{@code database} — <b>required</b> — the RDF engine UUID to query against</li>
 *   <li>{@code systemList} — <b>optional</b> — List of system URIs to filter results;
 *       if absent, uses default SystemUser bindings</li>
 *   <li>{@code systemQuery} — <b>optional alternative to systemList</b> — custom SPARQL
 *       filter or VALUES clause for system restriction</li>
 * </ul>
 *
 * <h3>Output contract</h3>
 * <pre>
 * {
 *   "buckets": {
 *     "Business_Processes_Supported": {
 *       "System1": ["BP1", "BP2"],
 *       "System2": ["BP1", "BP3"],
 *       ...
 *     },
 *     "Activities_Supported": { ... },
 *     "Data_and_Business_Logic_Supported": { ... },
 *     "Deployment_(Theater/Garrison)": { ... },
 *     "Transactional_(Yes/No)": { ... },
 *     "User_Types": { ... },
 *     "User_Interface_Types_(PC/Mobile/etc.)": { ... }
 *   },
 *   "systems": ["System1", "System2", "System3"],
 *   "meta": { "queryTimesMs": {...} },
 *   "rawRows": { ... }  // optional debug data
 * }
 * </pre>
 *
 * @see reactors.AbstractProjectReactor
 */
public class GetSystemSimilarityDataSourcesReactor extends AbstractProjectReactor {

  /** System property key for default engine UUID used when database is not provided. */
  private static final String DEFAULT_ENGINE_ID = "133db94b-4371-4763-bff9-edf7e5ed021b";

  /** Var-store key for caching the computed paramDataHash for downstream reactors. */
  public static final String VARSTORE_PARAM_DATA_HASH = "SYS_SIM_PARAM_DATA_HASH";

  /** Var-store key for caching the keyHash (pair key → system name mapping). */
  public static final String VARSTORE_KEY_HASH = "SYS_SIM_KEY_HASH";

  /** Var-store key for caching the systemLabelMap (URI → display label). */
  public static final String VARSTORE_SYSTEM_LABEL_MAP = "SYS_SIM_SYSTEM_LABEL_MAP";

  /** Var-store key for caching the allSystems list (canonical system URIs). */
  public static final String VARSTORE_ALL_SYSTEMS = "SYS_SIM_ALL_SYSTEMS";

  /** Var-store key for caching the raw (pre-processHashForCharting) scores per variable. */
  public static final String VARSTORE_RAW_SCORES = "SYS_SIM_RAW_SCORES";

  /** Var-store key for whether current run is DBS label mode. */
  public static final String VARSTORE_DBS_MODE = "SYS_SIM_DBS_MODE";

  /** Bucket name constants matching legacy playsheet paramDataHash keys. */
  private static final String BUCKET_BP = "Business_Processes_Supported";
  private static final String BUCKET_ACT = "Activities_Supported";
  private static final String BUCKET_THEATER = "Environment";
  private static final String BUCKET_TRANSACTIONAL = "Transactional_(Yes/No)";
  private static final String BUCKET_USERS = "User_Types";
  private static final String BUCKET_DATA_OBJ = "Data_Subject_Area";
  private static final String BUCKET_INTERFACE = "Interfaces";

  /** Default SystemUser bindings appended when no system filter is provided (legacy behavior). */
  private static final String DEFAULT_SYSTEM_USER_BINDINGS =
      "BINDINGS ?SystemUser {(<http://health.mil/ontologies/Concept/SystemOwner/Central>)"
      + "(<http://health.mil/ontologies/Concept/SystemUser/Army>)"
      + "(<http://health.mil/ontologies/Concept/SystemUser/Navy>)"
      + "(<http://health.mil/ontologies/Concept/SystemUser/Air_Force>)}";

  private String engineId;
  private List<String> systemList;
  private String systemQuery;
  private String bindingsClause;
  private List<String> allSystems;
  /**
   * Non-null when {@code systemList} contains plain labels rather than absolute URIs.
   * In that case {@code bindingsClause} falls back to the default SystemUser bindings and
   * {@code fetchAllSystems} post-filters the full result down to just these labels.
   */
  private List<String> labelsToFilter;
  private Map<String, String> systemLabelMap = new HashMap<>();
  private final Map<String, Map<String, Map<String, Object>>> paramDataHash = new HashMap<>();
  private final Map<String, Map<String, Object>> keyHash = new HashMap<>();
  private final Map<String, Map<String, Map<String, Double>>> rawScores = new HashMap<>();
  private final SimilarityFunctions similarityFunctions = new SimilarityFunctions();

  public GetSystemSimilarityDataSourcesReactor() {
    this.keysToGet = new String[] {
        ReactorKeysEnum.DATABASE.getKey(),   // engine UUID (required)
        "systemList",                        // optional system filter
        "systemQuery"                        // optional query filter
    };
    this.keyRequired = new int[] {1, 0, 0};
  }

  /**
   * Main execution logic: orchestrate query execution and result transformation.
   * Extends {@link AbstractProjectReactor#doExecute()} pattern.
   */
  @Override
  protected NounMetadata doExecute() {
    try {
      // ── 1. Parse & validate inputs ───────────────────────────────────────────────
      parseInputs();

      // ── 2. Build bindings clause (used by all queries) ──────────────────────────
      buildBindingsClause();

      // ── 3. Get the canonical system list (used as matrix axes) ──────────────────
      fetchAllSystems();

      // Legacy-play sheet compatible chart state.
      paramDataHash.clear();
      keyHash.clear();
      rawScores.clear();

      // ── 4. Execute queries sequentially and collect results ───────────────────
      Map<String, Map<String, Double>> dataObjectRaw = executeDataObjectQueries();
      Map<String, Map<String, Double>> interfaceRaw = executeSystemInterfaceQueries();
      Map<String, Map<String, Double>> theaterRaw = executeTheaterQuery();
      Map<String, Map<String, Double>> transactionalRaw = executeTransactionalQuery();
      Map<String, Map<String, Double>> businessProcessesRaw = executeBusinessProcessesQuery();
      Map<String, Map<String, Double>> activitiesRaw = executeActivitiesQuery();
      Map<String, Map<String, Double>> usersRaw = executeUsersQuery();

      // Match legacy flow: after each query + similarity function call, transform via processHashForCharting.
      processAndStoreBucket(BUCKET_DATA_OBJ, dataObjectRaw);
      processAndStoreBucket(BUCKET_INTERFACE, interfaceRaw);
      processAndStoreBucket(BUCKET_THEATER, theaterRaw);
      processAndStoreBucket(BUCKET_TRANSACTIONAL, transactionalRaw);
      processAndStoreBucket(BUCKET_BP, businessProcessesRaw);
      processAndStoreBucket(BUCKET_ACT, activitiesRaw);
      processAndStoreBucket(BUCKET_USERS, usersRaw);

      // ── 5. Prune paramDataHash (legacy calculateHash + flattenData behavior) ────
      // Remove pairs from ALL variables if they exist in every variable and
      // their simple average score across all variables is ≤ 50.
      // Skip pruning in DBS label mode so all requested DBS systems retain
      // their computed pairs for downstream charting.
      if (labelsToFilter == null || labelsToFilter.isEmpty()) {
        pruneParamDataHash();
      }

      Map<String, Object> result = new HashMap<>();
      // Keep raw contract keys for downstream compatibility.
      // result.put("dataBLUDataSet", dataBluRaw);
      // result.put("theaterData", theaterRaw);
      // result.put("transactionalData", transactionalRaw);
      // result.put("businessProcessesData", businessProcessesRaw);
      // result.put("activitiesData", activitiesRaw);
      // result.put("userTypesData", usersRaw);
      // result.put("userInterfaceData", uiRaw);

      // New chart-ready payload equivalent to legacy paramDataHash/keyHash behavior.
      result.put("paramDataHash", paramDataHash);
      // result.put("keyHash", keyHash);
      result.put("systemLabelMap", systemLabelMap);
      // result.put("systems", SimilarityChartingUtils.mapSystemsToLabels(allSystems, systemLabelMap));

      // Cache paramDataHash in var-store for downstream reactors (e.g. CompareParamDataHash)
      this.insight.getVarStore().put(
          VARSTORE_PARAM_DATA_HASH,
          new NounMetadata(paramDataHash, PixelDataType.MAP));

      // Cache keyHash in var-store for downstream reactors (e.g. ComputeSimilarityScores)
      this.insight.getVarStore().put(
          VARSTORE_KEY_HASH,
          new NounMetadata(keyHash, PixelDataType.MAP));

      // Cache systemLabelMap and allSystems in var-store for debugging
      this.insight.getVarStore().put(
          VARSTORE_SYSTEM_LABEL_MAP,
          new NounMetadata(systemLabelMap, PixelDataType.MAP));
      this.insight.getVarStore().put(
          VARSTORE_ALL_SYSTEMS,
          new NounMetadata(allSystems, PixelDataType.MAP));
      this.insight.getVarStore().put(
          VARSTORE_RAW_SCORES,
          new NounMetadata(rawScores, PixelDataType.MAP));

        // Cache DBS-mode state for downstream reactors (e.g. ComputeSimilarityScores).
        boolean dbsMode = labelsToFilter != null && !labelsToFilter.isEmpty();
        this.insight.getVarStore().put(
          VARSTORE_DBS_MODE,
          new NounMetadata(dbsMode, PixelDataType.BOOLEAN));

      return new NounMetadata(result, PixelDataType.MAP);

    } catch (Exception e) {
      throw new RuntimeException("Reactor execution failed: " + e.getMessage(), e);
    }
  }

  /**
   * Parse incoming pixel parameters: database, systemList, systemQuery.
   * Store in instance variables for use by query functions.
   */
  private void parseInputs() {
    // ── Read database/engine ID ──────────────────────────────────────────────
    engineId = this.keyValue.get(ReactorKeysEnum.DATABASE.getKey());
    if (engineId == null || engineId.trim().isEmpty()) {
      engineId = DEFAULT_ENGINE_ID;
    }

    // ── Read systemList (optional) ───────────────────────────────────────────
    systemList = getListParam("systemList");

    // ── Read systemQuery (optional alternative) ──────────────────────────────
    systemQuery = getStringParam("systemQuery");

    // Validate: only one of systemList or systemQuery should be set
    if (systemList != null && !systemList.isEmpty() && systemQuery != null && !systemQuery.isEmpty()) {
      systemQuery = null;
    }
  }

  /**
   * Build the SPARQL BINDINGS clause to be appended to each query.
   * Uses systemList if provided; otherwise uses default SystemUser bindings.
   *
   * <p>If {@code systemList} contains plain labels (not absolute URIs), the values cannot
   * be placed directly into a SPARQL BINDINGS clause as IRIs.  In that case the method
   * falls back to the default SystemUser bindings so that all systems are returned by
   * the query, and stores the labels in {@code labelsToFilter} so that
   * {@link #fetchAllSystems()} can restrict the comparison list after the label map is built.
   */
  private void buildBindingsClause() {
    if (systemList != null && !systemList.isEmpty()) {
      // Determine whether the caller supplied absolute URIs or plain labels.
      // An absolute URI must start with a scheme such as "http://" or "https://".
      boolean allAbsoluteUris = true;
      for (String value : systemList) {
        if (value == null || (!value.startsWith("http://") && !value.startsWith("https://"))) {
          allAbsoluteUris = false;
          break;
        }
      }

      if (allAbsoluteUris) {
        // Build BINDINGS ?System {(<uri1>)(<uri2>)...}
        StringBuilder sb = new StringBuilder("BINDINGS ?System {");
        for (String systemUri : systemList) {
          sb.append("(<").append(systemUri).append(">)");
        }
        sb.append("}");
        bindingsClause = sb.toString();
      } else {
        // Labels received (e.g. "JOMIS", "DODTR") — cannot be used as SPARQL IRIs.
        // Fall back to default SystemUser bindings and post-filter by label in fetchAllSystems.
        labelsToFilter = new ArrayList<>(systemList);
        bindingsClause = DEFAULT_SYSTEM_USER_BINDINGS;
      }
    } else if (systemQuery != null && !systemQuery.isEmpty()) {
      // Use systemQuery as-is (caller has provided the full BINDINGS/VALUES clause)
      bindingsClause = systemQuery;
    } else {
      // Use default SystemUser bindings
      bindingsClause = DEFAULT_SYSTEM_USER_BINDINGS;
    }
  }

  /**
   * Execute the default systems query to build the canonical system list used as matrix axes.
   * This query must run first so we have the full list of systems.
   */
  private void fetchAllSystems() {
    String defaultSystemsQuery =
        "SELECT DISTINCT ?System WHERE {"
        + "{?System <http://www.w3.org/1999/02/22-rdf-syntax-ns#type> "
        + "<http://semoss.org/ontologies/Concept/System>}"
        + "{?System ?UsedBy ?SystemUser}"
        + "}";
    defaultSystemsQuery = appendBindings(defaultSystemsQuery);

    allSystems = similarityFunctions.createComparisonObjectList(engineId, defaultSystemsQuery);
    systemLabelMap = SimilarityChartingUtils.buildSystemLabelMap(allSystems);

    // When plain labels were supplied (e.g. DBS system list), ensure allSystems includes all
    // requested labels, even if some have no matching data in the RDF repository.  This must
    // happen AFTER buildSystemLabelMap so the reverse-lookup is available.  Systems without
    // data will appear in the heatmap but have no similarity scores.
    if (labelsToFilter != null && !labelsToFilter.isEmpty()) {
      // Normalise requested labels to lower-case for case-insensitive matching
      Set<String> requestedLower = new HashSet<>();
      Map<String, String> requestedOriginal = new HashMap<>(); // preserve original casing
      for (String lbl : labelsToFilter) {
        requestedLower.add(lbl.toLowerCase());
        requestedOriginal.put(lbl.toLowerCase(), lbl);
      }

      // Find which requested labels have matching URIs in the database
      Set<String> matchedLabels = new HashSet<>();
      List<String> filteredSystems = new ArrayList<>();
      for (String uri : allSystems) {
        String label = systemLabelMap.get(uri);
        if (label != null && requestedLower.contains(label.toLowerCase())) {
          filteredSystems.add(uri);
          matchedLabels.add(label.toLowerCase());
        }
      }

      // For any requested labels that have no matching data in the RDF repository, create
      // synthetic URIs so they still appear in the heatmap matrix as empty rows/columns.
      // This ensures UI consistency: the user requested these systems, so they should all appear.
      for (String requestedLabel : requestedLower) {
        if (!matchedLabels.contains(requestedLabel)) {
          String originalLabel = requestedOriginal.get(requestedLabel);
          String syntheticUri = "http://semoss.org/temp/system/" + originalLabel;
          filteredSystems.add(syntheticUri);
          systemLabelMap.put(syntheticUri, originalLabel);
        }
      }

      allSystems = filteredSystems;
    }

    similarityFunctions.setComparisonObjectList(allSystems);
    // LOGGER.info("Found " + allSystems.size() + " systems for analysis");
  }

  /**
   * Applies chart-shaping transform equivalent to legacy processHashForCharting and stores by bucket.
   */
  private void processAndStoreBucket(String bucketName, Map<String, Map<String, Double>> rawBucketData) {
    // Cache raw scores (pre-chart-transform) for debug probing
    if (rawBucketData != null && !rawBucketData.isEmpty()) {
      rawScores.put(bucketName, rawBucketData);
    }

    Map<String, Map<String, Object>> chartData = SimilarityChartingUtils.processHashForCharting(
        rawBucketData,
        keyHash,
        "System1",
        "System2",
        systemLabelMap);

    if (chartData != null && !chartData.isEmpty()) {
      paramDataHash.put(bucketName, chartData);
    }
  }

  /**
   * Prunes paramDataHash to match legacy calculateHash + flattenData behavior.
   *
   * <p>Legacy flow:
   * <ol>
   *   <li>{@code calculateHash}: iterates pair keys starting from the smallest
   *       variable. For each pair key, if it exists in ALL variables, computes
   *       the simple average score ({@code sum(Score_i) / totalVars}).</li>
   *   <li>{@code flattenData}: for each pair in the calculateHash output, if
   *       average score ≤ 50, calls {@code clearParamDataHash(key)} which
   *       removes that pair key from EVERY variable.</li>
   * </ol>
   *
   * <p>Only pairs that exist in ALL variables AND have avg ≤ 50 are removed.
   * Pairs that exist in only some variables are left untouched.
   */
  private void pruneParamDataHash() {
    if (paramDataHash.isEmpty()) {
      return;
    }

    int totalVars = paramDataHash.size();
    if (totalVars == 0) {
      return;
    }

    // Find intersection of pair keys across ALL variables
    Set<String> intersection = null;
    for (Map<String, Map<String, Object>> varPairs : paramDataHash.values()) {
      if (intersection == null) {
        intersection = new HashSet<>(varPairs.keySet());
      } else {
        intersection.retainAll(varPairs.keySet());
      }
    }

    if (intersection == null || intersection.isEmpty()) {
      return;
    }

    // For each pair in the intersection, compute average score across all vars.
    // If avg <= 50, mark for removal from ALL variables.
    List<String> toRemove = new ArrayList<>();
    for (String pairKey : intersection) {
      double sum = 0.0;
      for (Map<String, Map<String, Object>> varPairs : paramDataHash.values()) {
        Map<String, Object> cell = varPairs.get(pairKey);
        Object scoreObj = cell != null ? cell.get("Score") : null;
        double varScore = scoreObj instanceof Number ? ((Number) scoreObj).doubleValue() : 0.0;
        sum += varScore;
      }
      double score = sum / totalVars;
      if (score <= 50.0) {
        toRemove.add(pairKey);
      }
    }

    // Remove from ALL variables (matches legacy clearParamDataHash behavior)
    for (String pairKey : toRemove) {
      for (Map<String, Map<String, Object>> varPairs : paramDataHash.values()) {
        varPairs.remove(pairKey);
      }
    }
  }

  /**
   * Execute Business Processes query and convert to per-system value sets.
   * Returns: Map<System, Set<BusinessProcessURI>>
   */
  private Map<String, Map<String, Double>> executeBusinessProcessesQuery() {
    String query =
        "SELECT DISTINCT ?System ?BusinessProcess WHERE {"
        + "{?System <http://www.w3.org/1999/02/22-rdf-syntax-ns#type> "
        + "<http://semoss.org/ontologies/Concept/System>}"
        + "{?BusinessProcess <http://www.w3.org/1999/02/22-rdf-syntax-ns#type> "
        + "<http://semoss.org/ontologies/Concept/BusinessProcess>}"
        + "{?System <http://semoss.org/ontologies/Relation/Supports> ?BusinessProcess}"
        + "{?System ?UsedBy ?SystemUser}"
        + "}";
    query = appendBindings(query);

    return similarityFunctions.compareObjectParameterScore(
      engineId,
      query,
      SimilarityFunctions.VALUE);
  }

  /**
   * Execute Activities query and convert to per-system value sets.
   * Returns: Map<System, Set<ActivityURI>>
   */
  private Map<String, Map<String, Double>> executeActivitiesQuery() {
    String query =
        "SELECT DISTINCT ?System ?Activity WHERE {"
        + "{?System <http://www.w3.org/1999/02/22-rdf-syntax-ns#type> "
        + "<http://semoss.org/ontologies/Concept/System>}"
        + "{?Activity <http://www.w3.org/1999/02/22-rdf-syntax-ns#type> "
        + "<http://semoss.org/ontologies/Concept/Activity>}"
        + "{?System <http://semoss.org/ontologies/Relation/Supports> ?Activity}"
        + "{?System ?UsedBy ?SystemUser}"
        + "}";
    query = appendBindings(query);

    return similarityFunctions.compareObjectParameterScore(
      engineId,
      query,
      SimilarityFunctions.VALUE);
  }

  /**
   * Execute DataObject query and compute simple set-overlap pairwise scores 
   * (same method as most other categories)
   * removed CRM weighting 
   */
  private Map<String, Map<String, Double>> executeDataObjectQueries() {
    String query =
        "SELECT DISTINCT ?System ?Data WHERE {"
        + "{?System <http://www.w3.org/1999/02/22-rdf-syntax-ns#type> "
        + "<http://semoss.org/ontologies/Concept/System>}"
        + "{?Data <http://www.w3.org/1999/02/22-rdf-syntax-ns#type> "
        + "<http://semoss.org/ontologies/Concept/DataObject>}"
        + "{?Provide <http://www.w3.org/2000/01/rdf-schema#subPropertyOf> "
        + "<http://semoss.org/ontologies/Relation/Provide>}"
        + "{?System ?Provide ?Data}"
        + "{?System ?UsedBy ?SystemUser}"
        + "}";
    query = appendBindings(query);

    return similarityFunctions.compareObjectParameterScore(
        engineId,
        query,
        SimilarityFunctions.VALUE);
  }


  /**
   * Execute SystemInterface queries and compute simple set-overlap pairwise scores.
   * Captures both directions of the interface relationship:
   * - System Provides SystemInterface
   * - SystemInterface Consumes System
   */
  private Map<String, Map<String, Double>> executeSystemInterfaceQueries() {
    String query =
        "SELECT DISTINCT ?System ?SystemInterface WHERE {"
        + "{?System <http://www.w3.org/1999/02/22-rdf-syntax-ns#type> "
        + "<http://semoss.org/ontologies/Concept/System>}"
        + "{?SystemInterface <http://www.w3.org/1999/02/22-rdf-syntax-ns#type> "
        + "<http://semoss.org/ontologies/Concept/SystemInterface>}"
        + "{{?System <http://semoss.org/ontologies/Relation/Provide> ?SystemInterface}"
        + " UNION "
        + "{?SystemInterface <http://semoss.org/ontologies/Relation/Consume> ?System}}"
        + "{?System ?UsedBy ?SystemUser}"
        + "}";
    query = appendBindings(query);

    return similarityFunctions.compareObjectParameterScore(
        engineId,
        query,
        SimilarityFunctions.VALUE);
  }

  /**
   * Execute Theater/Garrison deployment query and convert results.
   * Returns: Map<System, Set<TheaterValue>>
   */
  private Map<String, Map<String, Double>> executeTheaterQuery() {
    String query =
        "SELECT DISTINCT ?System ?Theater WHERE {"
        + "{?System <http://www.w3.org/1999/02/22-rdf-syntax-ns#type> "
        + "<http://semoss.org/ontologies/Concept/System>}"
        + "{?System <http://semoss.org/ontologies/Relation/Contains/GarrisonTheater> ?Theater}"
        + "{?System ?UsedBy ?SystemUser}"
        + "}";
    query = appendBindings(query);

    return similarityFunctions.stringCompareBinaryResultGetter(
      engineId,
      query,
      "Theater",
      "Garrison",
      "Both");
  }

  /**
   * Execute Transactional (Yes/No) query and convert results.
   * Returns: Map<System, Set<TransactionalValue>>
   */
  private Map<String, Map<String, Double>> executeTransactionalQuery() {
    String query =
        "SELECT DISTINCT ?System ?Trans WHERE {"
        + "{?System <http://www.w3.org/1999/02/22-rdf-syntax-ns#type> "
        + "<http://semoss.org/ontologies/Concept/System>}"
        + "{?System <http://semoss.org/ontologies/Relation/Contains/Transactional> ?Trans}"
        + "{?System ?UsedBy ?SystemUser}"
        + "}";
    query = appendBindings(query);

    return similarityFunctions.stringCompareBinaryResultGetter(
      engineId,
      query,
      "Yes",
      "No",
      "Both");
  }

  /**
   * Execute Users/Personnel query and convert results.
   * Returns: Map<System, Set<PersonnelURI>>
   */
  private Map<String, Map<String, Double>> executeUsersQuery() {
    String query =
        "SELECT DISTINCT ?System ?Personnel WHERE {"
        + "{?System <http://www.w3.org/1999/02/22-rdf-syntax-ns#type> "
        + "<http://semoss.org/ontologies/Concept/System>}"
        + "{?Personnel <http://www.w3.org/1999/02/22-rdf-syntax-ns#type> "
        + "<http://semoss.org/ontologies/Concept/Personnel>}"
        + "{?System <http://semoss.org/ontologies/Relation/UsedBy> ?Personnel}"
        + "{?System ?UsedBy ?SystemUser}"
        + "}";
    query = appendBindings(query);

    return similarityFunctions.compareObjectParameterScore(
      engineId,
      query,
      SimilarityFunctions.VALUE);
  }

  // ──────────────────────────────────────────────────────────────────────────────
  // ───── HELPER METHODS ─────────────────────────────────────────────────────────
  // ──────────────────────────────────────────────────────────────────────────────

  /**
   * Append the bindings clause to a SPARQL query template.
   * Port of legacy SysSimHeatMapSheet.addBindings().
   *
   * @param query SPARQL SELECT query (without bindings)
   * @return query with bindings clause appended
   */
  private String appendBindings(String query) {
    if (query == null) {
      return bindingsClause;
    }
    return query + " " + bindingsClause;
  }

  /**
   * Execute a SPARQL SELECT query and return raw rows.
   * Delegates to QueryExecutor which wraps WrapperManager/engine query execution.
   *
   * @param engineId UUID of RDF engine to query
   * @param query SPARQL SELECT query string
   * @return List of result rows as Map<varName, varValue>
   * @throws RuntimeException if query execution fails
   */
  private List<Map<String, String>> executeSelect(String engineId, String query) {
    QueryExecutor executor = new QueryExecutor(engineId);
    return executor.executeSelect(query);
  }

  /**
   * Convert SPARQL SELECT result rows to canonical Map<System, Set<Values>> format.
   * Groups multiple rows per system URI into a single set of value URIs.
   *
   * @param rows SPARQL result rows
   * @param systemVarName variable name in query that holds system URI (e.g., "System")
   * @param valueVarName variable name in query that holds value URI (e.g., "BusinessProcess")
   * @return Map<SystemURI, Set<ValueURI>>
   */
  private Map<String, Set<String>> rowsToSystemValueSet(
      List<Map<String, String>> rows,
      String systemVarName,
      String valueVarName) {

    Map<String, Set<String>> result = new HashMap<>();

    for (Map<String, String> row : rows) {
      String systemUri = row.get(systemVarName);
      String valueUri = row.get(valueVarName);

      if (systemUri != null && valueUri != null && !systemUri.isEmpty() && !valueUri.isEmpty()) {
        result.computeIfAbsent(systemUri, k -> new HashSet<>()).add(valueUri);
      }
    }

    // Ensure all systems are present in map (even if empty)
    for (String system : allSystems) {
      result.putIfAbsent(system, new HashSet<>());
    }

    return result;
  }

  /**
   * Retrieve a List parameter from the reactor's input.
   *
   * @param paramName parameter key
   * @return List<String> or null if not found
   */
  @SuppressWarnings("unchecked")
  private List<String> getListParam(String paramName) {
    GenRowStruct grs = this.store.getGenRowStruct(paramName);
    if (grs != null && !grs.isEmpty()) {
      // Primary path: support pixel list-like inputs represented as string values.
      List<String> strValues = grs.getAllStrValues();
      if (strValues != null && !strValues.isEmpty()) {
        return strValues;
      }

      // Fallback path: support callers that send a Java List as the first noun value.
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
   *
   * @param paramName parameter key
   * @return String value or null if not found
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
}
