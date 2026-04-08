package reactors.debug;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

import prerna.sablecc2.PixelRunner;
import prerna.sablecc2.om.PixelDataType;
import prerna.sablecc2.om.PixelOperationType;
import prerna.sablecc2.om.nounmeta.NounMetadata;
import reactors.AbstractProjectReactor;

/**
 * Validates the output of {@code ComputeSimilarityScores} against the live
 * output of {@code RefreshSystemSimilarityHeatmap} (the legacy reactor).
 *
 * <p>Both reactors are called with no parameters (all variables, no minimum
 * filters). Their {@code [System1, System2, Score]} data arrays are compared
 * using {@code "System1|System2"} directional pair keys.
 *
 * <h3>Prerequisites</h3>
 * <ul>
 *   <li>Legacy cache populated — run {@code GetSystemSimilarityHeatmap} first</li>
 *   <li>New pipeline warm — run {@code GetSystemSimilarityDataSources} first</li>
 * </ul>
 *
 * <h3>Pixel call</h3>
 * <pre>
 *   ValidateHeatmapOutput();
 *   ValidateHeatmapOutput(tolerance=[0.5]);
 * </pre>
 */
public class ValidateHeatmapOutputReactor extends AbstractProjectReactor {

  private static final double DEFAULT_TOLERANCE = 0.01;

  public ValidateHeatmapOutputReactor() {
    this.keysToGet = new String[] { "tolerance" };
    this.keyRequired = new int[] { 0 };
  }

  @Override
  @SuppressWarnings("unchecked")
  protected NounMetadata doExecute() {

    // ── 1. Parse optional tolerance ──────────────────────────────────────────
    double tolerance = DEFAULT_TOLERANCE;
    String tolStr = this.keyValue.get("tolerance");
    if (tolStr != null && !tolStr.trim().isEmpty()) {
      tolerance = Double.parseDouble(tolStr);
    }

    // ── 2. Run legacy reactor and extract data rows ──────────────────────────
    PixelRunner legacyRunner = this.insight.runPixel("RefreshSystemSimilarityHeatmap();");
    List<NounMetadata> legacyResults = legacyRunner.getResults();
    if (legacyResults == null || legacyResults.isEmpty()) {
      throw new IllegalStateException(
          "RefreshSystemSimilarityHeatmap returned no results.");
    }
    Object legacyVal = legacyResults.get(0).getValue();
    List<List<Object>> legacyRows = extractRows(legacyVal, "RefreshSystemSimilarityHeatmap");

    // ── 3. Run new reactor and extract data rows ─────────────────────────────
    PixelRunner newRunner = this.insight.runPixel("ComputeSimilarityScores();");
    List<NounMetadata> newResults = newRunner.getResults();
    if (newResults == null || newResults.isEmpty()) {
      throw new IllegalStateException(
          "ComputeSimilarityScores returned no results.");
    }
    Object newVal = newResults.get(0).getValue();
    List<List<Object>> newRows = extractRows(newVal, "ComputeSimilarityScores");

    // ── 4. Build score maps keyed by "System1|System2" ───────────────────────
    Map<String, Double> legacyMap = buildScoreMap(legacyRows);
    Map<String, Double> newMap = buildScoreMap(newRows);

    // ── 5. Compare ───────────────────────────────────────────────────────────
    int matchCount = 0;
    List<Map<String, Object>> mismatches = new ArrayList<>();
    List<Map<String, Object>> onlyInNew = new ArrayList<>();
    List<Map<String, Object>> onlyInLegacy = new ArrayList<>();

    TreeSet<String> allKeys = new TreeSet<>();
    allKeys.addAll(newMap.keySet());
    allKeys.addAll(legacyMap.keySet());

    for (String key : allKeys) {
      Double newScore = newMap.get(key);
      Double legScore = legacyMap.get(key);

      if (newScore != null && legScore != null) {
        if (Math.abs(newScore - legScore) <= tolerance) {
          matchCount++;
        } else {
          Map<String, Object> m = new LinkedHashMap<>();
          m.put("pair", key);
          m.put("newScore", newScore);
          m.put("legacyScore", legScore);
          m.put("diff", newScore - legScore);
          mismatches.add(m);
        }
      } else if (newScore != null) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("pair", key);
        m.put("newScore", newScore);
        onlyInNew.add(m);
      } else {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("pair", key);
        m.put("legacyScore", legScore);
        onlyInLegacy.add(m);
      }
    }

    // ── 6. Build response ────────────────────────────────────────────────────
    int detailLimit = 100;

    Map<String, Object> result = new LinkedHashMap<>();
    result.put("tolerance", tolerance);
    result.put("newPairCount", newMap.size());
    result.put("legacyPairCount", legacyMap.size());
    result.put("matchCount", matchCount);
    result.put("mismatchCount", mismatches.size());
    result.put("onlyInNewCount", onlyInNew.size());
    result.put("onlyInLegacyCount", onlyInLegacy.size());
    result.put("mismatches", mismatches.subList(0, Math.min(mismatches.size(), detailLimit)));
    result.put("onlyInNew", onlyInNew.subList(0, Math.min(onlyInNew.size(), detailLimit)));
    result.put("onlyInLegacy", onlyInLegacy.subList(0, Math.min(onlyInLegacy.size(), detailLimit)));

    if (mismatches.size() > detailLimit) result.put("mismatchesTruncated", true);
    if (onlyInNew.size() > detailLimit)  result.put("onlyInNewTruncated", true);
    if (onlyInLegacy.size() > detailLimit) result.put("onlyInLegacyTruncated", true);

    return new NounMetadata(result, PixelDataType.MAP, PixelOperationType.OPERATION);
  }

  // ── Helpers ────────────────────────────────────────────────────────────────

  /**
   * Extracts the {@code [[System1, System2, Score], ...]} data array from a
   * reactor result value. Accepts either a Map with a "data" key or a raw List.
   */
  @SuppressWarnings("unchecked")
  private static List<List<Object>> extractRows(Object val, String reactorName) {
    if (val instanceof Map) {
      Object data = ((Map<?, ?>) val).get("data");
      if (data instanceof List) {
        return (List<List<Object>>) data;
      }
    }
    if (val instanceof List) {
      return (List<List<Object>>) val;
    }
    throw new IllegalStateException(
        reactorName + " returned unexpected data shape: "
        + (val == null ? "null" : val.getClass().getName()));
  }

  /**
   * Converts {@code [[System1, System2, Score], ...]} rows into a
   * {@code "System1|System2" → score} map.
   */
  private static Map<String, Double> buildScoreMap(List<List<Object>> rows) {
    Map<String, Double> map = new LinkedHashMap<>();
    for (List<Object> row : rows) {
      if (row == null || row.size() < 3) continue;
      String sys1 = String.valueOf(row.get(0));
      String sys2 = String.valueOf(row.get(1));
      double score = ((Number) row.get(2)).doubleValue();
      map.put(sys1 + "|" + sys2, score);
    }
    return map;
  }
}
