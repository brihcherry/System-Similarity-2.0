import type {
    OutputResponse,
    RefreshHeatmapRequest,
    SimilarityRow,
    IntrospectResponse,
    ParamDataHashResponse,
} from "../types";

type RunPixelFn = <T = unknown>(
    pixelString: string,
    successMessage?: string,
) => Promise<T>;

/**
 * Stable insight ID for the SystemSimilarity playsheet stored in TAP_Core_Data.
 * Confirmed from GET /insight?insight=341 → { result: "341" }.
 */
const INSIGHT_ID = "341";
const INITIAL_ENGINE_ID = "133db94b-4371-4763-bff9-edf7e5ed021b";

const HEATMAP_HEADERS: [string, string, string] = [
    "System1",
    "System2",
    "Score",
];

/**
 * Fetches initial heatmap data via the project reactor using runPixel.
 *
 * Pixel: GetSystemSimilarityHeatmap(id=["341"], database=["133db94b-4371-4763-bff9-edf7e5ed021b"])
 */
export async function fetchInitialHeatmapFromReactor(
    runPixel: RunPixelFn,
): Promise<OutputResponse> {
    const pixel = `GetSystemSimilarityHeatmap(id=["${INSIGHT_ID}"], database=["${INITIAL_ENGINE_ID}"]);`;
    return runPixel<OutputResponse>(pixel);
}

/**
 * Fetches the raw data-source payload from Reactor-1 for debug and validation.
 *
 * Pixel: GetSystemSimilarityDataSources(database=["133db94b-4371-4763-bff9-edf7e5ed021b"])
 */
export async function fetchSystemSimilarityDataSources(
    runPixel: RunPixelFn,
): Promise<Record<string, unknown>> {
    const pixel = `GetSystemSimilarityDataSources(database=["${INITIAL_ENGINE_ID}"]);`;
    return runPixel<Record<string, unknown>>(pixel);
}

/**
 * Refreshes the SystemSimilarity heatmap using selected variables and optional
 * per-variable weights via the RefreshSystemSimilarityHeatmap project reactor.
 *
 * Pixel: RefreshSystemSimilarityHeatmap(selectedVars=[...], specifiedWeights={...})
 *
 * Requires GetSystemSimilarityHeatmap to have been called first so the backend
 * has populated the paramDataHash cache in the insight's var-store.
 */
export async function refreshHeatmapOutput(
    payload: RefreshHeatmapRequest,
    runPixel: RunPixelFn,
): Promise<OutputResponse> {
    const weightsJson = JSON.stringify(payload.specifiedWeights ?? {});
    const varsJson = JSON.stringify(payload.selectedVars);

    const pixel = `RefreshSystemSimilarityHeatmap(selectedVars=${varsJson}, specifiedWeights=${weightsJson});`;

    const result = await runPixel<
        | OutputResponse
        | Record<string, { System1: string; System2: string; Score: number }>
    >(pixel);

    // Preferred path: legacy backend refresh already returns OutputResponse shape.
    if (
        result &&
        typeof result === "object" &&
        "data" in result &&
        "headers" in result
    ) {
        return result as OutputResponse;
    }


}

/**
 * Introspects the cached playsheet to reveal its fields and methods.
 * Used for development to confirm field names before extraction.
 *
 * Pixel: IntrospectPlaysheet()
 *
 * Requires GetSystemSimilarityHeatmap to have been called first.
 */
export async function introspectPlaysheet(
    runPixel: RunPixelFn,
): Promise<IntrospectResponse> {
    const pixel = `IntrospectPlaysheet();`;
    return runPixel<IntrospectResponse>(pixel);
}

/**
 * Extracts the raw paramDataHash from the cached playsheet via reflection.
 * Returns the pre-computed similarity data before any weighting or aggregation.
 *
 * Pixel: GetParamDataHash(variable=["..."]) or GetParamDataHash()
 *
 * Requires GetSystemSimilarityHeatmap to have been called first.
 */
export async function getParamDataHash(
    runPixel: RunPixelFn,
    variable?: string,
): Promise<ParamDataHashResponse> {
    const pixel = variable
        ? `GetParamDataHash(variable=["${variable}"]);`
        : `GetParamDataHash();`;
    return runPixel<ParamDataHashResponse>(pixel);
}
