package reactors.systemSimilarity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

import java.lang.reflect.Field;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;

import prerna.sablecc2.om.GenRowStruct;
import prerna.sablecc2.om.PixelDataType;
import prerna.sablecc2.om.VarStore;
import prerna.sablecc2.om.nounmeta.NounMetadata;
import reactors.BaseReactorTest;

/**
 * Unit tests for {@link ComputeSimilarityScoresReactor}.
 *
 * <p>Covers the weighted-average aggregation semantics for
 * {@code specifiedWeights} (multipliers with default 1.0, not per-variable
 * score cutoffs), the global {@code minimumScore} composite filter, and the
 * {@code partialPairs} surface for pairs missing one or more categories.
 */
public class ComputeSimilarityScoresReactorTest extends BaseReactorTest {

    private static final String VAR_A = "Environment";
    private static final String VAR_B = "User_Types";

    private static final String PAIR_FULL = "SysA-SysB";
    private static final String PAIR_PARTIAL = "SysA-SysC";

    private static final double SCORE_A_FULL = 80.0;
    private static final double SCORE_B_FULL = 70.0;
    private static final double SCORE_A_PARTIAL = 60.0;

    @Mock
    private VarStore varStore;

    private Map<String, Map<String, Map<String, Object>>> paramDataHash;
    private Map<String, Map<String, Object>> keyHash;

    @BeforeEach
    void setupVarStore() {
        paramDataHash = new LinkedHashMap<>();

        Map<String, Map<String, Object>> varABucket = new LinkedHashMap<>();
        varABucket.put(PAIR_FULL, scoreCell(SCORE_A_FULL));
        varABucket.put(PAIR_PARTIAL, scoreCell(SCORE_A_PARTIAL));
        paramDataHash.put(VAR_A, varABucket);

        Map<String, Map<String, Object>> varBBucket = new LinkedHashMap<>();
        varBBucket.put(PAIR_FULL, scoreCell(SCORE_B_FULL));
        // PAIR_PARTIAL is missing from VAR_B → partial data
        paramDataHash.put(VAR_B, varBBucket);

        keyHash = new LinkedHashMap<>();
        keyHash.put(PAIR_FULL, pairEntry("SystemA", "SystemB"));
        keyHash.put(PAIR_PARTIAL, pairEntry("SystemA", "SystemC"));

        when(insight.getVarStore()).thenReturn(varStore);
        when(varStore.get(GetSystemSimilarityDataSourcesReactor.VARSTORE_PARAM_DATA_HASH))
                .thenReturn(new NounMetadata(paramDataHash, PixelDataType.MAP));
        when(varStore.get(GetSystemSimilarityDataSourcesReactor.VARSTORE_KEY_HASH))
                .thenReturn(new NounMetadata(keyHash, PixelDataType.MAP));
        when(varStore.get(GetSystemSimilarityDataSourcesReactor.VARSTORE_ALL_SYSTEMS))
                .thenReturn(new NounMetadata(
                        List.of("SystemA", "SystemB", "SystemC"),
                        PixelDataType.CUSTOM_DATA_STRUCTURE));
        when(varStore.get(GetSystemSimilarityDataSourcesReactor.VARSTORE_SYSTEM_LABEL_MAP))
                .thenReturn(new NounMetadata(new HashMap<String, String>(), PixelDataType.MAP));
    }

    // ── Default-weight (uniform mean) coverage + partial pairs ───────────────

    @Test
    @SuppressWarnings("unchecked")
    void noWeights_returnsCompletePairAndPartialPair() {
        ComputeSimilarityScoresReactor reactor = createReactor();

        Map<String, Object> payload = run(reactor);
        List<Object[]> data = (List<Object[]>) payload.get("data");
        List<Object[]> partialPairs = (List<Object[]>) payload.get("partialPairs");

        assertEquals(1, data.size(), "Only PAIR_FULL is complete across both vars");
        assertEquals("SystemA", data.get(0)[0]);
        assertEquals("SystemB", data.get(0)[1]);
        assertEquals((SCORE_A_FULL + SCORE_B_FULL) / 2.0, (double) data.get(0)[2], 0.001,
                "Default weights = 1.0 ⇒ uniform mean");

        assertNotNull(partialPairs);
        assertEquals(1, partialPairs.size(), "PAIR_PARTIAL must surface in partialPairs");
        assertEquals("SystemA", partialPairs.get(0)[0]);
        assertEquals("SystemC", partialPairs.get(0)[1]);
        Map<String, Double> partialScores = (Map<String, Double>) partialPairs.get(0)[2];
        assertTrue(partialScores.containsKey(VAR_A));
        assertFalse(partialScores.containsKey(VAR_B));
        assertEquals(SCORE_A_PARTIAL, partialScores.get(VAR_A), 0.001);
    }

    // ── Weighted-average semantics ───────────────────────────────────────────

    @Test
    @SuppressWarnings("unchecked")
    void customWeights_appliedAsMultipliers() {
        ComputeSimilarityScoresReactor reactor = createReactor();
        stubMapParam("specifiedWeights", Map.of(VAR_A, 3.0, VAR_B, 1.0));

        Map<String, Object> payload = run(reactor);
        List<Object[]> data = (List<Object[]>) payload.get("data");

        double expected = (3.0 * SCORE_A_FULL + 1.0 * SCORE_B_FULL) / (3.0 + 1.0);
        assertEquals(1, data.size());
        assertEquals(expected, (double) data.get(0)[2], 0.001);
    }

    @Test
    @SuppressWarnings("unchecked")
    void weightForUnweightedVarDefaultsToOne() {
        ComputeSimilarityScoresReactor reactor = createReactor();
        stubMapParam("specifiedWeights", Map.of(VAR_A, 5.0));

        Map<String, Object> payload = run(reactor);
        List<Object[]> data = (List<Object[]>) payload.get("data");

        double expected = (5.0 * SCORE_A_FULL + 1.0 * SCORE_B_FULL) / (5.0 + 1.0);
        assertEquals(expected, (double) data.get(0)[2], 0.001);
    }

    @Test
    @SuppressWarnings("unchecked")
    void highWeightOnLowScoringVar_doesNotDropPair() {
        // Replace VAR_A score for PAIR_FULL with a very low value to prove that a
        // high weight on that variable does not filter the pair out.
        paramDataHash.get(VAR_A).put(PAIR_FULL, scoreCell(10.0));

        ComputeSimilarityScoresReactor reactor = createReactor();
        stubMapParam("specifiedWeights", Map.of(VAR_A, 100.0));

        Map<String, Object> payload = run(reactor);
        List<Object[]> data = (List<Object[]>) payload.get("data");

        assertEquals(1, data.size(), "Pair must NOT be dropped because varScore < weight");
        double expected = (100.0 * 10.0 + 1.0 * SCORE_B_FULL) / (100.0 + 1.0);
        assertEquals(expected, (double) data.get(0)[2], 0.001);
    }

    @Test
    @SuppressWarnings("unchecked")
    void allZeroWeights_fallsBackToUniformMean() {
        ComputeSimilarityScoresReactor reactor = createReactor();
        stubMapParam("specifiedWeights", Map.of(VAR_A, 0.0, VAR_B, 0.0));

        Map<String, Object> payload = run(reactor);
        List<Object[]> data = (List<Object[]>) payload.get("data");

        assertEquals(1, data.size());
        assertEquals((SCORE_A_FULL + SCORE_B_FULL) / 2.0, (double) data.get(0)[2], 0.001,
                "All-zero weights ⇒ uniform 1/N fallback");
    }

    @Test
    @SuppressWarnings("unchecked")
    void negativeWeight_coercedToZero() {
        ComputeSimilarityScoresReactor reactor = createReactor();
        stubMapParam("specifiedWeights", Map.of(VAR_A, -5.0, VAR_B, 2.0));

        Map<String, Object> payload = run(reactor);
        List<Object[]> data = (List<Object[]>) payload.get("data");

        // VAR_A weight coerced to 0; only VAR_B contributes ⇒ composite = SCORE_B_FULL
        assertEquals(SCORE_B_FULL, (double) data.get(0)[2], 0.001);
    }

    @Test
    @SuppressWarnings("unchecked")
    void weightForUnselectedVar_isIgnored() {
        ComputeSimilarityScoresReactor reactor = createReactor();
        stubMapParam("specifiedWeights", Map.of("Not_A_Selected_Var", 99.0));

        Map<String, Object> payload = run(reactor);
        List<Object[]> data = (List<Object[]>) payload.get("data");

        assertEquals((SCORE_A_FULL + SCORE_B_FULL) / 2.0, (double) data.get(0)[2], 0.001,
                "Extra weight for unselected var must not affect composite");
    }

    // ── Global minimumScore composite filter ────────────────────────────────

    @Test
    @SuppressWarnings("unchecked")
    void globalMinimumScore_filtersComposite() {
        ComputeSimilarityScoresReactor reactor = createReactor();
        // Uniform mean for PAIR_FULL is 75.0 ⇒ excluded when threshold is 80.
        stubLiteralParam("minimumScore", "80");

        Map<String, Object> payload = run(reactor);
        List<Object[]> data = (List<Object[]>) payload.get("data");

        assertTrue(data.isEmpty(), "Composite below minimumScore must be excluded from data");
    }

    // ── Non-finite input sanitization ───────────────────────────────────────

    @Test
    @SuppressWarnings("unchecked")
    void nonFiniteWeights_treatedAsDefault() {
        ComputeSimilarityScoresReactor reactor = createReactor();
        Map<String, Object> raw = new LinkedHashMap<>();
        raw.put(VAR_A, Double.POSITIVE_INFINITY);
        raw.put(VAR_B, Double.NaN);
        stubRawMapParam("specifiedWeights", raw);

        Map<String, Object> payload = run(reactor);
        List<Object[]> data = (List<Object[]>) payload.get("data");

        // Both non-finite weights are dropped at parse time ⇒ both vars default
        // to weight 1.0 ⇒ uniform mean, no NaN composite.
        double composite = (double) data.get(0)[2];
        assertTrue(Double.isFinite(composite), "Composite must be finite");
        assertEquals((SCORE_A_FULL + SCORE_B_FULL) / 2.0, composite, 0.001);
    }

    @Test
    @SuppressWarnings("unchecked")
    void nonFiniteMinimumScore_treatedAsNoThreshold() {
        ComputeSimilarityScoresReactor reactor = createReactor();
        stubLiteralParam("minimumScore", "NaN");

        Map<String, Object> payload = run(reactor);
        List<Object[]> data = (List<Object[]>) payload.get("data");

        // NaN threshold must not silently disable filtering AND must not exclude
        // all pairs; sanitization coerces it to 0 (no threshold).
        assertEquals(1, data.size());
        assertEquals((SCORE_A_FULL + SCORE_B_FULL) / 2.0, (double) data.get(0)[2], 0.001);
    }

    // ── Echoed response field rename ────────────────────────────────────────

    @Test
    void responsePayload_usesSpecifiedWeightsUsedKey() {
        ComputeSimilarityScoresReactor reactor = createReactor();
        Map<String, Object> payload = run(reactor);

        assertTrue(payload.containsKey("specifiedWeightsUsed"),
                "Response must echo weights under specifiedWeightsUsed");
        assertFalse(payload.containsKey("minimumWeightsUsed"),
                "Legacy minimumWeightsUsed key must no longer be emitted");
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    @SuppressWarnings("unchecked")
    private Map<String, Object> run(ComputeSimilarityScoresReactor reactor) {
        NounMetadata result = reactor.doExecute();
        assertNotNull(result);
        return (Map<String, Object>) result.getValue();
    }

    private ComputeSimilarityScoresReactor createReactor() {
        ComputeSimilarityScoresReactor reactor = new ComputeSimilarityScoresReactor();
        reactor.setInsight(insight);
        reactor.setNounStore(nounStore);
        // curRow defaults to null on AbstractReactor; getMap() dereferences it
        // unconditionally, so seed an empty GenRowStruct for tests that drive
        // the reactor directly via doExecute().
        setField(reactor, "curRow", new GenRowStruct());
        return reactor;
    }

    private void stubMapParam(String name, Map<String, ? extends Number> map) {
        GenRowStruct grs = new GenRowStruct();
        Map<String, Object> typed = new LinkedHashMap<>(map);
        grs.add(new NounMetadata(typed, PixelDataType.MAP));
        when(nounStore.getGenRowStruct(name)).thenReturn(grs);
    }

    private void stubRawMapParam(String name, Map<String, Object> map) {
        GenRowStruct grs = new GenRowStruct();
        grs.add(new NounMetadata(map, PixelDataType.MAP));
        when(nounStore.getGenRowStruct(name)).thenReturn(grs);
    }

    private void stubLiteralParam(String name, String value) {
        GenRowStruct grs = new GenRowStruct();
        grs.addLiteral(value);
        when(nounStore.getGenRowStruct(name)).thenReturn(grs);
    }

    private static void setField(Object target, String fieldName, Object value) {
        try {
            Field field = findField(target.getClass(), fieldName);
            field.setAccessible(true);
            field.set(target, value);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Failed to set " + fieldName, e);
        }
    }

    private static Field findField(Class<?> cls, String name) throws NoSuchFieldException {
        Class<?> c = cls;
        while (c != null) {
            try {
                return c.getDeclaredField(name);
            } catch (NoSuchFieldException ignored) {
                c = c.getSuperclass();
            }
        }
        throw new NoSuchFieldException(name);
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
