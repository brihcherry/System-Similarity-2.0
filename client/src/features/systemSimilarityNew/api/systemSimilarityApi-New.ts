import type {
    CapabilityGroupMap,
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

/** Hardcoded DBS subset names, exposed as a capability group entry. */
export const DBS_SYSTEMS = [
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
    /** Per-variable weight multipliers echoed back from the reactor.
     *  Default weight for any selected variable absent from the map is 1.0;
     *  composites are computed as Σ(wᵢ·sᵢ) / Σ(wᵢ). Not a per-variable score
     *  cutoff — the only score filter is the global `minimumScore` argument. */
    specifiedWeightsUsed: Record<string, number> | null;
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
 * Always loads all systems; DBS filtering is handled client-side via the
 * capability group dropdown.
 */
async function ensureDataSourcesLoaded(
    runPixel: RunPixelFn,
): Promise<void> {
    const pixel = `GetSystemSimilarityDataSources(database=["${DATABASE_ID}"]);`;
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
    await ensureDataSourcesLoaded(runPixel);
    const minimumScore = options?.minimumScore ?? 50;
    const result = await runPixel<ComputeScoresResponse>(
        `ComputeSimilarityScores(minimumScore=[${minimumScore}]);`,
    );
    return toOutputResponse(result);
}

/**
 * Refreshes the heatmap using ComputeSimilarityScores with selected variables
 * and optional per-variable weight multipliers.
 *
 * Pixel: ComputeSimilarityScores(selectedVars=[...], specifiedWeights={...});
 */
export async function refreshHeatmapOutput(
    payload: RefreshHeatmapRequest,
    runPixel: RunPixelFn,
    options?: HeatmapRequestOptions,
): Promise<OutputResponse> {
    // Only re-run the data-source reactor when explicitly needed.
    // On normal Refresh clicks the var-store already holds the correct data
    // and we can go straight to scoring.
    if (!options?.skipDataSourcesReload) {
        await ensureDataSourcesLoaded(runPixel);
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

/**
 * Fetches the capability group → systems mapping from the backend.
 * Used to populate the capability-group dropdown in the sidebar.
 * The result is applied as a client-side view filter only — no similarity
 * data is reloaded when the user changes the selected group.
 *
 * Returns an empty object if the reactor call fails (dropdown shows "All Systems" only).
 */
/** Label used for the DBS Systems entry in the capability group dropdown. */
export const DBS_CAPABILITY_GROUP_LABEL = "DBS Systems";

export async function fetchCapabilityGroups(
    runPixel: RunPixelFn,
): Promise<CapabilityGroupMap> {
    let groups: CapabilityGroupMap = {};
    try {
        const result = await runPixel<CapabilityGroupMap>(
            `GetSystemsByCapabilityGroup(database=["${DATABASE_ID}"]);`,
        );
        groups = result ?? {};
    } catch {
        console.warn("GetSystemsByCapabilityGroup failed; capability group filter unavailable.");
    }
    // Inject the hardcoded DBS systems as a capability group entry so it
    // appears in the dropdown alongside backend-sourced groups.
    groups[DBS_CAPABILITY_GROUP_LABEL] = [...DBS_SYSTEMS];
    return groups;
}
