package reactors.debug;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

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
 * Compares the system list (allSystems) from the new reactor pipeline against
 * the legacy playsheet's comparisonObjectList. Identifies systems that appear
 * in one but not the other.
 *
 * <h3>Pixel call</h3>
 * <pre>CompareSystemLists();</pre>
 *
 * <h3>Prerequisites</h3>
 * <ul>
 *   <li>Legacy cache must be populated (Reload Heatmap Cache)</li>
 *   <li>New reactor must have run (GetSystemSimilarityDataSources)</li>
 * </ul>
 */
@SuppressWarnings("deprecation")
public class CompareSystemListsReactor extends AbstractProjectReactor {

  public CompareSystemListsReactor() {
    this.keysToGet = new String[] {};
    this.keyRequired = new int[] {};
  }

  @Override
  @SuppressWarnings("unchecked")
  protected NounMetadata doExecute() {

    // ── 1. Read allSystems from var-store (new pipeline) ─────────────────────
    NounMetadata asNoun = this.insight.getVarStore()
        .get(GetSystemSimilarityDataSourcesReactor.VARSTORE_ALL_SYSTEMS);

    if (asNoun == null) {
      throw new IllegalStateException(
          "No allSystems found in var-store. "
          + "Run GetSystemSimilarityDataSources first.");
    }

    List<String> newSystems = (List<String>) asNoun.getValue();

    // ── 2. Read comparisonObjectList from legacy playsheet ───────────────────
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
          "Cached insight not found or not an OldInsight.");
    }

    OldInsight oldInsight = (OldInsight) cachedInsight;
    IPlaySheet playSheet = oldInsight.getPlaySheet();

    if (playSheet == null) {
      throw new IllegalStateException("Cached OldInsight has no playsheet.");
    }

    // The legacy playsheet uses SimilarityFunctions which stores the system
    // list in comparisonObjectList. The playsheet itself aggregates via its
    // own similarityFunctions field.
    List<String> legacySystems = extractComparisonObjectList(playSheet);

    // ── 3. Compare ──────────────────────────────────────────────────────────
    Set<String> newSet = new TreeSet<>(newSystems);
    Set<String> legacySet = new TreeSet<>(legacySystems);

    List<String> onlyInNew = new ArrayList<>();
    for (String s : newSet) {
      if (!legacySet.contains(s)) {
        onlyInNew.add(s);
      }
    }

    List<String> onlyInLegacy = new ArrayList<>();
    for (String s : legacySet) {
      if (!newSet.contains(s)) {
        onlyInLegacy.add(s);
      }
    }

    List<String> common = new ArrayList<>();
    for (String s : newSet) {
      if (legacySet.contains(s)) {
        common.add(s);
      }
    }

    // ── 4. Build response ───────────────────────────────────────────────────
    Map<String, Object> result = new LinkedHashMap<>();
    result.put("newSystemCount", newSystems.size());
    result.put("legacySystemCount", legacySystems.size());
    result.put("commonCount", common.size());
    result.put("onlyInNewCount", onlyInNew.size());
    result.put("onlyInLegacyCount", onlyInLegacy.size());
    result.put("listsMatch", onlyInNew.isEmpty() && onlyInLegacy.isEmpty());
    result.put("onlyInNew", onlyInNew);
    result.put("onlyInLegacy", onlyInLegacy);

    return new NounMetadata(result, PixelDataType.MAP, PixelOperationType.OPERATION);
  }

  /**
   * Extracts the comparisonObjectList from the legacy playsheet.
   * Tries direct field, then looks inside the SimilarityFunctions instance.
   */
  @SuppressWarnings("unchecked")
  private List<String> extractComparisonObjectList(IPlaySheet playSheet) {
    // Try direct field on playsheet
    Object direct = extractField(playSheet, "comparisonObjectList");
    if (direct instanceof List) {
      return (List<String>) direct;
    }

    // Try via the SimilarityFunctions field
    Object simFunctions = extractField(playSheet, "similarityFunctions");
    if (simFunctions != null) {
      Object fromSF = extractField(simFunctions, "comparisonObjectList");
      if (fromSF instanceof List) {
        return (List<String>) fromSF;
      }
    }

    // Try via the "functions" field (some playsheets name it differently)
    Object functions = extractField(playSheet, "functions");
    if (functions != null) {
      Object fromF = extractField(functions, "comparisonObjectList");
      if (fromF instanceof List) {
        return (List<String>) fromF;
      }
    }

    throw new IllegalStateException(
        "Could not find comparisonObjectList on the legacy playsheet. "
        + "Playsheet class: " + playSheet.getClass().getName());
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
