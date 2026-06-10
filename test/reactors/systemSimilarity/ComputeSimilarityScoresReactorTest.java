package reactors.systemSimilarity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;

import prerna.sablecc2.om.PixelDataType;
import prerna.sablecc2.om.VarStore;
import prerna.sablecc2.om.nounmeta.NounMetadata;
import reactors.BaseReactorTest;

/**
 * Unit tests for {@link ComputeSimilarityScoresReactor}.
 *
 * <p>Focuses on verifying that partial pairs (pairs missing one or more
 * categories) are returned in both DBS and non-DBS modes.
 */
public class ComputeSimilarityScoresReactorTest extends BaseReactorTest {

    // Two variables (categories)
    private static final String VAR_A = "Environment";
    private static final String VAR_B = "User_Types";

    // System pair keys
    private static final String PAIR_FULL = "SysA-SysB";
    private static final String PAIR_PARTIAL = "SysA-SysC";

    @Mock
    private VarStore varStore;

    private Map<String, Map<String, Map<String, Object>>> paramDataHash;
    private Map<String, Map<String, Object>> keyHash;

    @BeforeEach
    void setupVarStore() {
        // Build paramDataHash: VAR_A has both pairs, VAR_B only has PAIR_FULL
        paramDataHash = new LinkedHashMap<>();

        Map<String, Map<String, Object>> varABucket = new LinkedHashMap<>();
        varABucket.put(PAIR_FULL, scoreCell(80.0));
        varABucket.put(PAIR_PARTIAL, scoreCell(60.0));
        paramDataHash.put(VAR_A, varABucket);

        Map<String, Map<String, Object>> varBBucket = new LinkedHashMap<>();
        varBBucket.put(PAIR_FULL, scoreCell(70.0));
        // PAIR_PARTIAL is missing from VAR_B → partial data
        paramDataHash.put(VAR_B, varBBucket);

        // Build keyHash
        keyHash = new LinkedHashMap<>();
        keyHash.put(PAIR_FULL, pairEntry("SystemA", "SystemB"));
        keyHash.put(PAIR_PARTIAL, pairEntry("SystemA", "SystemC"));

        // Stub var-store
        when(insight.getVarStore()).thenReturn(varStore);
        when(varStore.get(GetSystemSimilarityDataSourcesReactor.VARSTORE_PARAM_DATA_HASH))
                .thenReturn(new NounMetadata(paramDataHash, PixelDataType.MAP));
        when(varStore.get(GetSystemSimilarityDataSourcesReactor.VARSTORE_KEY_HASH))
                .thenReturn(new NounMetadata(keyHash, PixelDataType.MAP));
        when(varStore.get(GetSystemSimilarityDataSourcesReactor.VARSTORE_ALL_SYSTEMS))
                .thenReturn(new NounMetadata(List.of("SystemA", "SystemB", "SystemC"), PixelDataType.CUSTOM_DATA_STRUCTURE));
        when(varStore.get(GetSystemSimilarityDataSourcesReactor.VARSTORE_SYSTEM_LABEL_MAP))
                .thenReturn(new NounMetadata(new HashMap<String, String>(), PixelDataType.MAP));
        when(varStore.get(GetSystemSimilarityDataSourcesReactor.VARSTORE_DBS_MODE))
                .thenReturn(null); // non-DBS by default
    }

    @Test
    @SuppressWarnings("unchecked")
    void nonDbsMode_returnsPartialPairs() {
        // DBS mode is OFF (default from setupVarStore)
        ComputeSimilarityScoresReactor reactor = createReactor();

        NounMetadata result = reactor.doExecute();
        assertNotNull(result);

        Map<String, Object> payload = (Map<String, Object>) result.getValue();
        List<Object[]> data = (List<Object[]>) payload.get("data");
        List<Object[]> partialPairs = (List<Object[]>) payload.get("partialPairs");

        // PAIR_FULL should appear in data (complete across both vars)
        assertFalse(data.isEmpty(), "Should have at least one complete pair");
        assertEquals("SystemA", data.get(0)[0]);
        assertEquals("SystemB", data.get(0)[1]);

        // PAIR_PARTIAL should appear in partialPairs even though DBS is OFF
        assertNotNull(partialPairs, "partialPairs should not be null");
        assertFalse(partialPairs.isEmpty(), "Non-DBS mode should still return partial pairs");
        assertEquals("SystemA", partialPairs.get(0)[0]);
        assertEquals("SystemC", partialPairs.get(0)[1]);

        // Verify the partial pair carries VAR_A score but not VAR_B
        Map<String, Double> partialScores = (Map<String, Double>) partialPairs.get(0)[2];
        assertTrue(partialScores.containsKey(VAR_A), "Partial pair should have VAR_A score");
        assertFalse(partialScores.containsKey(VAR_B), "Partial pair should not have VAR_B score");
        assertEquals(60.0, partialScores.get(VAR_A), 0.001);
    }

    @Test
    @SuppressWarnings("unchecked")
    void dbsMode_returnsPartialPairs() {
        // Enable DBS mode
        when(varStore.get(GetSystemSimilarityDataSourcesReactor.VARSTORE_DBS_MODE))
                .thenReturn(new NounMetadata(Boolean.TRUE, PixelDataType.BOOLEAN));

        ComputeSimilarityScoresReactor reactor = createReactor();

        NounMetadata result = reactor.doExecute();
        Map<String, Object> payload = (Map<String, Object>) result.getValue();
        List<Object[]> partialPairs = (List<Object[]>) payload.get("partialPairs");

        assertNotNull(partialPairs, "partialPairs should not be null in DBS mode");
        assertFalse(partialPairs.isEmpty(), "DBS mode should return partial pairs");
        assertEquals("SystemA", partialPairs.get(0)[0]);
        assertEquals("SystemC", partialPairs.get(0)[1]);
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private ComputeSimilarityScoresReactor createReactor() {
        ComputeSimilarityScoresReactor reactor = new ComputeSimilarityScoresReactor();
        reactor.setInsight(insight);
        reactor.setNounStore(nounStore);
        return reactor;
    }

    private static Map<String, Object> scoreCell(double score) {
        Map<String, Object> cell = new HashMap<>();
        cell.put("Score", score);
        return cell;
    }

    private static Map<String, Object> pairEntry(String sys1, String sys2) {
        Map<String, Object> entry = new HashMap<>();
        entry.put("System1", sys1);
        entry.put("System2", sys2);
        return entry;
    }
}
