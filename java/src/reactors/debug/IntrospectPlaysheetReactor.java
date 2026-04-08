package reactors.debug;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
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
 * Introspects the cached OldInsight's playsheet to reveal its fields and
 * methods. Used as a development tool to confirm the exact field name and
 * type of {@code paramDataHash} before writing extraction code.
 *
 * <p>Requires {@link GetSystemSimilarityHeatmapReactor} to have been called
 * first so the cached insight ID is present in the var-store.
 *
 * <h3>Pixel call</h3>
 * <pre>
 *   IntrospectPlaysheet();
 * </pre>
 */
@SuppressWarnings("deprecation")
public class IntrospectPlaysheetReactor extends AbstractProjectReactor {

  public IntrospectPlaysheetReactor() {
    this.keysToGet = new String[] {};
    this.keyRequired = new int[] {};
  }

  @Override
  protected NounMetadata doExecute() {

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
          "Cached insight " + cachedInsightId + " not found in InsightStore.");
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

    // ── 3. Introspect the playsheet class hierarchy ──────────────────────────
    Map<String, Object> result = new HashMap<>();
    result.put("className", playSheet.getClass().getName());
    result.put("cachedInsightId", cachedInsightId);

    // Walk the entire class hierarchy to find inherited fields too
    List<Map<String, String>> allFields = new ArrayList<>();
    Class<?> current = playSheet.getClass();
    while (current != null && current != Object.class) {
      for (Field f : current.getDeclaredFields()) {
        Map<String, String> fieldInfo = new HashMap<>();
        fieldInfo.put("name", f.getName());
        fieldInfo.put("type", f.getType().getName());
        fieldInfo.put("genericType", f.getGenericType().toString());
        fieldInfo.put("modifiers", Modifier.toString(f.getModifiers()));
        fieldInfo.put("declaredIn", current.getName());
        allFields.add(fieldInfo);
      }
      current = current.getSuperclass();
    }
    result.put("fields", allFields);

    // Collect methods (declared on the playsheet class only, not Object)
    List<Map<String, Object>> allMethods = new ArrayList<>();
    current = playSheet.getClass();
    while (current != null && current != Object.class) {
      for (Method m : current.getDeclaredMethods()) {
        Map<String, Object> methodInfo = new HashMap<>();
        methodInfo.put("name", m.getName());
        methodInfo.put("returnType", m.getReturnType().getName());
        methodInfo.put("modifiers", Modifier.toString(m.getModifiers()));
        methodInfo.put("declaredIn", current.getName());
        List<String> paramTypes = new ArrayList<>();
        for (Class<?> p : m.getParameterTypes()) {
          paramTypes.add(p.getName());
        }
        methodInfo.put("parameterTypes", paramTypes);
        allMethods.add(methodInfo);
      }
      current = current.getSuperclass();
    }
    result.put("methods", allMethods);

    return new NounMetadata(result, PixelDataType.MAP, PixelOperationType.OPERATION);
  }
}
