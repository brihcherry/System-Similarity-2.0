package reactors.debug;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import prerna.om.Insight;
import prerna.om.InsightStore;
import prerna.om.OldInsight;
import prerna.sablecc2.om.PixelDataType;
import prerna.sablecc2.om.PixelOperationType;
import prerna.sablecc2.om.nounmeta.NounMetadata;
import prerna.ui.components.api.IPlaySheet;
import reactors.AbstractProjectReactor;
import reactors.examples.GetSystemSimilarityDataSourcesReactor;
import reactors.examples.GetSystemSimilarityHeatmapReactor;

/**
 * Compares the new reactor's paramDataHash (from var-store) against the
 * legacy playsheet's paramDataHash (via reflection) at the <b>score level</b>.
 *
 * <p>Both the new reactor ({@code GetSystemSimilarityDataSources}) and the legacy
 * playsheet produce paramDataHash with hyphen-delimited pair keys (e.g.
 * {@code "CHCS-AHLTA"}) and {@code {"Score": number}} cell values. This reactor
 * compares them directly — no key translation is needed.
 *
 * <h3>Prerequisites</h3>
 * <ol>
 *   <li>Legacy cache must be populated — run {@code GetSystemSimilarityHeatmap} first</li>
 *   <li>New reactor must have run — call {@code GetSystemSimilarityDataSources}
 *       so its paramDataHash is cached in the var-store</li>
 * </ol>
 *
 * <h3>Pixel call</h3>
 * <pre>CompareParamDataHash();</pre>
 */
@SuppressWarnings("deprecation")
public class CompareParamDataHashReactor extends AbstractProjectReactor {

  private static final int MAX_SAMPLE_MISMATCHES = 25;
  private static final int MAX_SAMPLE_ONLY = 10;
  private static final double DEFAULT_TOLERANCE = 0.01;

  public CompareParamDataHashReactor() {
    this.keysToGet = new String[] {};
    this.keyRequired = new int[] {};
  }

  @Override
  @SuppressWarnings("unchecked")
  protected NounMetadata doExecute() {

    // ── 1. Read the NEW paramDataHash from var-store ─────────────────────────
    NounMetadata newHashNoun = this.insight.getVarStore()
        .get(GetSystemSimilarityDataSourcesReactor.VARSTORE_PARAM_DATA_HASH);

    if (newHashNoun == null) {
      throw new IllegalStateException(
          "No new paramDataHash found in var-store. "
          + "Run GetSystemSimilarityDataSources first.");
    }

    Map<String, Map<String, Map<String, Object>>> newHash =
        (Map<String, Map<String, Map<String, Object>>>) newHashNoun.getValue();

    // ── 2. Read the LEGACY paramDataHash from playsheet cache ────────────────
    NounMetadata cachedIdNoun = this.insight.getVarStore()
        .get(GetSystemSimilarityHeatmapReactor.CACHED_INSIGHT_ID_KEY);

    if (cachedIdNoun == null) {
      throw new IllegalStateException(
          "No cached insight ID found. "
          + "Run GetSystemSimilarityHeatmap (Reload Heatmap Cache) first.");
    }

    String cachedInsightId = cachedIdNoun.getValue().toString();
    Insight cachedInsight = InsightStore.getInstance().get(cachedInsightId);

    if (cachedInsight == null || !(cachedInsight instanceof OldInsight)) {
      throw new IllegalStateException(
          "Cached insight not found or not an OldInsight. "
          + "Re-run GetSystemSimilarityHeatmap.");
    }

    OldInsight oldInsight = (OldInsight) cachedInsight;
    IPlaySheet playSheet = oldInsight.getPlaySheet();

    if (playSheet == null) {
      throw new IllegalStateException("Cached OldInsight has no playsheet.");
    }

    Object rawLegacyHash = extractField(playSheet, "paramDataHash");
    if (rawLegacyHash == null) {
      throw new IllegalStateException(
          "Legacy paramDataHash field is null on the playsheet.");
    }

    Map<?, ?> legacyHashRaw = (Map<?, ?>) rawLegacyHash;

    // ── 3. Compare per variable ──────────────────────────────────────────────
    // Both sides use hyphen-delimited pair keys ("SYS_A-SYS_B") and
    // {"Score": number} cell values, so we compare directly.
    double tolerance = DEFAULT_TOLERANCE;
    List<Map<String, Object>> variableResults = new ArrayList<>();
    int totalMatches = 0;
    int totalMismatches = 0;
    int totalOnlyInNew = 0;
    int totalOnlyInLegacy = 0;
    int totalNewPairs = 0;
    int totalLegacyPairs = 0;

    // Collect all variable names from both sides
    Set<String> allVars = new LinkedHashSet<>();
    allVars.addAll(newHash.keySet());
    for (Object key : legacyHashRaw.keySet()) {
      allVars.add(String.valueOf(key));
    }

    for (String variable : allVars) {

      Map<String, Map<String, Object>> newVarPairs = newHash.get(variable);
      if (newVarPairs == null) {
        newVarPairs = Collections.emptyMap();
      }

      Object legacyVarObj = legacyHashRaw.get(variable);

      // Build legacy scores map (extract score from each pair value)
      Map<String, Double> legacyScores = new LinkedHashMap<>();
      if (legacyVarObj instanceof Map) {
        for (Map.Entry<?, ?> entry : ((Map<?, ?>) legacyVarObj).entrySet()) {
          String pairKey = String.valueOf(entry.getKey());
          legacyScores.put(pairKey, extractScore(entry.getValue()));
        }
      }

      // Build new scores map
      Map<String, Double> newScores = new LinkedHashMap<>();
      for (Map.Entry<String, Map<String, Object>> entry : newVarPairs.entrySet()) {
        newScores.put(entry.getKey(), extractScore(entry.getValue()));
      }

      // ── Compare scores ─────────────────────────────────────────────────────
      int matchCount = 0;
      int mismatchCount = 0;
      int onlyInNewCount = 0;
      int onlyInLegacyCount = 0;
      List<Map<String, Object>> sampleMismatches = new ArrayList<>();
      List<Map<String, Object>> sampleOnlyInNew = new ArrayList<>();
      List<Map<String, Object>> sampleOnlyInLegacy = new ArrayList<>();

      for (Map.Entry<String, Double> entry : newScores.entrySet()) {
        String pairKey = entry.getKey();
        double newScore = entry.getValue();
        Double legacyScore = legacyScores.get(pairKey);

        if (legacyScore == null) {
          onlyInNewCount++;
          if (sampleOnlyInNew.size() < MAX_SAMPLE_ONLY) {
            Map<String, Object> sample = new LinkedHashMap<>();
            sample.put("pair", pairKey);
            sample.put("score", newScore);
            sampleOnlyInNew.add(sample);
          }
        } else if (Math.abs(newScore - legacyScore) <= tolerance) {
          matchCount++;
        } else {
          mismatchCount++;
          if (sampleMismatches.size() < MAX_SAMPLE_MISMATCHES) {
            Map<String, Object> sample = new LinkedHashMap<>();
            sample.put("pair", pairKey);
            sample.put("newScore", newScore);
            sample.put("legacyScore", legacyScore);
            sample.put("diff", Math.abs(newScore - legacyScore));
            sampleMismatches.add(sample);
          }
        }
      }

      for (Map.Entry<String, Double> entry : legacyScores.entrySet()) {
        if (!newScores.containsKey(entry.getKey())) {
          onlyInLegacyCount++;
          if (sampleOnlyInLegacy.size() < MAX_SAMPLE_ONLY) {
            Map<String, Object> sample = new LinkedHashMap<>();
            sample.put("pair", entry.getKey());
            sample.put("score", entry.getValue());
            sampleOnlyInLegacy.add(sample);
          }
        }
      }

      // Sort mismatches by largest absolute diff first
      sampleMismatches.sort((a, b) ->
          Double.compare((Double) b.get("diff"), (Double) a.get("diff")));

      Map<String, Object> varResult = new LinkedHashMap<>();
      varResult.put("variable", variable);
      varResult.put("newPairCount", newScores.size());
      varResult.put("legacyPairCount", legacyScores.size());
      varResult.put("matchCount", matchCount);
      varResult.put("mismatchCount", mismatchCount);
      varResult.put("onlyInNewCount", onlyInNewCount);
      varResult.put("onlyInLegacyCount", onlyInLegacyCount);
      varResult.put("allMatch",
          mismatchCount == 0 && onlyInNewCount == 0 && onlyInLegacyCount == 0);
      varResult.put("sampleMismatches", sampleMismatches);
      varResult.put("sampleOnlyInNew", sampleOnlyInNew);
      varResult.put("sampleOnlyInLegacy", sampleOnlyInLegacy);
      variableResults.add(varResult);

      totalMatches += matchCount;
      totalMismatches += mismatchCount;
      totalOnlyInNew += onlyInNewCount;
      totalOnlyInLegacy += onlyInLegacyCount;
      totalNewPairs += newScores.size();
      totalLegacyPairs += legacyScores.size();
    }

    // ── 4. Build response ────────────────────────────────────────────────────
    Map<String, Object> result = new LinkedHashMap<>();
    result.put("tolerance", tolerance);
    result.put("variableCount", allVars.size());
    result.put("allMatch",
        totalMismatches == 0 && totalOnlyInNew == 0 && totalOnlyInLegacy == 0);
    result.put("totalNewPairs", totalNewPairs);
    result.put("totalLegacyPairs", totalLegacyPairs);
    result.put("totalMatches", totalMatches);
    result.put("totalMismatches", totalMismatches);
    result.put("totalOnlyInNew", totalOnlyInNew);
    result.put("totalOnlyInLegacy", totalOnlyInLegacy);
    result.put("variables", variableResults);

    return new NounMetadata(result, PixelDataType.MAP, PixelOperationType.OPERATION);
  }

  // ── Helpers ────────────────────────────────────────────────────────────────

  /**
   * Extracts a numeric score from a pair value object.
   * Handles both {@code Map<"Score", Number>} and raw {@code Number} values.
   */
  private static double extractScore(Object pairValue) {
    if (pairValue instanceof Map) {
      Object scoreObj = ((Map<?, ?>) pairValue).get("Score");
      if (scoreObj instanceof Number) {
        return ((Number) scoreObj).doubleValue();
      }
    }
    if (pairValue instanceof Number) {
      return ((Number) pairValue).doubleValue();
    }
    return 0.0;
  }

  /**
   * Walks the class hierarchy to find and read a field by name.
   */
  private static Object extractField(Object target, String fieldName) {
    Class<?> current = target.getClass();
    while (current != null && current != Object.class) {
      try {
        Field field = current.getDeclaredField(fieldName);
        field.setAccessible(true);
        return field.get(target);
      } catch (NoSuchFieldException e) {
        current = current.getSuperclass();
      } catch (IllegalAccessException e) {
        throw new RuntimeException(
            "Cannot access field '" + fieldName + "' on " + current.getName(), e);
      }
    }
    return null;
  }
}
