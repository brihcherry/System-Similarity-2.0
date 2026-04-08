package reactors.debug;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import prerna.sablecc2.om.PixelDataType;
import prerna.sablecc2.om.PixelOperationType;
import prerna.sablecc2.om.nounmeta.NounMetadata;
import reactors.AbstractProjectReactor;
import reactors.examples.GetSystemSimilarityDataSourcesReactor;

/**
 * Debug reactor that probes the raw (pre-processHashForCharting) and charted
 * (post-processHashForCharting, pre-prune) score data for a specific pair of
 * systems. Helps diagnose pairs that exist in legacy but are missing in the
 * new pipeline.
 *
 * <h3>Pixel call</h3>
 * <pre>
 * // By pair key (hyphen-delimited labels):
 * ProbeRawScores(pairKey=["TBI-BH-AHLTA"]);
 *
 * // Or by system URIs:
 * ProbeRawScores(system1=["http://health.mil/.../TBI-BH"], system2=["http://health.mil/.../AHLTA"]);
 * </pre>
 *
 * <h3>Prerequisites</h3>
 * Requires GetSystemSimilarityDataSources to have been called first.
 *
 * <h3>Output</h3>
 * For each variable, shows:
 * <ul>
 *   <li>Whether the pair exists in raw scores (pre-chart)</li>
 *   <li>The raw score [0..1] in both directions (A→B, B→A)</li>
 *   <li>Whether the pair exists in charted paramDataHash (post-chart)</li>
 *   <li>The charted score [0..100]</li>
 *   <li>Whether the pair survived pruning</li>
 * </ul>
 */
public class ProbeRawScoresReactor extends AbstractProjectReactor {

  public ProbeRawScoresReactor() {
    this.keysToGet = new String[] {"pairKey", "system1", "system2"};
    this.keyRequired = new int[] {0, 0, 0};
  }

  @Override
  @SuppressWarnings("unchecked")
  protected NounMetadata doExecute() {

    // ── 1. Parse inputs ─────────────────────────────────────────────────────
    String pairKey = getStringParam("pairKey");
    String system1 = getStringParam("system1");
    String system2 = getStringParam("system2");

    // ── 2. Load data from var-store ─────────────────────────────────────────
    NounMetadata rawNoun = this.insight.getVarStore()
        .get(GetSystemSimilarityDataSourcesReactor.VARSTORE_RAW_SCORES);
    if (rawNoun == null) {
      throw new IllegalStateException(
          "No raw scores found in var-store. "
          + "Run GetSystemSimilarityDataSources first.");
    }
    Map<String, Map<String, Map<String, Double>>> rawScores =
        (Map<String, Map<String, Map<String, Double>>>) rawNoun.getValue();

    NounMetadata pdhNoun = this.insight.getVarStore()
        .get(GetSystemSimilarityDataSourcesReactor.VARSTORE_PARAM_DATA_HASH);
    Map<String, Map<String, Map<String, Object>>> paramDataHash =
        pdhNoun != null ? (Map<String, Map<String, Map<String, Object>>>) pdhNoun.getValue()
            : new LinkedHashMap<>();

    NounMetadata slmNoun = this.insight.getVarStore()
        .get(GetSystemSimilarityDataSourcesReactor.VARSTORE_SYSTEM_LABEL_MAP);
    Map<String, String> systemLabelMap =
        slmNoun != null ? (Map<String, String>) slmNoun.getValue()
            : new LinkedHashMap<>();

    // ── 3. Resolve system URIs ──────────────────────────────────────────────
    // If pairKey given (e.g. "TBI-BH-AHLTA"), we need keyHash to resolve labels → URIs
    // If system1/system2 given as URIs, we also derive labels
    String uri1 = system1;
    String uri2 = system2;
    String label1 = null;
    String label2 = null;

    if (pairKey != null && !pairKey.isEmpty()) {
      // Use keyHash to look up system names for this pair
      NounMetadata khNoun = this.insight.getVarStore()
          .get(GetSystemSimilarityDataSourcesReactor.VARSTORE_KEY_HASH);
      if (khNoun != null) {
        Map<String, Map<String, Object>> keyHash =
            (Map<String, Map<String, Object>>) khNoun.getValue();
        Map<String, Object> pair = keyHash.get(pairKey);
        if (pair != null) {
          label1 = String.valueOf(pair.get("System1"));
          label2 = String.valueOf(pair.get("System2"));
        }
      }

      if (label1 == null || label2 == null) {
        // keyHash didn't have it — try splitting the pairKey
        // This is tricky with hyphen-delimited keys when labels contain hyphens
        // Use the systemLabelMap to find the best split
        String[] resolved = resolvePairKey(pairKey, systemLabelMap);
        label1 = resolved[0];
        label2 = resolved[1];
      }

      // Reverse-lookup: label → URI
      uri1 = reverseLookup(label1, systemLabelMap);
      uri2 = reverseLookup(label2, systemLabelMap);
    } else if (uri1 != null && uri2 != null) {
      label1 = systemLabelMap.getOrDefault(uri1, deriveLabel(uri1));
      label2 = systemLabelMap.getOrDefault(uri2, deriveLabel(uri2));
      pairKey = label1 + "-" + label2;
    } else {
      throw new IllegalArgumentException(
          "Provide either pairKey or both system1 and system2.");
    }

    // Also build the reversed pair key
    String reversedPairKey = label2 + "-" + label1;

    // ── 4. Probe each variable ──────────────────────────────────────────────
    List<Map<String, Object>> variableProbes = new ArrayList<>();

    // Get all variable names from both raw and charted
    java.util.Set<String> allVars = new java.util.LinkedHashSet<>();
    allVars.addAll(rawScores.keySet());
    allVars.addAll(paramDataHash.keySet());

    for (String variable : allVars) {
      Map<String, Object> probe = new LinkedHashMap<>();
      probe.put("variable", variable);

      // ── Raw scores (pre-chart) ────────────────────────────────────────
      Map<String, Map<String, Double>> rawVarData = rawScores.get(variable);
      boolean rawExists = false;
      Double rawScoreAB = null;
      Double rawScoreBA = null;

      if (rawVarData != null && uri1 != null && uri2 != null) {
        // Check A→B direction
        Map<String, Double> rawA = rawVarData.get(uri1);
        if (rawA != null && rawA.containsKey(uri2)) {
          rawScoreAB = rawA.get(uri2);
          rawExists = true;
        }
        // Check B→A direction
        Map<String, Double> rawB = rawVarData.get(uri2);
        if (rawB != null && rawB.containsKey(uri1)) {
          rawScoreBA = rawB.get(uri1);
          rawExists = true;
        }

        // If URIs didn't work, also check with angle brackets (legacy format)
        if (!rawExists) {
          String angledUri1 = "<" + uri1 + ">";
          String angledUri2 = "<" + uri2 + ">";
          rawA = rawVarData.get(angledUri1);
          if (rawA != null && rawA.containsKey(angledUri2)) {
            rawScoreAB = rawA.get(angledUri2);
            rawExists = true;
          }
          rawA = rawVarData.get(angledUri2);
          if (rawA != null && rawA.containsKey(angledUri1)) {
            rawScoreBA = rawA.get(angledUri1);
            rawExists = true;
          }
        }
      }

      probe.put("rawExists", rawExists);
      probe.put("rawScoreAB", rawScoreAB);
      probe.put("rawScoreBA", rawScoreBA);
      if (rawScoreAB != null) {
        probe.put("rawScoreABx100", rawScoreAB * 100.0);
      }
      if (rawScoreBA != null) {
        probe.put("rawScoreBAx100", rawScoreBA * 100.0);
      }

      // Also check if system1/system2 exist as keys in raw data at all
      if (rawVarData != null) {
        probe.put("system1InRaw", uri1 != null && rawVarData.containsKey(uri1));
        probe.put("system2InRaw", uri2 != null && rawVarData.containsKey(uri2));
      }

      // ── Charted scores (post-chart, post-prune) ──────────────────────
      Map<String, Map<String, Object>> chartedVarData = paramDataHash.get(variable);
      boolean chartedForwardExists = false;
      boolean chartedReverseExists = false;
      Double chartedScoreForward = null;
      Double chartedScoreReverse = null;

      if (chartedVarData != null) {
        Map<String, Object> fwd = chartedVarData.get(pairKey);
        if (fwd != null) {
          chartedForwardExists = true;
          Object s = fwd.get("Score");
          if (s instanceof Number) {
            chartedScoreForward = ((Number) s).doubleValue();
          }
        }
        Map<String, Object> rev = chartedVarData.get(reversedPairKey);
        if (rev != null) {
          chartedReverseExists = true;
          Object s = rev.get("Score");
          if (s instanceof Number) {
            chartedScoreReverse = ((Number) s).doubleValue();
          }
        }
      }

      probe.put("chartedForwardExists", chartedForwardExists);
      probe.put("chartedReverseExists", chartedReverseExists);
      probe.put("chartedScoreForward", chartedScoreForward);
      probe.put("chartedScoreReverse", chartedScoreReverse);

      // ── Diagnosis ─────────────────────────────────────────────────────
      String diagnosis;
      if (!rawExists && !chartedForwardExists && !chartedReverseExists) {
        diagnosis = "MISSING_FROM_RAW — pair never computed by SimilarityFunctions";
      } else if (rawExists && !chartedForwardExists && !chartedReverseExists) {
        diagnosis = "LOST_IN_CHART_TRANSFORM — raw exists but processHashForCharting dropped it";
      } else if (rawExists && (chartedForwardExists || chartedReverseExists)) {
        diagnosis = "PRESENT — pair found in both raw and charted";
      } else {
        diagnosis = "CHARTED_NO_RAW — unexpected: charted exists but no raw data";
      }
      probe.put("diagnosis", diagnosis);

      variableProbes.add(probe);
    }

    // ── 5. Build response ───────────────────────────────────────────────────
    Map<String, Object> result = new LinkedHashMap<>();
    result.put("pairKey", pairKey);
    result.put("reversedPairKey", reversedPairKey);
    result.put("system1Label", label1);
    result.put("system2Label", label2);
    result.put("system1URI", uri1);
    result.put("system2URI", uri2);
    result.put("variableCount", variableProbes.size());
    result.put("variables", variableProbes);

    // Summary
    int missingFromRaw = 0;
    int lostInChart = 0;
    int present = 0;
    for (Map<String, Object> p : variableProbes) {
      String d = String.valueOf(p.get("diagnosis"));
      if (d.startsWith("MISSING_FROM_RAW")) missingFromRaw++;
      else if (d.startsWith("LOST_IN_CHART_TRANSFORM")) lostInChart++;
      else if (d.startsWith("PRESENT")) present++;
    }
    result.put("missingFromRawCount", missingFromRaw);
    result.put("lostInChartTransformCount", lostInChart);
    result.put("presentCount", present);

    return new NounMetadata(result, PixelDataType.MAP, PixelOperationType.OPERATION);
  }

  // ── Helpers ────────────────────────────────────────────────────────────────

  /**
   * Tries to split a hyphen-delimited pair key into two system labels.
   * Uses the systemLabelMap values as known labels to find the split point.
   */
  private String[] resolvePairKey(String pairKey, Map<String, String> systemLabelMap) {
    java.util.Set<String> knownLabels = new java.util.HashSet<>(systemLabelMap.values());

    // Try every possible split position
    for (int i = 1; i < pairKey.length(); i++) {
      if (pairKey.charAt(i) == '-') {
        String left = pairKey.substring(0, i);
        String right = pairKey.substring(i + 1);
        if (knownLabels.contains(left) && knownLabels.contains(right)) {
          return new String[] {left, right};
        }
      }
    }

    // Fallback: couldn't split
    return new String[] {pairKey, null};
  }

  /**
   * Reverse-lookups a label to find its URI in the systemLabelMap.
   */
  private String reverseLookup(String label, Map<String, String> systemLabelMap) {
    if (label == null) return null;
    for (Map.Entry<String, String> entry : systemLabelMap.entrySet()) {
      if (label.equals(entry.getValue())) {
        return entry.getKey();
      }
    }
    return null;
  }

  /**
   * Derives a label from a URI by extracting the last path segment.
   */
  private String deriveLabel(String uri) {
    if (uri == null) return null;
    int lastSlash = uri.lastIndexOf('/');
    return lastSlash >= 0 ? uri.substring(lastSlash + 1) : uri;
  }

  private String getStringParam(String key) {
    String val = this.keyValue.get(key);
    if (val != null) {
      val = val.trim();
      if (val.isEmpty()) return null;
    }
    return val;
  }
}
