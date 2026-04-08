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
 * Debug reactor that dumps the systemLabelMap from the var-store, along with
 * collision analysis to identify URI→label mapping issues.
 *
 * <h3>Pixel call</h3>
 * <pre>GetSystemLabelMap();</pre>
 *
 * <h3>Prerequisites</h3>
 * Requires GetSystemSimilarityDataSources to have been called first.
 *
 * <h3>Output</h3>
 * <pre>
 * {
 *   "totalSystems": 392,
 *   "totalMappings": 784,      // 2× systems (raw URI + normalized URI entries)
 *   "uniqueLabels": 390,
 *   "collisions": [            // labels that map to multiple distinct URIs
 *     { "label": "SOME_SYSTEM_2", "originalLabel": "SOME_SYSTEM", "uris": [...] }
 *   ],
 *   "labelToUris": {           // reverse map: label → list of URIs that map to it
 *     "EPIPHANY": ["http://...Concept/System/EPIPHANY"],
 *     ...
 *   },
 *   "systemLabelMap": { ... }  // the full URI→label map
 * }
 * </pre>
 */
public class GetSystemLabelMapReactor extends AbstractProjectReactor {

  public GetSystemLabelMapReactor() {
    this.keysToGet = new String[] {};
    this.keyRequired = new int[] {};
  }

  @Override
  @SuppressWarnings("unchecked")
  protected NounMetadata doExecute() {

    // ── 1. Load systemLabelMap from var-store ────────────────────────────────
    NounMetadata slmNoun = this.insight.getVarStore()
        .get(GetSystemSimilarityDataSourcesReactor.VARSTORE_SYSTEM_LABEL_MAP);

    if (slmNoun == null) {
      throw new IllegalStateException(
          "No systemLabelMap found in var-store. "
          + "Run GetSystemSimilarityDataSources first.");
    }

    Map<String, String> systemLabelMap = (Map<String, String>) slmNoun.getValue();

    // ── 2. Load allSystems from var-store ────────────────────────────────────
    NounMetadata asNoun = this.insight.getVarStore()
        .get(GetSystemSimilarityDataSourcesReactor.VARSTORE_ALL_SYSTEMS);

    List<String> allSystems = asNoun != null
        ? (List<String>) asNoun.getValue()
        : new ArrayList<>();

    // ── 3. Build reverse map: label → list of URIs ──────────────────────────
    Map<String, List<String>> labelToUris = new LinkedHashMap<>();
    for (Map.Entry<String, String> entry : systemLabelMap.entrySet()) {
      String uri = entry.getKey();
      String label = entry.getValue();
      labelToUris.computeIfAbsent(label, k -> new ArrayList<>()).add(uri);
    }

    // ── 4. Find collisions (labels with suffixes like _2, _3) ───────────────
    List<Map<String, Object>> collisions = new ArrayList<>();
    for (Map.Entry<String, List<String>> entry : labelToUris.entrySet()) {
      String label = entry.getKey();
      List<String> uris = entry.getValue();
      // A collision is indicated by labels with _N suffix from buildSystemLabelMap
      if (label.matches(".*_\\d+$")) {
        Map<String, Object> collision = new LinkedHashMap<>();
        collision.put("label", label);
        // Find the base label (without _N suffix)
        String base = label.replaceAll("_\\d+$", "");
        collision.put("originalLabel", base);
        collision.put("uris", uris);
        // Also include the URIs for the base label
        List<String> baseUris = labelToUris.get(base);
        if (baseUris != null) {
          collision.put("baseUris", baseUris);
        }
        collisions.add(collision);
      }
    }

    // ── 5. Also check for labels where multiple distinct base URIs map to same label
    //       (after stripping angle brackets and normalizing) ─────────────────
    List<Map<String, Object>> duplicateLabels = new ArrayList<>();
    for (Map.Entry<String, List<String>> entry : labelToUris.entrySet()) {
      List<String> uris = entry.getValue();
      if (uris.size() > 2) {
        // More than 2 means more than the expected raw+normalized pair
        Map<String, Object> dup = new LinkedHashMap<>();
        dup.put("label", entry.getKey());
        dup.put("uriCount", uris.size());
        dup.put("uris", uris);
        duplicateLabels.add(dup);
      }
    }

    // ── 6. Build response ───────────────────────────────────────────────────
    Map<String, Object> result = new LinkedHashMap<>();
    result.put("totalSystems", allSystems.size());
    result.put("totalMappings", systemLabelMap.size());
    result.put("uniqueLabels", labelToUris.size());
    result.put("collisionCount", collisions.size());
    result.put("collisions", collisions);
    result.put("duplicateLabelCount", duplicateLabels.size());
    result.put("duplicateLabels", duplicateLabels);
    result.put("allSystems", allSystems);
    result.put("labelToUris", labelToUris);
    result.put("systemLabelMap", systemLabelMap);

    return new NounMetadata(result, PixelDataType.MAP, PixelOperationType.OPERATION);
  }
}
