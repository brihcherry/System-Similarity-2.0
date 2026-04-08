package reactors.debug;

import java.lang.reflect.Field;
import java.util.HashMap;
import java.util.Hashtable;
import java.util.LinkedHashMap;
import java.util.Map;

import prerna.om.Insight;
import prerna.om.InsightStore;
import prerna.om.OldInsight;
import prerna.sablecc2.om.PixelDataType;
import prerna.sablecc2.om.PixelOperationType;
import prerna.sablecc2.om.nounmeta.NounMetadata;
import prerna.ui.components.api.IPlaySheet;
import reactors.AbstractProjectReactor;
import reactors.examples.GetSystemSimilarityHeatmapReactor;

/**
 * Extracts the raw {@code paramDataHash} from the cached OldInsight's
 * playsheet via reflection and returns it as a serializable Map.
 *
 * <p>This is a development/debug reactor for inspecting the pre-computed
 * similarity data before any weighting or aggregation is applied.
 *
 * <p>Requires {@link GetSystemSimilarityHeatmapReactor} to have been called
 * first so the cached insight ID is present in the var-store.
 *
 * <h3>Pixel call</h3>
 * <pre>
 *   GetParamDataHash(variable=["Business_Processes_Supported"]);
 * </pre>
 *
 * <h4>Parameters</h4>
 * <ul>
 *   <li>{@code variable} — optional — if provided, returns only that
 *       variable's data instead of the entire hash</li>
 * </ul>
 */
@SuppressWarnings("deprecation")
public class GetParamDataHashReactor extends AbstractProjectReactor {

  /** The field name on the legacy playsheet that holds the cached data. */
  private static final String PARAM_DATA_HASH_FIELD = "paramDataHash";

  public GetParamDataHashReactor() {
    this.keysToGet = new String[] {"variable"};
    this.keyRequired = new int[] {0};
  }

  @Override
  protected NounMetadata doExecute() {

    String variableFilter = this.keyValue.get("variable");

    // ── 1. Retrieve cached insight ID from var-store ─────────────────────────
    NounMetadata cachedIdNoun = this.insight.getVarStore()
        .get(GetSystemSimilarityHeatmapReactor.CACHED_INSIGHT_ID_KEY);

    if (cachedIdNoun == null) {
      throw new IllegalStateException(
          "No cached insight ID found in var-store. "
          + "Run GetSystemSimilarityHeatmap first.");
    }

    String cachedInsightId = cachedIdNoun.getValue().toString();

    // ── 2. Look up the OldInsight and its playsheet ──────────────────────────
    Insight cachedInsight = InsightStore.getInstance().get(cachedInsightId);
    if (cachedInsight == null) {
      throw new IllegalStateException(
          "Cached insight " + cachedInsightId + " not found in InsightStore. "
          + "The session may have expired — re-run GetSystemSimilarityHeatmap.");
    }

    if (!(cachedInsight instanceof OldInsight)) {
      throw new IllegalStateException(
          "Cached insight is " + cachedInsight.getClass().getSimpleName()
          + ", expected OldInsight.");
    }

    OldInsight oldInsight = (OldInsight) cachedInsight;
    IPlaySheet playSheet = oldInsight.getPlaySheet();

    if (playSheet == null) {
      throw new IllegalStateException(
          "Cached OldInsight has no playsheet attached.");
    }

    // ── 3. Extract paramDataHash via reflection ──────────────────────────────
    //
    // Walk the class hierarchy to find the field (it may be declared on a
    // superclass like SimilarityHeatMapSheet).
    Object rawParamDataHash = extractField(playSheet, PARAM_DATA_HASH_FIELD);

    if (rawParamDataHash == null) {
      throw new IllegalStateException(
          "Field '" + PARAM_DATA_HASH_FIELD + "' not found or is null on "
          + playSheet.getClass().getName()
          + ". Run IntrospectPlaysheet() to inspect available fields.");
    }

    // ── 4. Convert to serializable Map ───────────────────────────────────────
    Map<String, Object> serializable = toSerializableMap(rawParamDataHash);

    // ── 5. Build response ────────────────────────────────────────────────────
    Map<String, Object> result = new LinkedHashMap<>();
    result.put("cachedInsightId", cachedInsightId);
    result.put("playsheetClass", playSheet.getClass().getName());
    result.put("fieldType", rawParamDataHash.getClass().getName());

    if (variableFilter != null && !variableFilter.trim().isEmpty()) {
      // Return only the requested variable
      Object variableData = serializable.get(variableFilter);
      if (variableData == null) {
        result.put("error", "Variable '" + variableFilter
            + "' not found. Available keys: " + serializable.keySet());
        result.put("availableVariables", serializable.keySet());
      } else {
        result.put("variable", variableFilter);
        result.put("data", variableData);
        if (variableData instanceof Map) {
          result.put("pairCount", ((Map<?, ?>) variableData).size());
        }
      }
    } else {
      // Return the full hash with summary metadata
      result.put("variableCount", serializable.size());
      result.put("variables", serializable.keySet());

      // Add per-variable pair counts
      Map<String, Integer> pairCounts = new LinkedHashMap<>();
      for (Map.Entry<String, Object> entry : serializable.entrySet()) {
        if (entry.getValue() instanceof Map) {
          pairCounts.put(entry.getKey(), ((Map<?, ?>) entry.getValue()).size());
        }
      }
      result.put("pairCounts", pairCounts);

      result.put("data", serializable);
    }

    return new NounMetadata(result, PixelDataType.MAP, PixelOperationType.OPERATION);
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

  /**
   * Recursively converts Hashtable/Map structures into plain LinkedHashMaps
   * that serialize cleanly to JSON. Non-map values are converted to strings.
   */
  @SuppressWarnings("unchecked")
  private static Map<String, Object> toSerializableMap(Object obj) {
    Map<String, Object> result = new LinkedHashMap<>();

    Map<?, ?> source;
    if (obj instanceof Hashtable) {
      source = (Hashtable<?, ?>) obj;
    } else if (obj instanceof Map) {
      source = (Map<?, ?>) obj;
    } else {
      result.put("_raw", obj.toString());
      return result;
    }

    for (Map.Entry<?, ?> entry : source.entrySet()) {
      String key = String.valueOf(entry.getKey());
      Object value = entry.getValue();

      if (value instanceof Map || value instanceof Hashtable) {
        result.put(key, toSerializableMap(value));
      } else if (value == null) {
        result.put(key, null);
      } else {
        // Preserve numbers; stringify everything else
        if (value instanceof Number) {
          result.put(key, value);
        } else {
          result.put(key, value.toString());
        }
      }
    }

    return result;
  }
}
