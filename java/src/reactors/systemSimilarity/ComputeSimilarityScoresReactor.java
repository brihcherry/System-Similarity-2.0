package reactors.systemSimilarity;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import prerna.sablecc2.om.PixelDataType;
import prerna.sablecc2.om.PixelOperationType;
import prerna.sablecc2.om.nounmeta.NounMetadata;
import reactors.AbstractProjectReactor;
import reactors.examples.GetSystemSimilarityDataSourcesReactor;

/**
 * Computes overall similarity scores for every system pair by aggregating
 * per-variable scores from the cached {@code paramDataHash} data.
 *
 * <p>This reactor replicates the legacy {@code calculateHash()} method in
 * {@code SimilarityHeatMapSheet} and the {@code flattenData()} method in
 * {@code SysSimHeatMapSheet}.
 *
 * <h3>Legacy algorithm (replicated exactly)</h3>
 * <ol>
 *   <li>For each pair key from the smallest variable's key set:</li>
 *   <li>For each selected variable, look up the pair's Score.</li>
 *   <li>If the pair is missing from ANY variable → skip the pair entirely.</li>
 *   <li>If {@code specifiedWeights} contains a minimum for this variable and
 *       the score is below it → skip the pair entirely.</li>
 *   <li>Accumulate in legacy order: {@code score += (varScore / totalVars)}.</li>
 *   <li>After all vars: if {@code score > 50} → include in output.</li>
 * </ol>
 *
 * <h3>Prerequisites</h3>
 * <p>Requires {@code GetSystemSimilarityDataSources} to have been called first
 * so that the paramDataHash and keyHash are cached in the var-store.
 *
 * <h3>Pixel calls</h3>
 * <pre>
 *   // Initial load (all vars, no minimums):
 *   ComputeSimilarityScores();
 *
 *   // Refresh with selected vars and per-variable minimum score filters:
 *   ComputeSimilarityScores(
 *     selectedVars=["Deployment_(Theater/Garrison)", "User_Types"],
 *     specifiedWeights={"Deployment_(Theater/Garrison)": 90, "User_Types": 80}
 *   );
 * </pre>
 *
 * <p><b>Note:</b> {@code specifiedWeights} are per-variable <i>minimum score
 * filters</i>, not weighting multipliers.  If a pair's score for a variable is
 * below the specified minimum, the entire pair is excluded.  This matches the
 * legacy {@code calculateHash(minimumWeights)} behaviour.
 */
public class ComputeSimilarityScoresReactor extends AbstractProjectReactor {

  public ComputeSimilarityScoresReactor() {
    this.keysToGet = new String[] { "selectedVars", "specifiedWeights" };
    this.keyRequired = new int[] { 0, 0 };
  }

  @Override
  @SuppressWarnings("unchecked")
  protected NounMetadata doExecute() {

    // ── 1. Parse optional parameters ─────────────────────────────────────────
    List<String> selectedVars = getSelectedVars();
    Map<String, Double> minimumWeights = parseWeights(getMap("specifiedWeights"));

    // ── 2. Load paramDataHash from var-store ─────────────────────────────────
    NounMetadata pdhNoun = this.insight.getVarStore()
        .get(GetSystemSimilarityDataSourcesReactor.VARSTORE_PARAM_DATA_HASH);

    if (pdhNoun == null) {
      throw new IllegalStateException(
          "No paramDataHash found in var-store. "
          + "Run GetSystemSimilarityDataSources first.");
    }

    Map<String, Map<String, Map<String, Object>>> paramDataHash =
        (Map<String, Map<String, Map<String, Object>>>) pdhNoun.getValue();

    // ── 3. Load keyHash from var-store ───────────────────────────────────────
    NounMetadata khNoun = this.insight.getVarStore()
        .get(GetSystemSimilarityDataSourcesReactor.VARSTORE_KEY_HASH);

    if (khNoun == null) {
      throw new IllegalStateException(
          "No keyHash found in var-store. "
          + "Run GetSystemSimilarityDataSources first.");
    }

    Map<String, Map<String, Object>> keyHash =
        (Map<String, Map<String, Object>>) khNoun.getValue();

    // ── 4. Determine which variables to use ──────────────────────────────────
    Set<String> availableVars = paramDataHash.keySet();

    if (selectedVars == null || selectedVars.isEmpty()) {
      selectedVars = new ArrayList<>(availableVars);
    } else {
      for (String var : selectedVars) {
        if (!availableVars.contains(var)) {
          throw new IllegalArgumentException(
              "Variable '" + var + "' not found in paramDataHash. "
              + "Available: " + availableVars);
        }
      }
    }

    int totalVars = selectedVars.size();
    if (totalVars == 0) {
      throw new IllegalArgumentException("No variables selected.");
    }

    // Legacy orderedVars behavior: iterate variables from smallest bucket first,
    // tie-breaking by variable name for deterministic floating-point accumulation.
    List<String> orderedVars = new ArrayList<>(selectedVars);
    Collections.sort(orderedVars, (a, b) -> {
      int sizeCmp = Integer.compare(paramDataHash.get(a).size(), paramDataHash.get(b).size());
      if (sizeCmp != 0) {
        return sizeCmp;
      }
      return a.compareTo(b);
    });

    // ── 5. Find the master key set from the smallest variable ────────────────
    String smallestVar = orderedVars.get(0);

    Set<String> masterKeys = paramDataHash.get(smallestVar).keySet();

    // ── 6. Compute simple average for each pair (legacy algorithm) ───────────
    //
    // Legacy formula: accumulate score as score += varScore / totalVars in orderedVars.
    // specifiedWeights are per-variable MINIMUM score filters, not multipliers.
    // If a variable's score for a pair is below its minimum → exclude pair.
    // After accumulation: include pair only if score > 50.
    List<Object[]> rows = new ArrayList<>();
    int totalPairsEvaluated = 0;
    int pairsAboveThreshold = 0;

    for (String pairKey : masterKeys) {
      double score = 0.0;
      boolean storeCell = true;

      for (String var : orderedVars) {
        Map<String, Object> cellData = paramDataHash.get(var).get(pairKey);

        if (cellData == null) {
          storeCell = false;
          break;
        }

        Object scoreObj = cellData.get("Score");
        if (scoreObj == null) {
          storeCell = false;
          break;
        }

        double varScore = ((Number) scoreObj).doubleValue();

        // Check per-variable minimum filter (legacy: minimumWeights)
        if (minimumWeights != null) {
          Double minVal = minimumWeights.get(var);
          if (minVal != null && varScore < minVal) {
            storeCell = false;
            break;
          }
        }

        score += varScore / totalVars;
      }

      if (!storeCell) {
        continue;
      }

      totalPairsEvaluated++;

      // Keep pairs strictly above 50 to match legacy flatten behavior
      if (score <= 50.0) {
        continue;
      }

      pairsAboveThreshold++;

      // Resolve pair key to system names via keyHash
      String[] parts = lookupPairNames(pairKey, keyHash);
      rows.add(new Object[] { parts[0], parts[1], score });
    }

    // ── 7. Build response ────────────────────────────────────────────────────
    Map<String, Object> result = new LinkedHashMap<>();
    result.put("headers", new String[] { "System1", "System2", "Score" });
    result.put("data", rows);
    result.put("variablesUsed", selectedVars);
    result.put("minimumWeightsUsed", minimumWeights);
    result.put("totalPairsEvaluated", totalPairsEvaluated);
    result.put("pairsAboveThreshold", pairsAboveThreshold);

    return new NounMetadata(result, PixelDataType.MAP, PixelOperationType.OPERATION);
  }

  // ── Private helpers ──────────────────────────────────────────────────────────

  /**
   * Looks up the pre-split system names for a hyphen-delimited pair key from
   * the keyHash. Falls back to first-hyphen split if the key is not found.
   */
  private static String[] lookupPairNames(
      String pairKey, Map<String, Map<String, Object>> keyHash) {
    Map<String, Object> entry = keyHash.get(pairKey);
    if (entry != null) {
      Object s1 = entry.get("System1");
      Object s2 = entry.get("System2");
      if (s1 != null && s2 != null) {
        return new String[] { s1.toString(), s2.toString() };
      }
    }
    // Fallback (should not be reached with a complete keyHash)
    int idx = pairKey.indexOf('-');
    if (idx < 0) {
      return new String[] { pairKey, "" };
    }
    return new String[] { pairKey.substring(0, idx), pairKey.substring(idx + 1) };
  }

  private List<String> getSelectedVars() {
    prerna.sablecc2.om.GenRowStruct grs = this.store.getGenRowStruct("selectedVars");
    if (grs != null && !grs.isEmpty()) {
      return grs.getAllStrValues();
    }
    return null;
  }

  private static Map<String, Double> parseWeights(Map<String, Object> raw) {
    if (raw == null || raw.isEmpty()) {
      return null;
    }
    Map<String, Double> weights = new LinkedHashMap<>();
    for (Map.Entry<String, Object> entry : raw.entrySet()) {
      if (entry.getValue() instanceof Number) {
        weights.put(entry.getKey(), ((Number) entry.getValue()).doubleValue());
      }
    }
    return weights.isEmpty() ? null : weights;
  }
}
