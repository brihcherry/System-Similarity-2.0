import { useCallback, useState } from "react";
import { useAppContext } from "@/contexts";
import {
    fetchInitialHeatmapFromReactor,
    introspectPlaysheet,
    getParamDataHash,
} from "@/features/systemSimilarity/api/systemSimilarityApi";
import type {
    IntrospectResponse,
    OutputResponse,
    ParamDataHashResponse,
} from "@/features/systemSimilarity/types";

type Step = "idle" | "loading-heatmap" | "ready" | "loading-introspect" | "loading-hash" | "loading-keyhash" | "loading-compute-params" | "loading-compare" | "loading-pipeline" | "loading-pipeline-compare" | "loading-labelmap" | "loading-probe";

const ALL_VARIABLES = [
    "Deployment_(Theater/Garrison)",
    "Business_Processes_Supported",
    "User_Types",
    "Data_and_Business_Logic_Supported",
    "User_Interface_Types_(PC/Mobile/etc.)",
    "Activities_Supported",
    "Transactional_(Yes/No)",
] as const;

interface ComparisonMismatch {
    pair: string;
    newScore: number;
    legacyScore: number;
    diff: number;
}

interface ComparisonResult {
    newPairCount: number;
    legacyPairCount: number;
    matchCount: number;
    mismatchCount: number;
    onlyInNewCount: number;
    onlyInLegacyCount: number;
    mismatches: ComparisonMismatch[];
    onlyInNew: { pair: string; score: number }[];
    onlyInLegacy: { pair: string; score: number }[];
    tolerance: number;
}

interface VariableScoreComparison {
    variable: string;
    newPairCount: number;
    legacyPairCount: number;
    matchCount: number;
    mismatchCount: number;
    onlyInNewCount: number;
    onlyInLegacyCount: number;
    allMatch: boolean;
    sampleMismatches: { pair: string; newScore: number; legacyScore: number; diff: number }[];
    sampleOnlyInNew: { pair: string; score: number }[];
    sampleOnlyInLegacy: { pair: string; score: number }[];
}

interface PipelineScoreComparisonResult {
    tolerance: number;
    variableCount: number;
    allMatch: boolean;
    totalNewPairs: number;
    totalLegacyPairs: number;
    totalMatches: number;
    totalMismatches: number;
    totalOnlyInNew: number;
    totalOnlyInLegacy: number;
    variables: VariableScoreComparison[];
}

/** Build a directional score map from [System1, System2, Score] rows. */
function buildScoreMap(data: unknown[][]): Map<string, number> {
    const map = new Map<string, number>();
    for (const row of data) {
        const key = `${String(row[0])}|${String(row[1])}`;
        map.set(key, Number(row[2]));
    }
    return map;
}

/** Compare two score maps and produce a ComparisonResult. */
function compareScoreMaps(
    newMap: Map<string, number>,
    legacyMap: Map<string, number>,
    tolerance = 0.01,
): ComparisonResult {
    const mismatches: ComparisonMismatch[] = [];
    const onlyInNew: { pair: string; score: number }[] = [];
    const onlyInLegacy: { pair: string; score: number }[] = [];
    let matchCount = 0;

    for (const [key, newScore] of newMap) {
        const legacyScore = legacyMap.get(key);
        if (legacyScore === undefined) {
            onlyInNew.push({ pair: key, score: newScore });
        } else if (Math.abs(newScore - legacyScore) <= tolerance) {
            matchCount++;
        } else {
            mismatches.push({ pair: key, newScore, legacyScore, diff: Math.abs(newScore - legacyScore) });
        }
    }

    for (const [key, legacyScore] of legacyMap) {
        if (!newMap.has(key)) {
            onlyInLegacy.push({ pair: key, score: legacyScore });
        }
    }

    mismatches.sort((a, b) => b.diff - a.diff);

    return {
        newPairCount: newMap.size,
        legacyPairCount: legacyMap.size,
        matchCount,
        mismatchCount: mismatches.length,
        onlyInNewCount: onlyInNew.length,
        onlyInLegacyCount: onlyInLegacy.length,
        mismatches,
        onlyInNew,
        onlyInLegacy,
        tolerance,
    };
}

/** Triggers a browser download of a string as a file. */
function downloadFile(filename: string, content: string) {
    const blob = new Blob([content], { type: "application/json" });
    const url = URL.createObjectURL(blob);
    const a = document.createElement("a");
    a.href = url;
    a.download = filename;
    document.body.appendChild(a);
    a.click();
    document.body.removeChild(a);
    URL.revokeObjectURL(url);
}

/**
 * Debug page that extracts the raw paramDataHash from the cached legacy
 * playsheet and exports it as downloadable JSON files.
 */
export const DebugParamDataPage = () => {
    const { runPixel } = useAppContext();

    const [step, setStep] = useState<Step>("idle");
    const [error, setError] = useState<string | null>(null);
    const [heatmapLoaded, setHeatmapLoaded] = useState(false);
    const [legacyOutput, setLegacyOutput] = useState<OutputResponse | null>(null);

    const [introspectData, setIntrospectData] = useState<IntrospectResponse | null>(null);
    const [paramHashData, setParamHashData] = useState<ParamDataHashResponse | null>(null);
    const [exportingVar, setExportingVar] = useState<string | null>(null);

    // Parameterized test state
    const [varChecked, setVarChecked] = useState<Record<string, boolean>>(
        () => Object.fromEntries(ALL_VARIABLES.map(v => [v, true]))
    );
    const [varWeights, setVarWeights] = useState<Record<string, string>>(
        () => Object.fromEntries(ALL_VARIABLES.map(v => [v, ""]))
    );
    const [paramComputeResult, setParamComputeResult] = useState<Record<string, unknown> | null>(null);
    const [comparisonResult, setComparisonResult] = useState<ComparisonResult | null>(null);

    // Pipeline comparison state
    const [pipelineSummary, setPipelineSummary] = useState<Record<string, unknown> | null>(null);
    const [pipelineComparison, setPipelineComparison] = useState<PipelineScoreComparisonResult | null>(null);
    const [pipelineError, setPipelineError] = useState<string | null>(null);

    // Label map debug state
    const [labelMapData, setLabelMapData] = useState<Record<string, unknown> | null>(null);

    // Raw score probe state
    const [probeKey, setProbeKey] = useState("");
    const [probeData, setProbeData] = useState<Record<string, unknown> | null>(null);

    // Step 1: Ensure heatmap is loaded (populates the cache) and new pipeline var-store is warm
    const loadHeatmap = useCallback(async () => {
        setStep("loading-heatmap");
        setError(null);
        try {
            // Load legacy heatmap and new pipeline data sources in parallel
            const [output] = await Promise.all([
                fetchInitialHeatmapFromReactor(runPixel),
                runPixel('GetSystemSimilarityDataSources(database=["133db94b-4371-4763-bff9-edf7e5ed021b"])'),
            ]);
            setLegacyOutput(output);
            setHeatmapLoaded(true);
            setStep("ready");
        } catch (err) {
            setError(err instanceof Error ? err.message : "Failed to load heatmap");
            setStep("idle");
        }
    }, [runPixel]);

    // Step 2: Introspect the playsheet
    const loadIntrospect = useCallback(async () => {
        setStep("loading-introspect");
        setError(null);
        try {
            const data = await introspectPlaysheet(runPixel);
            setIntrospectData(data);
            setStep("ready");
        } catch (err) {
            setError(err instanceof Error ? err.message : "Introspection failed");
            setStep("ready");
        }
    }, [runPixel]);

    // Step 3: Fetch full paramDataHash and export as file
    const exportFullHash = useCallback(async () => {
        setStep("loading-hash");
        setError(null);
        try {
            const data = await getParamDataHash(runPixel);
            setParamHashData(data);
            downloadFile("paramDataHash_full.json", JSON.stringify(data, null, 2));
            setStep("ready");
        } catch (err) {
            setError(err instanceof Error ? err.message : "Failed to fetch paramDataHash");
            setStep("ready");
        }
    }, [runPixel]);

    // Export keyHash (pair key → pre-split system names)
    const exportKeyHash = useCallback(async () => {
        setStep("loading-keyhash");
        setError(null);
        try {
            const data = await runPixel<Record<string, unknown>>("GetKeyHash();");
            downloadFile("keyHash.json", JSON.stringify(data, null, 2));
            setStep("ready");
        } catch (err) {
            setError(err instanceof Error ? err.message : "Failed to export keyHash");
            setStep("ready");
        }
    }, [runPixel]);

    // Step 4: Fetch a single variable's data and export as file
    const exportVariable = useCallback(async (variable: string) => {
        setExportingVar(variable);
        setStep("loading-hash");
        setError(null);
        try {
            const data = await getParamDataHash(runPixel, variable);
            if (data.error) {
                setError(data.error);
            } else {
                const safeName = variable.replace(/[^a-zA-Z0-9_()-]/g, "_");
                downloadFile(`paramDataHash_${safeName}.json`, JSON.stringify(data, null, 2));
            }
            setStep("ready");
        } catch (err) {
            setError(err instanceof Error ? err.message : "Failed to fetch variable data");
            setStep("ready");
        } finally {
            setExportingVar(null);
        }
    }, [runPixel]);

    // Run ComputeSimilarityScores with user-specified parameters
    const runComputeWithParams = useCallback(async () => {
        setStep("loading-compute-params");
        setError(null);
        setParamComputeResult(null);
        try {
            const selectedVars = ALL_VARIABLES.filter(v => varChecked[v]);
            if (selectedVars.length === 0) {
                setError("No variables selected.");
                setStep("ready");
                return;
            }
            const varsJson = JSON.stringify(selectedVars);

            // Build specifiedWeights from non-empty weight inputs
            const weights: Record<string, number> = {};
            for (const v of selectedVars) {
                const w = varWeights[v];
                if (w !== "" && !isNaN(Number(w))) {
                    weights[v] = Number(w);
                }
            }
            const hasWeights = Object.keys(weights).length > 0;
            const weightsJson = JSON.stringify(weights);

            const pixel = hasWeights
                ? `ComputeSimilarityScores(selectedVars=${varsJson}, specifiedWeights=${weightsJson});`
                : `ComputeSimilarityScores(selectedVars=${varsJson});`;

            const result = await runPixel<Record<string, unknown>>(pixel);
            setParamComputeResult(result);
            setStep("ready");
        } catch (err) {
            setError(err instanceof Error ? err.message : "ComputeSimilarityScores (params) failed");
            setStep("ready");
        }
    }, [runPixel, varChecked, varWeights]);

    // Run both reactors with same params and compare outputs
    const runCompareWithLegacy = useCallback(async () => {
        setStep("loading-compare");
        setError(null);
        setComparisonResult(null);
        try {
            const selectedVars = ALL_VARIABLES.filter(v => varChecked[v]);
            if (selectedVars.length === 0) {
                setError("No variables selected.");
                setStep("ready");
                return;
            }
            const varsJson = JSON.stringify(selectedVars);

            const weights: Record<string, number> = {};
            for (const v of selectedVars) {
                const w = varWeights[v];
                if (w !== "" && !isNaN(Number(w))) {
                    weights[v] = Number(w);
                }
            }
            const hasWeights = Object.keys(weights).length > 0;
            const weightsJson = JSON.stringify(weights);

            // Build pixel strings for both reactors
            const newPixel = hasWeights
                ? `ComputeSimilarityScores(selectedVars=${varsJson}, specifiedWeights=${weightsJson});`
                : `ComputeSimilarityScores(selectedVars=${varsJson});`;

            const legacyPixel = hasWeights
                ? `RefreshSystemSimilarityHeatmap(selectedVars=${varsJson}, specifiedWeights=${weightsJson});`
                : `RefreshSystemSimilarityHeatmap(selectedVars=${varsJson});`;

            // Call both reactors (var-store already populated during page load)
            const [newResult, legacyResult] = await Promise.all([
                runPixel<Record<string, unknown>>(newPixel),
                runPixel<Record<string, unknown>>(legacyPixel),
            ]);

            const newData = (newResult as Record<string, unknown>).data as unknown[][];
            const legacyData = (legacyResult as Record<string, unknown>).data as unknown[][];

            if (!Array.isArray(newData) || !Array.isArray(legacyData)) {
                setError("Unexpected response shape from one or both reactors.");
                setStep("ready");
                return;
            }

            const newMap = buildScoreMap(newData);
            const legacyMap = buildScoreMap(legacyData);
            const result = compareScoreMaps(newMap, legacyMap);

            setComparisonResult(result);
            setStep("ready");
        } catch (err) {
            setError(err instanceof Error ? err.message : "Comparison failed");
            setStep("ready");
        }
    }, [runPixel, varChecked, varWeights]);

    // Run GetSystemSimilarityDataSources (queries + scoring + charting in one step)
    const runPipeline = useCallback(async () => {
        setStep("loading-pipeline");
        setPipelineError(null);
        setPipelineSummary(null);
        try {
            const result = await runPixel<{ paramDataHash?: Record<string, Record<string, unknown>> }>(
                'GetSystemSimilarityDataSources(database=["133db94b-4371-4763-bff9-edf7e5ed021b"]);'
            );

            // Extract summary from the returned paramDataHash
            const pdh = result?.paramDataHash ?? {};
            const pairCountsByVariable: Record<string, number> = {};
            let totalPairKeys = 0;
            for (const [varName, pairs] of Object.entries(pdh)) {
                const count = Object.keys(pairs as Record<string, unknown>).length;
                pairCountsByVariable[varName] = count;
                totalPairKeys += count;
            }
            setPipelineSummary({
                variableCount: Object.keys(pdh).length,
                totalPairKeys,
                pairCountsByVariable,
            });

            setStep("ready");
        } catch (err) {
            setPipelineError(err instanceof Error ? err.message : "Pipeline failed");
            setStep("ready");
        }
    }, [runPixel]);

    // Run reactor then do score-level comparison against legacy paramDataHash
    const runPipelineAndCompare = useCallback(async () => {
        setStep("loading-pipeline-compare");
        setPipelineError(null);
        setPipelineComparison(null);
        try {
            // Step 1: Run reactor (queries + scoring, caches paramDataHash in var-store)
            const result = await runPixel<{ paramDataHash?: Record<string, Record<string, unknown>> }>(
                'GetSystemSimilarityDataSources(database=["133db94b-4371-4763-bff9-edf7e5ed021b"]);'
            );

            // Extract summary from the returned paramDataHash
            const pdh = result?.paramDataHash ?? {};
            const pairCountsByVariable: Record<string, number> = {};
            let totalPairKeys = 0;
            for (const [varName, pairs] of Object.entries(pdh)) {
                const count = Object.keys(pairs as Record<string, unknown>).length;
                pairCountsByVariable[varName] = count;
                totalPairKeys += count;
            }
            setPipelineSummary({
                variableCount: Object.keys(pdh).length,
                totalPairKeys,
                pairCountsByVariable,
            });

            // Step 2: Server-side score-level comparison against legacy
            const comparison = await runPixel<PipelineScoreComparisonResult>('CompareParamDataHash();');
            setPipelineComparison(comparison);

            setStep("ready");
        } catch (err) {
            setPipelineError(err instanceof Error ? err.message : "Pipeline comparison failed");
            setStep("ready");
        }
    }, [runPixel]);

    // Fetch label map from GetSystemLabelMap reactor
    const runGetLabelMap = useCallback(async () => {
        setStep("loading-labelmap");
        setError(null);
        setLabelMapData(null);
        try {
            const result = await runPixel<Record<string, unknown>>("GetSystemLabelMap();");
            setLabelMapData(result);
            setStep("ready");
        } catch (err) {
            setError(err instanceof Error ? err.message : "GetSystemLabelMap failed");
            setStep("ready");
        }
    }, [runPixel]);

    // Probe raw scores for a specific pair key
    const runProbeRawScores = useCallback(async () => {
        if (!probeKey.trim()) return;
        setStep("loading-probe");
        setError(null);
        setProbeData(null);
        try {
            const result = await runPixel<Record<string, unknown>>(
                `ProbeRawScores(pairKey=["${probeKey.trim()}"])`
            );
            setProbeData(result);
            setStep("ready");
        } catch (err) {
            setError(err instanceof Error ? err.message : "ProbeRawScores failed");
            setStep("ready");
        }
    }, [runPixel, probeKey]);

    const isLoading = step.startsWith("loading");

    return (
        <div style={{ padding: "24px", fontFamily: "monospace", maxWidth: "1200px", margin: "0 auto" }}>
            <h1 style={{ fontSize: "1.5rem", marginBottom: "8px" }}>
                Debug: paramDataHash Inspector
            </h1>
            <p style={{ color: "#666", marginBottom: "24px" }}>
                Many of the tools on this page rely on legacy backend reactors to perform comparisons. 
                If a tool returns an error, please reload the heatmap data and try again.
            </p>

            {/* Status bar */}
            {(error || step === "loading-heatmap" || heatmapLoaded) && (
            <div style={{
                padding: "12px 16px",
                marginBottom: "16px",
                borderRadius: "6px",
                background: error ? "#fee" : heatmapLoaded ? "#efe" : "#fff8e1",
                border: `1px solid ${error ? "#fcc" : heatmapLoaded ? "#cec" : "#ffe082"}`,
            }}>
                {error && <span style={{ color: "#c00" }}>Error: {error}</span>}
                {!error && step === "loading-heatmap" && "Loading initial heatmap (populating cache)..."}
                {!error && heatmapLoaded && step !== "loading-heatmap" && (
                    <span style={{ color: "#060" }}>Cache populated. Ready to inspect.</span>
                )}
            </div>
            )}

            {/* Top-level action buttons */}
            <div style={{ display: "flex", gap: "8px", marginBottom: "24px", flexWrap: "wrap" }}>
                <button
                    onClick={loadHeatmap}
                    disabled={isLoading}
                    style={buttonStyle}
                >
                    {step === "loading-heatmap" ? "Loading..." : "Reload Heatmap Cache"}
                </button>
                <button
                    onClick={loadIntrospect}
                    disabled={isLoading || !heatmapLoaded}
                    style={buttonStyle}
                >
                    {step === "loading-introspect" ? "Loading..." : "Introspect Playsheet"}
                </button>
            </div>

            {/* ── Export Section ─────────────────────────────────────────── */}
            <CollapsibleSection title="Export" defaultOpen>
                <div style={{ display: "flex", gap: "8px", flexWrap: "wrap" }}>
                    <button
                        onClick={() => {
                            if (legacyOutput) {
                                downloadFile("legacyHeatmapOutput.json", JSON.stringify(legacyOutput.data, null, 2));
                            }
                        }}
                        disabled={isLoading || !legacyOutput}
                        style={buttonStyle}
                    >
                        Export Legacy Heatmap Output
                    </button>
                    <button
                        onClick={exportFullHash}
                        disabled={isLoading || !heatmapLoaded}
                        style={buttonStyle}
                    >
                        {step === "loading-hash" && !exportingVar ? "Exporting..." : "Export Full paramDataHash"}
                    </button>
                    <button
                        onClick={exportKeyHash}
                        disabled={isLoading || !heatmapLoaded}
                        style={buttonStyle}
                    >
                        {step === "loading-keyhash" ? "Exporting..." : "Export keyHash"}
                    </button>
                </div>
            </CollapsibleSection>

            {/* ── Test: ComputeSimilarityScores ──────────────────────────── */}
            <CollapsibleSection title="Test: ComputeSimilarityScores" defaultOpen>
                <table style={{ ...tableStyle, marginBottom: "12px" }}>
                    <thead>
                        <tr>
                            <th style={{ ...thStyle, width: "40px" }}>Use</th>
                            <th style={thStyle}>Variable</th>
                            <th style={{ ...thStyle, width: "120px" }}>Min Score</th>
                        </tr>
                    </thead>
                    <tbody>
                        {ALL_VARIABLES.map(v => (
                            <tr key={v} style={{ opacity: varChecked[v] ? 1 : 0.5 }}>
                                <td style={tdStyle}>
                                    <input
                                        type="checkbox"
                                        checked={varChecked[v]}
                                        onChange={e => setVarChecked(prev => ({ ...prev, [v]: e.target.checked }))}
                                    />
                                </td>
                                <td style={tdStyle}>{v}</td>
                                <td style={tdStyle}>
                                    <input
                                        type="number"
                                        placeholder="—"
                                        value={varWeights[v]}
                                        onChange={e => setVarWeights(prev => ({ ...prev, [v]: e.target.value }))}
                                        disabled={!varChecked[v]}
                                        style={{ width: "80px", padding: "2px 6px", fontFamily: "monospace", fontSize: "0.8rem" }}
                                    />
                                </td>
                            </tr>
                        ))}
                    </tbody>
                </table>

                <div style={{ display: "flex", gap: "8px", flexWrap: "wrap", marginBottom: "16px" }}>
                    <button
                        onClick={runComputeWithParams}
                        disabled={isLoading}
                        style={buttonStyle}
                    >
                        {step === "loading-compute-params" ? "Running..." : "Run with Parameters"}
                    </button>
                    <button
                        onClick={runCompareWithLegacy}
                        disabled={isLoading}
                        style={{ ...buttonStyle, background: "#f0f4ff", borderColor: "#99b" }}
                    >
                        {step === "loading-compare" ? "Comparing..." : "Run & Compare with Legacy"}
                    </button>
                    <button
                        onClick={() => {
                            setVarChecked(Object.fromEntries(ALL_VARIABLES.map(v => [v, true])));
                            setVarWeights(Object.fromEntries(ALL_VARIABLES.map(v => [v, ""])));
                        }}
                        disabled={isLoading}
                        style={{ ...buttonStyle, color: "#666" }}
                    >
                        Reset All
                    </button>
                </div>

                {/* Parameterized result */}
                {paramComputeResult && (
                    <CollapsibleSection title={`Result: ${String((paramComputeResult as Record<string, unknown>).pairsAboveThreshold ?? "?")} pairs above threshold`} defaultOpen>
                        <table style={tableStyle}>
                            <tbody>
                                <tr><td style={tdStyle}><strong>Variables used</strong></td><td style={tdStyle}>{String((paramComputeResult.variablesUsed as string[])?.join(", ") ?? "—")}</td></tr>
                                <tr><td style={tdStyle}><strong>Min weights used</strong></td><td style={tdStyle}>{JSON.stringify(paramComputeResult.minimumWeightsUsed ?? null)}</td></tr>
                                <tr><td style={tdStyle}><strong>Total pairs evaluated</strong></td><td style={tdStyle}>{String(paramComputeResult.totalPairsEvaluated)}</td></tr>
                                <tr><td style={tdStyle}><strong>Pairs above threshold</strong></td><td style={tdStyle}>{String(paramComputeResult.pairsAboveThreshold)}</td></tr>
                            </tbody>
                        </table>
                        {Array.isArray(paramComputeResult.data) && (
                            <div style={{ marginTop: "8px" }}>
                                <button
                                    onClick={() => downloadFile("computeResult_params.json", JSON.stringify(paramComputeResult, null, 2))}
                                    style={{ ...buttonStyle, padding: "4px 10px", fontSize: "0.75rem" }}
                                >
                                    Export Result JSON
                                </button>
                            </div>
                        )}
                    </CollapsibleSection>
                )}

                {/* Comparison result (new vs legacy with parameters) */}
                {comparisonResult && (
                    <CollapsibleSection title="Comparison: New vs Legacy (with parameters)" defaultOpen>
                        <div style={{ marginBottom: "12px" }}>
                            <table style={tableStyle}>
                                <tbody>
                                    <tr><td style={tdStyle}><strong>New pair count</strong></td><td style={tdStyle}>{comparisonResult.newPairCount}</td></tr>
                                    <tr><td style={tdStyle}><strong>Legacy pair count</strong></td><td style={tdStyle}>{comparisonResult.legacyPairCount}</td></tr>
                                    <tr style={{ background: "#efe" }}><td style={tdStyle}><strong>Matches</strong></td><td style={tdStyle}>{comparisonResult.matchCount}</td></tr>
                                    <tr style={{ background: comparisonResult.mismatchCount > 0 ? "#fee" : undefined }}><td style={tdStyle}><strong>Mismatches</strong></td><td style={tdStyle}>{comparisonResult.mismatchCount}</td></tr>
                                    <tr style={{ background: comparisonResult.onlyInNewCount > 0 ? "#fff8e1" : undefined }}><td style={tdStyle}><strong>Only in new</strong></td><td style={tdStyle}>{comparisonResult.onlyInNewCount}</td></tr>
                                    <tr style={{ background: comparisonResult.onlyInLegacyCount > 0 ? "#fff8e1" : undefined }}><td style={tdStyle}><strong>Only in legacy</strong></td><td style={tdStyle}>{comparisonResult.onlyInLegacyCount}</td></tr>
                                    <tr><td style={tdStyle}><strong>Tolerance</strong></td><td style={tdStyle}>{comparisonResult.tolerance}</td></tr>
                                </tbody>
                            </table>
                        </div>

                        {comparisonResult.mismatches.length > 0 && (
                            <CollapsibleSection title={`Mismatches (${comparisonResult.mismatchCount})`} defaultOpen>
                                <table style={tableStyle}>
                                    <thead>
                                        <tr>
                                            <th style={thStyle}>Pair</th>
                                            <th style={thStyle}>New Score</th>
                                            <th style={thStyle}>Legacy Score</th>
                                            <th style={thStyle}>Diff</th>
                                        </tr>
                                    </thead>
                                    <tbody>
                                        {comparisonResult.mismatches.map((m, i) => (
                                            <tr key={i}>
                                                <td style={tdStyle}>{m.pair}</td>
                                                <td style={tdStyle}>{m.newScore.toFixed(6)}</td>
                                                <td style={tdStyle}>{m.legacyScore.toFixed(6)}</td>
                                                <td style={tdStyle}>{m.diff.toFixed(6)}</td>
                                            </tr>
                                        ))}
                                    </tbody>
                                </table>
                            </CollapsibleSection>
                        )}

                        {comparisonResult.onlyInLegacy.length > 0 && (
                            <CollapsibleSection title={`Only in legacy (${comparisonResult.onlyInLegacyCount})`}>
                                <table style={tableStyle}>
                                    <thead><tr><th style={thStyle}>Pair</th><th style={thStyle}>Legacy Score</th></tr></thead>
                                    <tbody>
                                        {comparisonResult.onlyInLegacy.map((m, i) => (
                                            <tr key={i}>
                                                <td style={tdStyle}>{m.pair}</td>
                                                <td style={tdStyle}>{m.score.toFixed(6)}</td>
                                            </tr>
                                        ))}
                                    </tbody>
                                </table>
                            </CollapsibleSection>
                        )}

                        {comparisonResult.onlyInNew.length > 0 && (
                            <CollapsibleSection title={`Only in new (${comparisonResult.onlyInNewCount})`}>
                                <table style={tableStyle}>
                                    <thead><tr><th style={thStyle}>Pair</th><th style={thStyle}>New Score</th></tr></thead>
                                    <tbody>
                                        {comparisonResult.onlyInNew.map((m, i) => (
                                            <tr key={i}>
                                                <td style={tdStyle}>{m.pair}</td>
                                                <td style={tdStyle}>{m.score.toFixed(6)}</td>
                                            </tr>
                                        ))}
                                    </tbody>
                                </table>
                            </CollapsibleSection>
                        )}

                        <div style={{ marginTop: "8px" }}>
                            <button
                                onClick={() => downloadFile("comparison_result.json", JSON.stringify(comparisonResult, null, 2))}
                                style={{ ...buttonStyle, padding: "4px 10px", fontSize: "0.75rem" }}
                            >
                                Export Comparison JSON
                            </button>
                        </div>
                    </CollapsibleSection>
                )}
            </CollapsibleSection>

            {/* ── Test: paramDataHash Pipeline ─────────────────────── */}
            <CollapsibleSection title="Test: paramDataHash Pipeline" defaultOpen={false}>
                <p style={{ color: "#666", fontSize: "0.8rem", marginBottom: "12px" }}>
                    Runs <code>GetSystemSimilarityDataSources()</code> which executes 7 SPARQL queries,
                    computes pairwise scores via ported SimilarityFunctions, and transforms results via
                    processHashForCharting. Then uses <code>CompareParamDataHash()</code> to perform a
                    score-level comparison against the legacy playsheet's cached paramDataHash.
                    Requires the legacy heatmap cache to be loaded first.
                </p>

                <div style={{ display: "flex", gap: "8px", flexWrap: "wrap", marginBottom: "16px" }}>
                    <button
                        onClick={runPipeline}
                        disabled={isLoading}
                        style={buttonStyle}
                    >
                        {step === "loading-pipeline" ? "Running Pipeline..." : "Run Pipeline Only"}
                    </button>
                    <button
                        onClick={runPipelineAndCompare}
                        disabled={isLoading}
                        style={{ ...buttonStyle, background: "#f0f4ff", borderColor: "#99b" }}
                    >
                        {step === "loading-pipeline-compare" ? "Comparing..." : "Run Pipeline & Compare with Legacy"}
                    </button>
                </div>

                {pipelineError && (
                    <div style={{ padding: "12px 16px", marginBottom: "12px", borderRadius: "6px", background: "#fee", border: "1px solid #fcc" }}>
                        <span style={{ color: "#c00" }}>Pipeline error: {pipelineError}</span>
                    </div>
                )}

                {pipelineSummary && (
                    <CollapsibleSection title={`Pipeline Result: ${String(pipelineSummary.variableCount)} variables, ${String(pipelineSummary.totalPairKeys)} total pair keys`} defaultOpen>
                        <table style={tableStyle}>
                            <thead>
                                <tr>
                                    <th style={thStyle}>Variable</th>
                                    <th style={thStyle}>Pair Count</th>
                                </tr>
                            </thead>
                            <tbody>
                                {pipelineSummary.pairCountsByVariable && Object.entries(pipelineSummary.pairCountsByVariable as Record<string, number>).map(([varName, count]) => (
                                    <tr key={varName}>
                                        <td style={tdStyle}>{varName}</td>
                                        <td style={tdStyle}>{count}</td>
                                    </tr>
                                ))}
                            </tbody>
                        </table>
                    </CollapsibleSection>
                )}

                {pipelineComparison && (
                    <CollapsibleSection title={`Score Comparison: ${pipelineComparison.allMatch ? "✅ All scores match" : "❌ Differences found"}`} defaultOpen>
                        {/* Overall summary */}
                        <table style={{ ...tableStyle, marginBottom: "16px" }}>
                            <tbody>
                                <tr><td style={tdStyle}><strong>Tolerance</strong></td><td style={tdStyle}>{pipelineComparison.tolerance}</td></tr>
                                <tr><td style={tdStyle}><strong>Variables</strong></td><td style={tdStyle}>{pipelineComparison.variableCount}</td></tr>
                                <tr><td style={tdStyle}><strong>Total new pairs</strong></td><td style={tdStyle}>{pipelineComparison.totalNewPairs.toLocaleString()}</td></tr>
                                <tr><td style={tdStyle}><strong>Total legacy pairs</strong></td><td style={tdStyle}>{pipelineComparison.totalLegacyPairs.toLocaleString()}</td></tr>
                                <tr style={{ background: "#efe" }}><td style={tdStyle}><strong>Total matches</strong></td><td style={tdStyle}>{pipelineComparison.totalMatches.toLocaleString()}</td></tr>
                                <tr style={{ background: pipelineComparison.totalMismatches > 0 ? "#fee" : undefined }}><td style={tdStyle}><strong>Total mismatches</strong></td><td style={tdStyle}>{pipelineComparison.totalMismatches.toLocaleString()}</td></tr>
                                <tr style={{ background: pipelineComparison.totalOnlyInNew > 0 ? "#fff8e1" : undefined }}><td style={tdStyle}><strong>Total only in new</strong></td><td style={tdStyle}>{pipelineComparison.totalOnlyInNew.toLocaleString()}</td></tr>
                                <tr style={{ background: pipelineComparison.totalOnlyInLegacy > 0 ? "#fff8e1" : undefined }}><td style={tdStyle}><strong>Total only in legacy</strong></td><td style={tdStyle}>{pipelineComparison.totalOnlyInLegacy.toLocaleString()}</td></tr>
                            </tbody>
                        </table>

                        {/* Per-variable breakdown */}
                        {pipelineComparison.variables.map((v) => (
                            <CollapsibleSection
                                key={v.variable}
                                title={`${v.allMatch ? "✅" : "❌"} ${v.variable} — ${v.matchCount.toLocaleString()} match, ${v.mismatchCount} mismatch, ${v.onlyInNewCount} only-new, ${v.onlyInLegacyCount} only-legacy`}
                                defaultOpen={!v.allMatch}
                            >
                                <table style={{ ...tableStyle, marginBottom: "12px" }}>
                                    <tbody>
                                        <tr><td style={tdStyle}><strong>New pairs</strong></td><td style={tdStyle}>{v.newPairCount.toLocaleString()}</td></tr>
                                        <tr><td style={tdStyle}><strong>Legacy pairs</strong></td><td style={tdStyle}>{v.legacyPairCount.toLocaleString()}</td></tr>
                                        <tr style={{ background: "#efe" }}><td style={tdStyle}><strong>Matches</strong></td><td style={tdStyle}>{v.matchCount.toLocaleString()}</td></tr>
                                        <tr style={{ background: v.mismatchCount > 0 ? "#fee" : undefined }}><td style={tdStyle}><strong>Mismatches</strong></td><td style={tdStyle}>{v.mismatchCount.toLocaleString()}</td></tr>
                                        <tr style={{ background: v.onlyInNewCount > 0 ? "#fff8e1" : undefined }}><td style={tdStyle}><strong>Only in new</strong></td><td style={tdStyle}>{v.onlyInNewCount.toLocaleString()}</td></tr>
                                        <tr style={{ background: v.onlyInLegacyCount > 0 ? "#fff8e1" : undefined }}><td style={tdStyle}><strong>Only in legacy</strong></td><td style={tdStyle}>{v.onlyInLegacyCount.toLocaleString()}</td></tr>
                                    </tbody>
                                </table>

                                {v.sampleMismatches.length > 0 && (
                                    <div style={{ marginBottom: "12px" }}>
                                        <strong style={{ fontSize: "0.8rem" }}>Sample mismatches ({v.sampleMismatches.length} of {v.mismatchCount.toLocaleString()}):</strong>
                                        <table style={tableStyle}>
                                            <thead>
                                                <tr>
                                                    <th style={thStyle}>Pair</th>
                                                    <th style={thStyle}>New Score</th>
                                                    <th style={thStyle}>Legacy Score</th>
                                                    <th style={thStyle}>Diff</th>
                                                </tr>
                                            </thead>
                                            <tbody>
                                                {v.sampleMismatches.map((m, i) => (
                                                    <tr key={i}>
                                                        <td style={tdStyle}>{m.pair}</td>
                                                        <td style={tdStyle}>{m.newScore.toFixed(4)}</td>
                                                        <td style={tdStyle}>{m.legacyScore.toFixed(4)}</td>
                                                        <td style={tdStyle}>{m.diff.toFixed(4)}</td>
                                                    </tr>
                                                ))}
                                            </tbody>
                                        </table>
                                    </div>
                                )}

                                {v.sampleOnlyInNew.length > 0 && (
                                    <div style={{ marginBottom: "12px" }}>
                                        <strong style={{ fontSize: "0.8rem" }}>Sample only in new ({v.sampleOnlyInNew.length} of {v.onlyInNewCount.toLocaleString()}):</strong>
                                        <table style={tableStyle}>
                                            <thead><tr><th style={thStyle}>Pair</th><th style={thStyle}>Score</th></tr></thead>
                                            <tbody>
                                                {v.sampleOnlyInNew.map((m, i) => (
                                                    <tr key={i}>
                                                        <td style={tdStyle}>{m.pair}</td>
                                                        <td style={tdStyle}>{m.score.toFixed(4)}</td>
                                                    </tr>
                                                ))}
                                            </tbody>
                                        </table>
                                    </div>
                                )}

                                {v.sampleOnlyInLegacy.length > 0 && (
                                    <div style={{ marginBottom: "12px" }}>
                                        <strong style={{ fontSize: "0.8rem" }}>Sample only in legacy ({v.sampleOnlyInLegacy.length} of {v.onlyInLegacyCount.toLocaleString()}):</strong>
                                        <table style={tableStyle}>
                                            <thead><tr><th style={thStyle}>Pair</th><th style={thStyle}>Score</th></tr></thead>
                                            <tbody>
                                                {v.sampleOnlyInLegacy.map((m, i) => (
                                                    <tr key={i}>
                                                        <td style={tdStyle}>{m.pair}</td>
                                                        <td style={tdStyle}>{m.score.toFixed(4)}</td>
                                                    </tr>
                                                ))}
                                            </tbody>
                                        </table>
                                    </div>
                                )}
                            </CollapsibleSection>
                        ))}

                        <div style={{ marginTop: "8px" }}>
                            <button
                                onClick={() => downloadFile("pipeline_comparison.json", JSON.stringify(pipelineComparison, null, 2))}
                                style={{ ...buttonStyle, padding: "4px 10px", fontSize: "0.75rem" }}
                            >
                                Export Comparison JSON
                            </button>
                        </div>
                    </CollapsibleSection>
                )}
            </CollapsibleSection>

            {/* ── Label Map Debug ────────────────────────────────────── */}
            <CollapsibleSection title="Debug: System Label Map" defaultOpen={false}>
                <div style={{ display: "flex", gap: "8px", flexWrap: "wrap", marginBottom: "16px" }}>
                    <button
                        onClick={runGetLabelMap}
                        disabled={isLoading || !heatmapLoaded}
                        style={buttonStyle}
                    >
                        {step === "loading-labelmap" ? "Loading..." : "Load Label Map"}
                    </button>
                    {labelMapData && (
                        <button
                            onClick={() => downloadFile("systemLabelMap.json", JSON.stringify(labelMapData, null, 2))}
                            style={{ ...buttonStyle, padding: "4px 10px", fontSize: "0.75rem" }}
                        >
                            Export Label Map JSON
                        </button>
                    )}
                </div>

                {labelMapData && (
                    <>
                        {/* Summary stats */}
                        <table style={{ ...tableStyle, marginBottom: "16px" }}>
                            <tbody>
                                <tr><td style={tdStyle}><strong>Total Systems</strong></td><td style={tdStyle}>{String(labelMapData.totalSystems)}</td></tr>
                                <tr><td style={tdStyle}><strong>Total Mappings</strong></td><td style={tdStyle}>{String(labelMapData.totalMappings)}</td></tr>
                                <tr><td style={tdStyle}><strong>Unique Labels</strong></td><td style={tdStyle}>{String(labelMapData.uniqueLabels)}</td></tr>
                                <tr style={{ background: Number(labelMapData.collisionCount) > 0 ? "#fff8e1" : undefined }}>
                                    <td style={tdStyle}><strong>Collisions</strong></td>
                                    <td style={tdStyle}>{String(labelMapData.collisionCount)}</td>
                                </tr>
                                <tr style={{ background: Number(labelMapData.duplicateLabelCount) > 0 ? "#fee" : undefined }}>
                                    <td style={tdStyle}><strong>Duplicate Labels</strong></td>
                                    <td style={tdStyle}>{String(labelMapData.duplicateLabelCount)}</td>
                                </tr>
                            </tbody>
                        </table>

                        {/* Collisions */}
                        {Array.isArray(labelMapData.collisions) && (labelMapData.collisions as Array<Record<string, unknown>>).length > 0 && (
                            <CollapsibleSection title={`Collisions (${(labelMapData.collisions as unknown[]).length})`} defaultOpen>
                                <table style={tableStyle}>
                                    <thead>
                                        <tr>
                                            <th style={thStyle}>Suffixed Label</th>
                                            <th style={thStyle}>Original Label</th>
                                            <th style={thStyle}>URIs</th>
                                            <th style={thStyle}>Base URIs</th>
                                        </tr>
                                    </thead>
                                    <tbody>
                                        {(labelMapData.collisions as Array<Record<string, unknown>>).map((c, i) => (
                                            <tr key={i}>
                                                <td style={tdStyle}>{String(c.label)}</td>
                                                <td style={tdStyle}>{String(c.originalLabel)}</td>
                                                <td style={{ ...tdStyle, fontSize: "0.7rem", wordBreak: "break-all" }}>
                                                    {(c.uris as string[]).join(", ")}
                                                </td>
                                                <td style={{ ...tdStyle, fontSize: "0.7rem", wordBreak: "break-all" }}>
                                                    {c.baseUris ? (c.baseUris as string[]).join(", ") : "—"}
                                                </td>
                                            </tr>
                                        ))}
                                    </tbody>
                                </table>
                            </CollapsibleSection>
                        )}

                        {/* Duplicate labels */}
                        {Array.isArray(labelMapData.duplicateLabels) && (labelMapData.duplicateLabels as Array<Record<string, unknown>>).length > 0 && (
                            <CollapsibleSection title={`Duplicate Labels (${(labelMapData.duplicateLabels as unknown[]).length})`} defaultOpen>
                                <table style={tableStyle}>
                                    <thead>
                                        <tr>
                                            <th style={thStyle}>Label</th>
                                            <th style={thStyle}>URI Count</th>
                                            <th style={thStyle}>URIs</th>
                                        </tr>
                                    </thead>
                                    <tbody>
                                        {(labelMapData.duplicateLabels as Array<Record<string, unknown>>).map((d, i) => (
                                            <tr key={i}>
                                                <td style={tdStyle}>{String(d.label)}</td>
                                                <td style={tdStyle}>{String(d.uriCount)}</td>
                                                <td style={{ ...tdStyle, fontSize: "0.7rem", wordBreak: "break-all" }}>
                                                    {(d.uris as string[]).join(", ")}
                                                </td>
                                            </tr>
                                        ))}
                                    </tbody>
                                </table>
                            </CollapsibleSection>
                        )}

                        {/* Full label map (scrollable) */}
                        <CollapsibleSection title={`Full systemLabelMap (${String(labelMapData.totalMappings)} entries)`} defaultOpen={false}>
                            <div style={{ maxHeight: "400px", overflow: "auto" }}>
                                <table style={tableStyle}>
                                    <thead>
                                        <tr>
                                            <th style={{ ...thStyle, position: "sticky", top: 0 }}>URI</th>
                                            <th style={{ ...thStyle, position: "sticky", top: 0 }}>Label</th>
                                        </tr>
                                    </thead>
                                    <tbody>
                                        {labelMapData.systemLabelMap && Object.entries(labelMapData.systemLabelMap as Record<string, string>)
                                            .sort(([, a], [, b]) => a.localeCompare(b))
                                            .map(([uri, label], i) => (
                                                <tr key={i}>
                                                    <td style={{ ...tdStyle, fontSize: "0.7rem", wordBreak: "break-all" }}>{uri}</td>
                                                    <td style={tdStyle}>{label}</td>
                                                </tr>
                                            ))}
                                    </tbody>
                                </table>
                            </div>
                        </CollapsibleSection>
                    </>
                )}
            </CollapsibleSection>

            {/* ── Raw Score Probe ─────────────────────────────────────────── */}
            <CollapsibleSection title="Debug: Raw Score Probe" defaultOpen={false}>
                <p style={{ color: "#666", fontSize: "0.8rem", marginBottom: "12px" }}>
                    Enter a pair key (e.g. "TBI-BH-AHLTA") to trace where it exists in the pipeline:
                    raw scores → processHashForCharting → pruning.
                </p>
                <div style={{ display: "flex", gap: "8px", flexWrap: "wrap", marginBottom: "16px", alignItems: "center" }}>
                    <input
                        type="text"
                        value={probeKey}
                        onChange={(e) => setProbeKey(e.target.value)}
                        placeholder="Pair key (e.g. TBI-BH-AHLTA)"
                        style={{
                            padding: "6px 12px",
                            border: "1px solid #ccc",
                            borderRadius: "4px",
                            fontFamily: "monospace",
                            fontSize: "0.85rem",
                            width: "300px",
                        }}
                        onKeyDown={(e) => { if (e.key === "Enter") runProbeRawScores(); }}
                    />
                    <button
                        onClick={runProbeRawScores}
                        disabled={isLoading || !heatmapLoaded || !probeKey.trim()}
                        style={buttonStyle}
                    >
                        {step === "loading-probe" ? "Probing..." : "Probe"}
                    </button>
                    {probeData && (
                        <button
                            onClick={() => downloadFile(`probe_${probeKey.trim()}.json`, JSON.stringify(probeData, null, 2))}
                            style={{ ...buttonStyle, padding: "4px 10px", fontSize: "0.75rem" }}
                        >
                            Export JSON
                        </button>
                    )}
                </div>

                {probeData && (
                    <>
                        {/* Probe header */}
                        <table style={{ ...tableStyle, marginBottom: "16px" }}>
                            <tbody>
                                <tr><td style={tdStyle}><strong>Pair Key</strong></td><td style={tdStyle}>{String(probeData.pairKey)}</td></tr>
                                <tr><td style={tdStyle}><strong>Reversed Key</strong></td><td style={tdStyle}>{String(probeData.reversedPairKey)}</td></tr>
                                <tr><td style={tdStyle}><strong>System 1</strong></td><td style={tdStyle}>{String(probeData.system1Label)} <span style={{ fontSize: "0.7rem", color: "#888" }}>{String(probeData.system1URI)}</span></td></tr>
                                <tr><td style={tdStyle}><strong>System 2</strong></td><td style={tdStyle}>{String(probeData.system2Label)} <span style={{ fontSize: "0.7rem", color: "#888" }}>{String(probeData.system2URI)}</span></td></tr>
                                <tr style={{ background: Number(probeData.missingFromRawCount) > 0 ? "#fee" : "#efe" }}>
                                    <td style={tdStyle}><strong>Missing from Raw</strong></td>
                                    <td style={tdStyle}>{String(probeData.missingFromRawCount)} / {String(probeData.variableCount)}</td>
                                </tr>
                                <tr style={{ background: Number(probeData.lostInChartTransformCount) > 0 ? "#fff8e1" : undefined }}>
                                    <td style={tdStyle}><strong>Lost in Chart Transform</strong></td>
                                    <td style={tdStyle}>{String(probeData.lostInChartTransformCount)} / {String(probeData.variableCount)}</td>
                                </tr>
                                <tr style={{ background: "#efe" }}>
                                    <td style={tdStyle}><strong>Present</strong></td>
                                    <td style={tdStyle}>{String(probeData.presentCount)} / {String(probeData.variableCount)}</td>
                                </tr>
                            </tbody>
                        </table>

                        {/* Per-variable detail */}
                        <CollapsibleSection title={`Per-Variable Detail (${String(probeData.variableCount)})`} defaultOpen>
                            <table style={tableStyle}>
                                <thead>
                                    <tr>
                                        <th style={thStyle}>Variable</th>
                                        <th style={thStyle}>Raw A→B</th>
                                        <th style={thStyle}>Raw B→A</th>
                                        <th style={thStyle}>Charted Fwd</th>
                                        <th style={thStyle}>Charted Rev</th>
                                        <th style={thStyle}>Diagnosis</th>
                                    </tr>
                                </thead>
                                <tbody>
                                    {Array.isArray(probeData.variables) && (probeData.variables as Array<Record<string, unknown>>).map((v, i) => {
                                        const bgColor = String(v.diagnosis).startsWith("MISSING") ? "#fee"
                                            : String(v.diagnosis).startsWith("LOST") ? "#fff8e1"
                                            : undefined;
                                        return (
                                            <tr key={i} style={{ background: bgColor }}>
                                                <td style={{ ...tdStyle, fontSize: "0.75rem" }}>{String(v.variable)}</td>
                                                <td style={tdStyle}>{v.rawScoreABx100 != null ? Number(v.rawScoreABx100).toFixed(2) : "—"}</td>
                                                <td style={tdStyle}>{v.rawScoreBAx100 != null ? Number(v.rawScoreBAx100).toFixed(2) : "—"}</td>
                                                <td style={tdStyle}>{v.chartedScoreForward != null ? Number(v.chartedScoreForward).toFixed(2) : "—"}</td>
                                                <td style={tdStyle}>{v.chartedScoreReverse != null ? Number(v.chartedScoreReverse).toFixed(2) : "—"}</td>
                                                <td style={{ ...tdStyle, fontSize: "0.7rem", fontWeight: 600 }}>{String(v.diagnosis)}</td>
                                            </tr>
                                        );
                                    })}
                                </tbody>
                            </table>
                        </CollapsibleSection>
                    </>
                )}
            </CollapsibleSection>

            {/* Introspection results */}
            {introspectData && (
                <CollapsibleSection title={`Playsheet: ${introspectData.className}`} defaultOpen>
                    <div style={{ marginBottom: "12px" }}>
                        <strong>Cached Insight ID:</strong> {introspectData.cachedInsightId}
                    </div>
                    <CollapsibleSection title={`Fields (${introspectData.fields.length})`} defaultOpen>
                        <table style={tableStyle}>
                            <thead>
                                <tr>
                                    <th style={thStyle}>Name</th>
                                    <th style={thStyle}>Type</th>
                                    <th style={thStyle}>Modifiers</th>
                                    <th style={thStyle}>Declared In</th>
                                </tr>
                            </thead>
                            <tbody>
                                {introspectData.fields.map((f, i) => (
                                    <tr key={i} style={{
                                        background: f.name === "paramDataHash" ? "#ffffcc" : undefined,
                                    }}>
                                        <td style={tdStyle}>{f.name}</td>
                                        <td style={tdStyle}>{f.genericType}</td>
                                        <td style={tdStyle}>{f.modifiers}</td>
                                        <td style={{ ...tdStyle, fontSize: "0.75rem" }}>{f.declaredIn}</td>
                                    </tr>
                                ))}
                            </tbody>
                        </table>
                    </CollapsibleSection>
                    <CollapsibleSection title={`Methods (${introspectData.methods.length})`}>
                        <table style={tableStyle}>
                            <thead>
                                <tr>
                                    <th style={thStyle}>Name</th>
                                    <th style={thStyle}>Return</th>
                                    <th style={thStyle}>Params</th>
                                    <th style={thStyle}>Declared In</th>
                                </tr>
                            </thead>
                            <tbody>
                                {introspectData.methods.map((m, i) => (
                                    <tr key={i} style={{
                                        background: m.name === "refreshSysSimData" || m.name === "calculateHash"
                                            ? "#ffffcc" : undefined,
                                    }}>
                                        <td style={tdStyle}>{m.name}</td>
                                        <td style={tdStyle}>{m.returnType}</td>
                                        <td style={tdStyle}>{m.parameterTypes.join(", ") || "—"}</td>
                                        <td style={{ ...tdStyle, fontSize: "0.75rem" }}>{m.declaredIn}</td>
                                    </tr>
                                ))}
                            </tbody>
                        </table>
                    </CollapsibleSection>
                </CollapsibleSection>
            )}

            {/* paramDataHash summary with per-variable export buttons */}
            {paramHashData && (
                <CollapsibleSection
                    title={`paramDataHash — ${paramHashData.variableCount ?? 0} variables`}
                    defaultOpen
                >
                    <div style={{ marginBottom: "12px" }}>
                        <strong>Field type:</strong> {paramHashData.fieldType}<br />
                        <strong>Playsheet:</strong> {paramHashData.playsheetClass}
                    </div>

                    {paramHashData.pairCounts && (
                        <table style={tableStyle}>
                            <thead>
                                <tr>
                                    <th style={thStyle}>Variable</th>
                                    <th style={thStyle}>Pairs</th>
                                    <th style={thStyle}>Export</th>
                                </tr>
                            </thead>
                            <tbody>
                                {Object.entries(paramHashData.pairCounts).map(([name, count]) => (
                                    <tr key={name}>
                                        <td style={tdStyle}>{name}</td>
                                        <td style={tdStyle}>{count}</td>
                                        <td style={tdStyle}>
                                            <button
                                                onClick={() => exportVariable(name)}
                                                disabled={isLoading}
                                                style={{ ...buttonStyle, padding: "2px 8px", fontSize: "0.75rem" }}
                                            >
                                                {exportingVar === name ? "Exporting..." : "Export JSON"}
                                            </button>
                                        </td>
                                    </tr>
                                ))}
                            </tbody>
                        </table>
                    )}
                </CollapsibleSection>
            )}
        </div>
    );
};

/** Collapsible section component. */
function CollapsibleSection({
    title,
    defaultOpen = false,
    children,
}: {
    title: string;
    defaultOpen?: boolean;
    children: React.ReactNode;
}) {
    const [open, setOpen] = useState(defaultOpen);
    return (
        <div style={{ marginBottom: "16px", border: "1px solid #ddd", borderRadius: "6px" }}>
            <button
                onClick={() => setOpen(!open)}
                style={{
                    display: "block",
                    width: "100%",
                    textAlign: "left",
                    padding: "10px 14px",
                    background: "#f5f5f5",
                    border: "none",
                    cursor: "pointer",
                    fontFamily: "monospace",
                    fontSize: "0.9rem",
                    fontWeight: 600,
                }}
            >
                {open ? "▼" : "▶"} {title}
            </button>
            {open && <div style={{ padding: "14px" }}>{children}</div>}
        </div>
    );
}

// ── Inline styles ────────────────────────────────────────────────────────────

const buttonStyle: React.CSSProperties = {
    padding: "8px 16px",
    border: "1px solid #ccc",
    borderRadius: "4px",
    background: "#fff",
    cursor: "pointer",
    fontFamily: "monospace",
    fontSize: "0.85rem",
};

const sectionSubheadingStyle: React.CSSProperties = {
    fontSize: "0.9rem",
    fontWeight: 600,
    margin: "0 0 8px 0",
    color: "#444",
};

const tableStyle: React.CSSProperties = {
    width: "100%",
    borderCollapse: "collapse",
    fontSize: "0.8rem",
};

const thStyle: React.CSSProperties = {
    textAlign: "left",
    padding: "6px 10px",
    borderBottom: "2px solid #ddd",
    background: "#f9f9f9",
};

const tdStyle: React.CSSProperties = {
    padding: "4px 10px",
    borderBottom: "1px solid #eee",
};
