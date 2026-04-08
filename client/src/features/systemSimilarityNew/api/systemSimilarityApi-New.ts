import type {
    OutputResponse,
    RefreshHeatmapRequest,
    SimilarityRow,
} from "../types";

type RunPixelFn = <T = unknown>(
    pixelString: string,
    successMessage?: string,
) => Promise<T>;

/** TAP_Core_Data engine UUID used by GetSystemSimilarityDataSources. */
const DATABASE_ID = "133db94b-4371-4763-bff9-edf7e5ed021b";

/** Raw response shape from ComputeSimilarityScores reactor. */
interface ComputeScoresResponse {
    headers: [string, string, string];
    data: SimilarityRow[];
    variablesUsed: string[];
    minimumWeightsUsed: Record<string, number> | null;
    totalPairsEvaluated: number;
    pairsAboveThreshold: number;
}

/** Wraps a ComputeScoresResponse into the OutputResponse shape used downstream. */
function toOutputResponse(raw: ComputeScoresResponse): OutputResponse {
    return {
        layout: "SystemSimilarity",
        pkqlOutput: { insights: [] },
        headers: raw.headers,
        data: raw.data,
    };
}

/**
 * Runs GetSystemSimilarityDataSources to populate the var-store with
 * paramDataHash and keyHash.  Must be called before ComputeSimilarityScores.
 */
async function ensureDataSourcesLoaded(runPixel: RunPixelFn): Promise<void> {
    await runPixel(
        `GetSystemSimilarityDataSources(database=["${DATABASE_ID}"]);`,
    );
}

/**
 * Fetches initial heatmap data.  First runs GetSystemSimilarityDataSources
 * to populate the var-store (SPARQL queries + scoring + pruning), then calls
 * ComputeSimilarityScores to aggregate and return the final heatmap rows.
 */
export async function fetchInitialHeatmapFromReactor(
    runPixel: RunPixelFn,
): Promise<OutputResponse> {
    await ensureDataSourcesLoaded(runPixel);
    const result = await runPixel<ComputeScoresResponse>("ComputeSimilarityScores();");
    return toOutputResponse(result);
}

/**
 * Refreshes the heatmap using ComputeSimilarityScores with selected variables
 * and optional per-variable minimum score filters.
 *
 * Pixel: ComputeSimilarityScores(selectedVars=[...], specifiedWeights={...});
 */
export async function refreshHeatmapOutput(
    payload: RefreshHeatmapRequest,
    runPixel: RunPixelFn,
): Promise<OutputResponse> {
    const varsJson = JSON.stringify(payload.selectedVars);
    const weightsJson = JSON.stringify(payload.specifiedWeights ?? {});

    const hasWeights = Object.keys(payload.specifiedWeights ?? {}).length > 0;
    const pixel = hasWeights
        ? `ComputeSimilarityScores(selectedVars=${varsJson}, specifiedWeights=${weightsJson});`
        : `ComputeSimilarityScores(selectedVars=${varsJson});`;

    const result = await runPixel<ComputeScoresResponse>(pixel);
    return toOutputResponse(result);
}
