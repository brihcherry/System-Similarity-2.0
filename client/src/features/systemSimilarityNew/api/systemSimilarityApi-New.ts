import type {
    HeatmapRequestOptions,
    OutputResponse,
    PartialSimilarityRow,
    RefreshHeatmapRequest,
    SimilarityRow,
} from "../types";

type RunPixelFn = <T = unknown>(
    pixelString: string,
    successMessage?: string,
) => Promise<T>;

/** TAP_Core_Data engine UUID used by GetSystemSimilarityDataSources. */
const DATABASE_ID = "133db94b-4371-4763-bff9-edf7e5ed021b";

/** Hardcoded DBS subset names for the dedicated map toggle. */
const DBS_SYSTEMS = [
    "JOMIS",
    "DODTR",
    "DODCR",
    "PCOS",
    "SNPMIS",
    "TMIP-J_INC_2",
    "MIP",
    "CDS",
    "DENCAS",
    "DOEHRS-HC",
    "HAIMS",
    "SEPS",
    "VSSM",
    "BUMIS_II",
    "DMHRSI",
    "EAS_IV",
    "EIRB",
    "NMIS",
    "PHIMT",
    "CCQAS",
    "EBMS-D",
    "EKT",
    "MP2BET",
    "PSR",
    "WIC_PIMS",
    "ART",
    "DODSER",
    "TRRWS",
    "FMIS",
    "ECAA",
    "ASIMS",
    "DMACS",
    "SRTS",
    "DML-ES",
    "AFMETS",
    "LISA",
    "PQNS",
    "DHA_ECS",
    "ICPCCS",
    "T2T",
    "DIPS",
    "EBRAP",
    "EGS",
    "LINCS",
    "LIMDU_SMART",
    "DRSI",
    "EMPARTS",
    "DOEHRS-IH",
    "USU_SIS",
    "ARMOR-D",
    "BLMS",
    "EDC",
    "EDMS",
    "FDA_SDV",
    "LIMS",
    "MDAPT",
    "MRPP",
    "PBF_LIMS",
    "VSIMS",
    "HPCD-NAVY",
    "NOAH",
    "NMO",
    "FROID",
    "HMS",
    "EHA",
    "NMCPHC-EDC2",
    "JPIMS",
    "CHCS",
    "CIS-ESSENTRIS",
    "AHLTA",
    "ESSENCE",
    "RXREFILL",
    "APLIS",
    "ABACUS",
    "ANAM",
    "MHS_GENESIS",
] as const;

/** Raw response shape from ComputeSimilarityScores reactor. */
interface ComputeScoresResponse {
    headers: [string, string, string];
    data: SimilarityRow[];
    variablesUsed: string[];
    minimumWeightsUsed: Record<string, number> | null;
    totalPairsEvaluated: number;
    pairsAboveThreshold: number;
    /** All systems in the current comparison universe, including those with no data. */
    allSystems: string[];
    /** URI to label mapping for all systems. */
    systemLabelMap: Record<string, string>;
    /** Pairs with data for some but not all selected categories (DBS mode only). */
    partialPairs?: PartialSimilarityRow[];
}

/** Wraps a ComputeScoresResponse into the OutputResponse shape used downstream. */
function toOutputResponse(raw: ComputeScoresResponse): OutputResponse {
    return {
        layout: "SystemSimilarity",
        pkqlOutput: { insights: [] },
        headers: raw.headers,
        data: raw.data,
        allSystems: raw.allSystems,
        systemLabelMap: raw.systemLabelMap,
        variablesUsed: raw.variablesUsed,
        partialPairs: raw.partialPairs,
    };
}

/**
 * Runs GetSystemSimilarityDataSources to populate the var-store with
 * paramDataHash and keyHash.  Must be called before ComputeSimilarityScores.
 */
async function ensureDataSourcesLoaded(
    runPixel: RunPixelFn,
    options?: HeatmapRequestOptions,
): Promise<void> {
    const dbsOnly = options?.dbsOnly === true;
    const dbsSystemList = JSON.stringify([...DBS_SYSTEMS]);

    const pixel = dbsOnly
        ? `GetSystemSimilarityDataSources(database=["${DATABASE_ID}"], systemList=${dbsSystemList});`
        : `GetSystemSimilarityDataSources(database=["${DATABASE_ID}"]);`;

    await runPixel(pixel);
}

/**
 * Fetches initial heatmap data.  First runs GetSystemSimilarityDataSources
 * to populate the var-store (SPARQL queries + scoring + pruning), then calls
 * ComputeSimilarityScores to aggregate and return the final heatmap rows.
 */
export async function fetchInitialHeatmapFromReactor(
    runPixel: RunPixelFn,
    options?: HeatmapRequestOptions,
): Promise<OutputResponse> {
    await ensureDataSourcesLoaded(runPixel, options);
    const minimumScore = options?.minimumScore ?? 50;
    const result = await runPixel<ComputeScoresResponse>(
        `ComputeSimilarityScores(minimumScore=[${minimumScore}]);`,
    );
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
    options?: HeatmapRequestOptions,
): Promise<OutputResponse> {
    // Only re-run the data-source reactor when explicitly needed (i.e. dbsOnly
    // changed since the last load).  On normal Refresh clicks the var-store
    // already holds the correct subset, so we skip straight to scoring.
    if (!options?.skipDataSourcesReload) {
        await ensureDataSourcesLoaded(runPixel, options);
    }

    const varsJson = JSON.stringify(payload.selectedVars);
    const weightsJson = JSON.stringify(payload.specifiedWeights ?? {});
    const minimumScore = options?.minimumScore ?? 50;

    const hasWeights = Object.keys(payload.specifiedWeights ?? {}).length > 0;
    const pixel = hasWeights
        ? `ComputeSimilarityScores(selectedVars=${varsJson}, specifiedWeights=${weightsJson}, minimumScore=[${minimumScore}]);`
        : `ComputeSimilarityScores(selectedVars=${varsJson}, minimumScore=[${minimumScore}]);`;

    const result = await runPixel<ComputeScoresResponse>(pixel);
    return toOutputResponse(result);
}
