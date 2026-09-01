package reactors.systemSimilarity;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import prerna.sablecc2.om.PixelDataType;
import prerna.sablecc2.om.ReactorKeysEnum;
import prerna.sablecc2.om.nounmeta.NounMetadata;
import reactors.AbstractProjectReactor;
import reactors.utils.QueryExecutor;
import reactors.utils.SimilarityChartingUtils;

/**
 * Reactor that returns a mapping of CapabilityGroup labels to the system labels
 * that support each group, derived from the TAP_Core_Data RDF engine.
 *
 * <p>This reactor is used to populate the capability-group filter dropdown on
 * the System Similarity heatmap page. The returned map is consumed purely on
 * the frontend as a client-side view filter — it does not affect the data loaded
 * into the var-store by {@code GetSystemSimilarityDataSources}.
 *
 * <h3>Pixel call</h3>
 * <pre>
 *   GetSystemsByCapabilityGroup(database=["133db94b-4371-4763-bff9-edf7e5ed021b"]);
 * </pre>
 *
 * <h3>Parameters</h3>
 * <ul>
 *   <li>{@code database} — <b>optional</b> — RDF engine UUID; defaults to the
 *       standard TAP_Core_Data engine ID.</li>
 * </ul>
 *
 * <h3>Output contract</h3>
 * <pre>
 * {
 *   "GroupLabelA": ["SystemLabel1", "SystemLabel2"],
 *   "GroupLabelB": ["SystemLabel3"],
 *   ...
 * }
 * </pre>
 *
 * <p>Keys are CapabilityGroup display labels (last URI path segment).
 * Values are sorted lists of System display labels belonging to that group.
 * Returns an empty map when no triples match.
 *
 * @see reactors.AbstractProjectReactor
 * @see reactors.systemSimilarity.GetSystemSimilarityDataSourcesReactor
 */
public class GetSystemsByCapabilityGroupReactor extends AbstractProjectReactor {

    /** Default TAP_Core_Data engine UUID. */
    private static final String DEFAULT_ENGINE_ID = "133db94b-4371-4763-bff9-edf7e5ed021b";

    /**
     * SPARQL query that retrieves all System–CapabilityGroup pairs.
     * Both ends must be typed instances in the ontology.
     */
    private static final String CAPABILITY_GROUP_QUERY =
            "SELECT DISTINCT ?System ?CapabilityGroup WHERE {"
            + " ?System <http://www.w3.org/1999/02/22-rdf-syntax-ns#type>"
            + "   <http://semoss.org/ontologies/Concept/System> ."
            + " ?CapabilityGroup <http://www.w3.org/1999/02/22-rdf-syntax-ns#type>"
            + "   <http://semoss.org/ontologies/Concept/CapabilityGroup> ."
            + " ?System <http://semoss.org/ontologies/Relation/Supports> ?CapabilityGroup"
            + " }";

    public GetSystemsByCapabilityGroupReactor() {
        this.keysToGet = new String[] { ReactorKeysEnum.DATABASE.getKey() };
        this.keyRequired = new int[] { 0 };
    }

    @Override
    protected NounMetadata doExecute() {
        String engineId = this.keyValue.get(ReactorKeysEnum.DATABASE.getKey());
        if (engineId == null || engineId.trim().isEmpty()) {
            engineId = DEFAULT_ENGINE_ID;
        }

        QueryExecutor executor = new QueryExecutor(engineId);
        List<Map<String, String>> rows = executor.executeSelect(CAPABILITY_GROUP_QUERY);

        // Collect distinct System and CapabilityGroup URIs for label extraction.
        List<String> systemUris = new ArrayList<>();
        List<String> groupUris = new ArrayList<>();
        for (Map<String, String> row : rows) {
            String sys = row.get("System");
            String grp = row.get("CapabilityGroup");
            if (sys != null && !systemUris.contains(sys)) {
                systemUris.add(sys);
            }
            if (grp != null && !groupUris.contains(grp)) {
                groupUris.add(grp);
            }
        }

        Map<String, String> systemLabelMap = SimilarityChartingUtils.buildSystemLabelMap(systemUris);
        Map<String, String> groupLabelMap = SimilarityChartingUtils.buildSystemLabelMap(groupUris);

        // Build Map<groupLabel, List<systemLabel>>.
        Map<String, List<String>> result = new LinkedHashMap<>();
        for (Map<String, String> row : rows) {
            String sysUri = row.get("System");
            String grpUri = row.get("CapabilityGroup");
            if (sysUri == null || grpUri == null) {
                continue;
            }

            String groupLabel = groupLabelMap.getOrDefault(grpUri, grpUri);
            String sysLabel = systemLabelMap.getOrDefault(sysUri, sysUri);

            result.computeIfAbsent(groupLabel, k -> new ArrayList<>()).add(sysLabel);
        }

        // Sort each system list for deterministic ordering.
        for (List<String> systems : result.values()) {
            Collections.sort(systems);
        }

        return new NounMetadata(result, PixelDataType.MAP);
    }

    /**
     * Returns a description of this reactor's purpose, behavior, and output contract.
     * Used by MakePixelMCP to generate high-quality MCP tool schemas.
     *
     * @return reactor description string
     */
    public String getReactorDescription() {
        return "Read-only lookup reactor that queries TAP_Core_Data RDF engine for System-CapabilityGroup "
            + "relationships and returns a map of CapabilityGroup labels to System labels. Used for client-side "
            + "filtering in the System Similarity heatmap UI. No side effects, no var-store writes. "
            + "Output: MAP where keys are CapabilityGroup display labels and values are sorted lists of "
            + "System display labels belonging to that group.";
    }

    /**
     * Returns a description for a specific parameter key.
     * Used by MakePixelMCP to generate parameter-level MCP tool schema descriptions.
     *
     * @param key the parameter key
     * @return parameter description string, or null if key is not recognized
     */
    public String getDescriptionForKey(String key) {
        switch (key) {
            case "database":
                return "Optional String UUID of the RDF engine to query (default: TAP_Core_Data "
                    + "133db94b-4371-4763-bff9-edf7e5ed021b). Must contain System and CapabilityGroup ontology triples.";
            default:
                return null;
        }
    }
}
