import type { HeatmapCell, HeatmapMatrix } from "../types";

/**
 * Returns a new {@link HeatmapMatrix} restricted to only the systems present
 * in {@code allowedSystems}.
 *
 * - Axes are filtered to the intersection of the current xSystems/ySystems and
 *   the allowed set, preserving existing sort order.
 * - Matrix cells are pruned to matching row/column keys.
 * - minScore and maxScore are recomputed from the remaining cells so the colour
 *   scale re-normalises to the filtered subset.
 * - variablesUsed is passed through unchanged.
 *
 * When {@code allowedSystems} is empty, the original matrix is returned as-is
 * (no filtering applied — "All Systems" behaviour).
 */
export function filterHeatmapBySystems(
    matrix: HeatmapMatrix,
    allowedSystems: string[],
): HeatmapMatrix {
    if (allowedSystems.length === 0) {
        return matrix;
    }

    const allowed = new Set(allowedSystems);

    const xSystems = matrix.xSystems.filter((s) => allowed.has(s));
    const ySystems = matrix.ySystems.filter((s) => allowed.has(s));

    const xSet = new Set(xSystems);
    const ySet = new Set(ySystems);

    const filteredMatrix: Record<string, Record<string, HeatmapCell>> = {};
    let minScore = Infinity;
    let maxScore = -Infinity;

    for (const rowSystem of ySystems) {
        if (!ySet.has(rowSystem)) continue;
        const rowData = matrix.matrix[rowSystem];
        if (!rowData) continue;

        const filteredRow: Record<string, HeatmapCell> = {};
        for (const colSystem of xSystems) {
            if (!xSet.has(colSystem)) continue;
            const cell = rowData[colSystem];
            if (cell) {
                filteredRow[colSystem] = cell;
                if (cell.score !== undefined) {
                    if (cell.score < minScore) minScore = cell.score;
                    if (cell.score > maxScore) maxScore = cell.score;
                }
            }
        }
        filteredMatrix[rowSystem] = filteredRow;
    }

    // If no scored cells remain, fall back to original bounds to avoid Infinity.
    if (minScore === Infinity) minScore = matrix.minScore;
    if (maxScore === -Infinity) maxScore = matrix.maxScore;

    return {
        xSystems,
        ySystems,
        matrix: filteredMatrix,
        minScore,
        maxScore,
        variablesUsed: matrix.variablesUsed,
    };
}
