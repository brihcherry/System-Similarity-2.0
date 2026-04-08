package reactors.examples;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

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

  private static final Logger LOGGER = LogManager.getLogger(GetSystemSimilarityDataSourcesReactor.class);

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

  /** Bucket name constants matching legacy playsheet paramDataHash keys. */
  private static final String BUCKET_BP = "Business_Processes_Supported";
  private static final String BUCKET_ACT = "Activities_Supported";
  private static final String BUCKET_DATA_BLU = "Data_and_Business_Logic_Supported";
  private static final String BUCKET_THEATER = "Deployment_(Theater/Garrison)";
  private static final String BUCKET_TRANSACTIONAL = "Transactional_(Yes/No)";
  private static final String BUCKET_USERS = "User_Types";
  private static final String BUCKET_UI = "User_Interface_Types_(PC/Mobile/etc.)";

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
  private Map<String, String> systemLabelMap = new HashMap<>();
  private final Map<String, Map<String, Map<String, Object>>> paramDataHash = new HashMap<>();
  private final Map<String, Map<String, Object>> keyHash = new HashMap<>();
  private final Map<String, Map<String, Map<String, Double>>> rawScores = new HashMap<>();
  private final SimilarityFunctions similarityFunctions = new SimilarityFunctions();

  public GetSystemSimilarityDataSourcesReactor() {
    this.keysToGet = new String[] {
        ReactorKeysEnum.DATABASE.getKey(),  // engine UUID (required)
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

      // ── 4. Execute 7 queries sequentially and collect results ──────────────────
      Map<String, Map<String, Double>> dataBluRaw = executeDataBLUQueries();
      Map<String, Map<String, Double>> theaterRaw = executeTheaterQuery();
      Map<String, Map<String, Double>> transactionalRaw = executeTransactionalQuery();
      Map<String, Map<String, Double>> businessProcessesRaw = executeBusinessProcessesQuery();
      Map<String, Map<String, Double>> activitiesRaw = executeActivitiesQuery();
      Map<String, Map<String, Double>> usersRaw = executeUsersQuery();
      Map<String, Map<String, Double>> uiRaw = executeUIQuery();

      // Match legacy flow: after each query + similarity function call, transform via processHashForCharting.
      processAndStoreBucket(BUCKET_DATA_BLU, dataBluRaw);
      processAndStoreBucket(BUCKET_THEATER, theaterRaw);
      processAndStoreBucket(BUCKET_TRANSACTIONAL, transactionalRaw);
      processAndStoreBucket(BUCKET_BP, businessProcessesRaw);
      processAndStoreBucket(BUCKET_ACT, activitiesRaw);
      processAndStoreBucket(BUCKET_USERS, usersRaw);
      processAndStoreBucket(BUCKET_UI, uiRaw);

      // ── 5. Prune paramDataHash (legacy calculateHash + flattenData behavior) ────
      // Remove pairs from ALL variables if they exist in every variable and
      // their simple average score across all variables is ≤ 50.
      pruneParamDataHash();

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
   */
  private void buildBindingsClause() {
    if (systemList != null && !systemList.isEmpty()) {
      // Build BINDINGS ?System {(<uri1>)(<uri2>)...}
      StringBuilder sb = new StringBuilder("BINDINGS ?System {");
      for (String systemUri : systemList) {
        sb.append("(<").append(systemUri).append(">)");
      }
      sb.append("}");
      bindingsClause = sb.toString();
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
    similarityFunctions.setComparisonObjectList(allSystems);
    systemLabelMap = SimilarityChartingUtils.buildSystemLabelMap(allSystems);
    // LOGGER.info("[SYS-LIST] Engine ID: {}", engineId);
    // LOGGER.info("[SYS-LIST] Total systems fetched: {}", allSystems.size());
    // LOGGER.info("[SYS-LIST] Systems: {}", allSystems);
    // LOGGER.info("[SYS-LIST] Query used: {}", defaultSystemsQuery);
    System.out.println("[SYS-LIST] Engine ID: " + engineId);
    System.out.println("[SYS-LIST] Total systems fetched: " + allSystems.size());
    System.out.println("[SYS-LIST] Systems: " + allSystems);
    System.out.println("[SYS-LIST] Query used: " + defaultSystemsQuery);
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
      LOGGER.info("[BUCKET:{}] Pair count after chart transform: {}", bucketName, chartData.size());
    } else {
      LOGGER.info("[BUCKET:{}] No chart data produced.", bucketName);
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

    // BEFORE pruning debugging
    int totalPairsBefore = 0;
    for (Map<String, Map<String, Object>> varMap : paramDataHash.values()) {
      totalPairsBefore += varMap.size();
    }
    LOGGER.info("[PRUNING] Total pair entries before: {}", totalPairsBefore);

    // Mirror legacy orderedVars behavior: iterate from smallest variable first.
    List<String> orderedVars = new ArrayList<>(paramDataHash.keySet());
    orderedVars.sort((a, b) -> {
      int sizeCmp = Integer.compare(paramDataHash.get(a).size(), paramDataHash.get(b).size());
      if (sizeCmp != 0) {
        return sizeCmp;
      }
      return a.compareTo(b);
    });

    String minVar = orderedVars.get(0);
    List<String> masterKeys = new ArrayList<>(paramDataHash.get(minVar).keySet());
    if (masterKeys.isEmpty()) {
      LOGGER.info("[PRUNING] No pairs found in intersection; nothing to prune.");
      return;
    }

    LOGGER.info("[PRUNING] Common pair keys candidate count (smallest var): {}", masterKeys.size());

    List<String> toRemove = new ArrayList<>();
    for (String pairKey : masterKeys) {
      double score = 0.0;
      boolean storeCell = true;

      // Legacy-style accumulation order: orderedVars from smallest onward.
      for (String var : orderedVars) {
        Map<String, Object> cell = paramDataHash.get(var).get(pairKey);
        if (cell == null) {
          storeCell = false;
          break;
        }

        Object scoreObj = cell.get("Score");
        if (!(scoreObj instanceof Number)) {
          storeCell = false;
          break;
        }

        double varScore = ((Number) scoreObj).doubleValue();
        score += varScore / totalVars;
      }

      if (!storeCell) {
        continue;
      }

      // Match legacy flattenData gate exactly: keep only when score > 50.
      if (!(score > 50.0)) {
        toRemove.add(pairKey);
      }
    }

    // Remove from ALL variables (matches legacy clearParamDataHash behavior)
    for (String pairKey : toRemove) {
      for (Map<String, Map<String, Object>> varPairs : paramDataHash.values()) {
        varPairs.remove(pairKey);
      }
    }

    // After pruning debugging
    LOGGER.info("[PRUNING] Pairs removed (avg score <= 50): {}", toRemove.size());
    int sampleEnd = Math.min(10, toRemove.size());
    LOGGER.info("[PRUNING] Sample removed pairs: {}", toRemove.subList(0, sampleEnd));

    int totalPairsAfter = 0;
    for (Map<String, Map<String, Object>> varMap : paramDataHash.values()) {
      totalPairsAfter += varMap.size();
    }
    LOGGER.info("[PRUNING] Total pair entries after: {}", totalPairsAfter);
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
  * Execute both Data and BLU queries and return CRM-aware pairwise scores.
  * Returns: Map<System, Map<System, Score>>
   */
  private Map<String, Map<String, Double>> executeDataBLUQueries() {
    // ── Data Query ───────────────────────────────────────────────────────────
    String dataQuery =
        "SELECT DISTINCT ?System ?Data ?CRM WHERE {"
        + "{?System <http://www.w3.org/1999/02/22-rdf-syntax-ns#type> "
        + "<http://semoss.org/ontologies/Concept/System>}"
        + "{?Data <http://www.w3.org/1999/02/22-rdf-syntax-ns#type> "
        + "<http://semoss.org/ontologies/Concept/DataObject>}"
        + "{?Provide <http://www.w3.org/2000/01/rdf-schema#subPropertyOf> "
        + "<http://semoss.org/ontologies/Relation/Provide>}"
        + "{?Provide <http://semoss.org/ontologies/Relation/Contains/CRM> ?CRM}"
        + "{?System ?Provide ?Data}"
        + "{?System ?UsedBy ?SystemUser}"
        + "}";
    dataQuery = appendBindings(dataQuery);

    // ── BLU Query ────────────────────────────────────────────────────────────
    String bluQuery =
        "SELECT DISTINCT ?System ?BLU WHERE {"
        + "{?System <http://www.w3.org/1999/02/22-rdf-syntax-ns#type> "
        + "<http://semoss.org/ontologies/Concept/System>}"
        + "{?BLU <http://www.w3.org/1999/02/22-rdf-syntax-ns#type> "
        + "<http://semoss.org/ontologies/Concept/BusinessLogicUnit>}"
        + "{?System <http://semoss.org/ontologies/Relation/Provide> ?BLU}"
        + "{?System ?UsedBy ?SystemUser}"
        + "}";
    bluQuery = appendBindings(bluQuery);

    // Legacy-equivalent Data/BLU path:
    // 1) createTable(dataQuery) -> data hash with CRM
    // 2) createTable(bluQuery) -> inject BLUs as CRM "C"
    // 3) pairwise CRM-aware similarity matrix
    return similarityFunctions.getDataBLUDataSet(
        engineId,
        dataQuery,
        bluQuery,
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

  /**
   * Execute User Interface query and convert results.
   * Returns: Map<System, Set<UITypeURI>>
   */
  private Map<String, Map<String, Double>> executeUIQuery() {
    String query =
        "SELECT DISTINCT ?System ?UserInterface WHERE {"
        + "{?System <http://www.w3.org/1999/02/22-rdf-syntax-ns#type> "
        + "<http://semoss.org/ontologies/Concept/System>}"
        + "{?UserInterface <http://www.w3.org/1999/02/22-rdf-syntax-ns#type> "
        + "<http://semoss.org/ontologies/Concept/UserInterface>}"
        + "{?System <http://semoss.org/ontologies/Relation/Utilizes> ?UserInterface}"
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
