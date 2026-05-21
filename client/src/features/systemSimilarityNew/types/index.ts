// ── Raw API shapes ────────────────────────────────────────────────────────────

/** Response from GET /api/engine/e-{engine}/insight?insight={id} */
export interface InsightResponse {
    result: string;
    options: Record<string, unknown>;
    params: Record<string, unknown>;
}

/** One entry inside pkqlOutput.insights */
export interface PkqlInsight {
    closedPanels: unknown[];
    newColumns: Record<string, unknown>;
    dataID: number;
    feData: Record<string, unknown>;
    pkqlData: unknown[];
    clear: boolean;
    insightID: string;
    newInsights: unknown[];
}

/** One heatmap similarity tuple: [system1Name, system2Name, scoreValue, perCategoryScores?] */
export type SimilarityRow = [string, string, number, (Record<string, number> | undefined)?];

/** A pair with partial category data (missing one or more categories): [sys1, sys2, availableCategoryScores] */
export type PartialSimilarityRow = [string, string, Record<string, number>];

/** Response from POST /api/engine/e-{engine}/output */
export interface OutputResponse {
    layout: string;
    pkqlOutput: {
        insights: PkqlInsight[];
    };
    /** Always ["System1", "System2", "Score"] */
    headers: [string, string, string];
    /** Each row: [system1Name, system2Name, scoreValue] */
    data: SimilarityRow[];
    /** All systems in the comparison universe, including those with no similarity data. */
    allSystems?: string[];
    /** URI to label mapping for all systems. */
    systemLabelMap?: Record<string, string>;
    /** Variables that were selected for this computation run. */
    variablesUsed?: string[];
    /** Pairs that have data for some but not all selected categories (DBS mode only). */
    partialPairs?: PartialSimilarityRow[];
}

/** Request payload for refreshing the heatmap with selected variables and weights. */
export interface RefreshHeatmapRequest {
    selectedVars: string[];
    specifiedWeights?: Partial<Record<string, number>>;
}

/** Optional request knobs for selecting the loaded system universe. */
export interface HeatmapRequestOptions {
    dbsOnly?: boolean;
    /** When true, skip re-running GetSystemSimilarityDataSources and use
     * whatever data is already cached in the var-store. Set this on Refresh
     * calls when the dbsOnly toggle hasn't changed since the last full load. */
    skipDataSourcesReload?: boolean;
    /** Pairs with an overall score below this value are excluded from the
     * ComputeSimilarityScores response.  Defaults to 0 (no filtering) when
     * omitted on the reactor side; the UI defaults to 50. */
    minimumScore?: number;
}

// ── Transformed / app-level shapes ───────────────────────────────────────────

/** One heatmap cell's computed metrics */
export interface HeatmapCell {
    score?: number;
    percentile?: number;
    categoryScores?: Record<string, number>;
    /** True for cells with partial category data (some categories missing). */
    isPartial?: boolean;
}

/** Processed heatmap data ready for rendering */
export interface HeatmapMatrix {
    /** Sorted unique list of System1 values (x-axis) */
    xSystems: string[];
    /** Sorted unique list of System2 values (y-axis) */
    ySystems: string[];
    /** Directional lookup: matrix[system2][system1] = cell metrics */
    matrix: Record<string, Record<string, HeatmapCell>>;
    /** Lowest score present in the dataset */
    minScore: number;
    /** Highest score present in the dataset */
    maxScore: number;
    /** Variables selected for this computation run (used for partial-data tooltips). */
    variablesUsed?: string[];
}

/** Data passed to a hovered tooltip */
export interface TooltipState {
    x: number;
    y: number;
    rowSystem: string;
    colSystem: string;
    score?: number;
    percentile?: number;
    categoryScores?: Record<string, number>;
    /** Undefined means a normal data cell. */
    status?: "no-data" | "partial-data" | "filtered-out" | "self";
}

// ── Debug / introspection shapes ─────────────────────────────────────────────

/** A single field descriptor from the playsheet introspection. */
export interface PlaysheetFieldInfo {
    name: string;
    type: string;
    genericType: string;
    modifiers: string;
    declaredIn: string;
}

/** A single method descriptor from the playsheet introspection. */
export interface PlaysheetMethodInfo {
    name: string;
    returnType: string;
    modifiers: string;
    declaredIn: string;
    parameterTypes: string[];
}

/** Response from IntrospectPlaysheet() pixel. */
export interface IntrospectResponse {
    className: string;
    cachedInsightId: string;
    fields: PlaysheetFieldInfo[];
    methods: PlaysheetMethodInfo[];
}

/**
 * Response from GetParamDataHash() pixel.
 * Shape varies depending on whether a variable filter was provided.
 */
export interface ParamDataHashResponse {
    cachedInsightId: string;
    playsheetClass: string;
    fieldType: string;
    /** Present when no variable filter — count of top-level keys. */
    variableCount?: number;
    /** Present when no variable filter — list of variable names. */
    variables?: string[];
    /** Present when no variable filter — pair count per variable. */
    pairCounts?: Record<string, number>;
    /** Present when a variable filter is provided. */
    variable?: string;
    /** Pair count for the filtered variable. */
    pairCount?: number;
    /** The raw data — full hash or single-variable slice. */
    data?: Record<string, unknown>;
    /** Present on error (e.g. variable not found). */
    error?: string;
    /** Available variable names (present on error). */
    availableVariables?: string[];
}
