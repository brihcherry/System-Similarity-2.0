package reactors.systemSimilarity;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import prerna.sablecc2.om.PixelDataType;
import prerna.sablecc2.om.PixelOperationType;
import prerna.sablecc2.om.nounmeta.NounMetadata;
import reactors.AbstractProjectReactor;

/**
 * Computes overall similarity scores for every system pair by aggregating
 * per-variable scores from the cached {@code paramDataHash} data.
 *
 * <p>This reactor replicates the legacy {@code calculateHash()} method in
 * {@code SimilarityHeatMapSheet} and the {@code flattenData()} method in
 * {@code SysSimHeatMapSheet}.
 *
 * <h3>Algorithm</h3>
 * <ol>
 *   <li>For each pair key from the smallest variable's key set:</li>
 *   <li>For each selected variable, look up the pair's Score.</li>
 *   <li>If the pair is missing from ANY variable → skip the pair entirely
 *       (completeness rule).</li>
 *   <li>Accumulate a <b>weighted average</b> across variables:
 *       {@code weightedSum += w * varScore; totalWeight += w} where
 *       {@code w = specifiedWeights.getOrDefault(var, 1.0)} (negative weights
 *       are coerced to 0). After the loop:
 *       {@code score = totalWeight > 0 ? weightedSum / totalWeight
 *                                      : uniformSum / totalVars} so an
 *       all-zero-weight input falls back to a uniform 1/N mean.</li>
 *   <li>After all vars: if the composite is below {@code minimumScore}
 *       (default 0 → no threshold) → skip pair.</li>
 * </ol>
 *
 * <h3>Prerequisites</h3>
 * <p>Requires {@code GetSystemSimilarityDataSources} to have been called first
 * so that the paramDataHash and keyHash are cached in the var-store.
 *
 * <h3>Pixel calls</h3>
 * <pre>
 *   // Initial load (all vars, default weight 1.0 each → uniform mean):
 *   ComputeSimilarityScores();
 *
 *   // Refresh with selected vars and per-variable weight multipliers:
 *   ComputeSimilarityScores(
 *     selectedVars=["Deployment_(Theater/Garrison)", "User_Types"],
 *     specifiedWeights={"Deployment_(Theater/Garrison)": 50, "User_Types": 1}
 *   );
 * </pre>
 *
 * <p><b>Note:</b> {@code specifiedWeights} are per-variable <i>multipliers</i>
 * in a weighted average, matching the legacy
 * {@code SimilarityHeatMapSheet.calculateHash(minimumWeights)} behaviour where
 * any variable absent from the map defaults to weight 1.0. They are not
 * per-variable score-cutoff filters.
 */
public class ComputeSimilarityScoresReactor extends AbstractProjectReactor {

  public ComputeSimilarityScoresReactor() {
    this.keysToGet = new String[] { "selectedVars", "specifiedWeights", "minimumScore" };
    this.keyRequired = new int[] { 0, 0, 0 };
  }

  @Override
  @SuppressWarnings("unchecked")
  protected NounMetadata doExecute() {

    // ── 1. Parse optional parameters ─────────────────────────────────────────
    List<String> selectedVars = getSelectedVars();
    Map<String, Double> specifiedWeights = parseWeights(getMap("specifiedWeights"));
    double minimumScore = parseMinimumScore();

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

    // ── 6. Compute weighted average for each pair (legacy algorithm) ─────────
    //
    // Weighted formula: score = Σ(wᵢ·sᵢ) / Σ(wᵢ), where wᵢ defaults to 1.0 for
    // any selected variable absent from specifiedWeights. Negative weights are
    // coerced to 0. If every selected weight is 0, fall back to a uniform 1/N
    // mean to avoid divide-by-zero. The global minimumScore threshold is
    // applied to the composite, mirroring legacy flattenData() < 50 filtering.
    List<Object[]> rows = new ArrayList<>();
    Set<String> completedKeys = new HashSet<>();
    int totalPairsEvaluated = 0;
    int pairsAboveThreshold = 0;

    for (String pairKey : masterKeys) {
      double weightedSum = 0.0;
      double totalWeight = 0.0;
      double uniformSum = 0.0;
      boolean storeCell = true;
      Map<String, Double> varScores = new LinkedHashMap<>();

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

        double weight = 1.0;
        if (specifiedWeights != null) {
          Double w = specifiedWeights.get(var);
          if (w != null) {
            weight = Math.max(0.0, w);
          }
        }

        weightedSum += weight * varScore;
        totalWeight += weight;
        uniformSum += varScore;
        varScores.put(var, varScore);
      }

      if (!storeCell) {
        continue;
      }

      double score = totalWeight > 0.0
          ? weightedSum / totalWeight
          : uniformSum / totalVars;

      totalPairsEvaluated++;

      // Apply minimum score threshold consistently in all modes.
      if (minimumScore > 0 && score < minimumScore) {
        continue;
      }

      pairsAboveThreshold++;

      // Resolve pair key to system names via keyHash
      String[] parts = lookupPairNames(pairKey, keyHash);
      rows.add(new Object[] { parts[0], parts[1], score, varScores });
      completedKeys.add(pairKey);
    }

    // ── 6b. Collect pairs with partial category data ────────────────────────
    //
    // These are pairs that exist in at least one category bucket but were
    // excluded from the main pass (missing data in one or more categories).
    // They remain blank cells on the map but carry partial categoryScores
    // for display in the hover tooltip.  Collected in all modes so that
    // non-DBS capability-group views can show per-category breakdowns.
    List<Object[]> partialRows = new ArrayList<>();
    {
      Set<String> allPairKeys = new HashSet<>();
      for (String var : orderedVars) {
        allPairKeys.addAll(paramDataHash.get(var).keySet());
      }

      for (String pairKey : allPairKeys) {
        if (completedKeys.contains(pairKey)) continue;

        Map<String, Double> partialVarScores = new LinkedHashMap<>();
        for (String var : orderedVars) {
          Map<String, Object> cellData = paramDataHash.get(var).get(pairKey);
          if (cellData != null) {
            Object scoreObj = cellData.get("Score");
            if (scoreObj instanceof Number) {
              partialVarScores.put(var, ((Number) scoreObj).doubleValue());
            }
          }
        }

        if (partialVarScores.isEmpty()) continue;

        String[] parts = lookupPairNames(pairKey, keyHash);
        partialRows.add(new Object[] { parts[0], parts[1], partialVarScores });
      }
    }

    // ── 7. Retrieve allSystems from var-store ───────────────────────────────
    List<String> allSystems = new ArrayList<>();
    NounMetadata allSystemsNoun = this.insight.getVarStore()
        .get(GetSystemSimilarityDataSourcesReactor.VARSTORE_ALL_SYSTEMS);
    if (allSystemsNoun != null && allSystemsNoun.getValue() instanceof List) {
      allSystems = (List<String>) allSystemsNoun.getValue();
    }

    // ── 8. Retrieve systemLabelMap from var-store ───────────────────────────
    Map<String, String> systemLabelMap = new HashMap<>();
    NounMetadata systemLabelMapNoun = this.insight.getVarStore()
        .get(GetSystemSimilarityDataSourcesReactor.VARSTORE_SYSTEM_LABEL_MAP);
    if (systemLabelMapNoun != null && systemLabelMapNoun.getValue() instanceof Map) {
      systemLabelMap = (Map<String, String>) systemLabelMapNoun.getValue();
    }

    // ── 9. Build response ────────────────────────────────────────────────────
    Map<String, Object> result = new LinkedHashMap<>();
    result.put("headers", new String[] { "System1", "System2", "Score" });
    result.put("data", rows);
    result.put("partialPairs", partialRows);
    result.put("variablesUsed", selectedVars);
    result.put("specifiedWeightsUsed", specifiedWeights);
    result.put("totalPairsEvaluated", totalPairsEvaluated);
    result.put("pairsAboveThreshold", pairsAboveThreshold);
    result.put("allSystems", allSystems);
    result.put("systemLabelMap", systemLabelMap);

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

  private double parseMinimumScore() {
    prerna.sablecc2.om.GenRowStruct grs = this.store.getGenRowStruct("minimumScore");
    if (grs != null && !grs.isEmpty()) {
      // Try as string value first (most common from Pixel)
      List<String> strVals = grs.getAllStrValues();
      if (strVals != null && !strVals.isEmpty()) {
        try {
          return sanitizeThreshold(Double.parseDouble(strVals.get(0).trim()));
        } catch (NumberFormatException e) {
          // fall through
        }
      }
      // Try as numeric noun directly
      prerna.sablecc2.om.nounmeta.NounMetadata noun = grs.getNoun(0);
      if (noun != null && noun.getValue() instanceof Number) {
        return sanitizeThreshold(((Number) noun.getValue()).doubleValue());
      }
    }
    return 0.0;
  }

  // Non-finite thresholds (NaN, ±Infinity) would silently disable or invert the
  // composite filter; coerce them to "no threshold".
  private static double sanitizeThreshold(double value) {
    return Double.isFinite(value) ? value : 0.0;
  }

  private static Map<String, Double> parseWeights(Map<String, Object> raw) {
    if (raw == null || raw.isEmpty()) {
      return null;
    }
    Map<String, Double> weights = new LinkedHashMap<>();
    for (Map.Entry<String, Object> entry : raw.entrySet()) {
      if (entry.getValue() instanceof Number) {
        double w = ((Number) entry.getValue()).doubleValue();
        // Drop NaN / ±Infinity so they default to weight 1.0 in the loop and
        // cannot produce NaN composites that bypass the minimumScore filter.
        if (Double.isFinite(w)) {
          weights.put(entry.getKey(), w);
        }
      }
    }
    return weights.isEmpty() ? null : weights;
  }
}
