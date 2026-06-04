import type { HeatmapCell, HeatmapMatrix } from "../types";

/**
 * Returns a new {@link HeatmapMatrix} restricted to only the systems present
 * in {@code allowedSystems}.
 *
 * - Axes are derived from allowedSystems and kept symmetrical, so allowed
 *   systems remain visible even when no score pair exists.
 * - Matrix cells are pruned to matching row/column keys.
 * - minScore and maxScore are recomputed from the remaining cells so the colour
 *   scale re-normalises to the filtered subset.
 * - variablesUsed is passed through unchanged.
 */
export function filterHeatmapBySystems(
    matrix: HeatmapMatrix,
    allowedSystems: string[],
): HeatmapMatrix {
    const axisSystems = Array.from(new Set(allowedSystems)).sort();
    const xSystems = axisSystems;
    const ySystems = axisSystems;

    const filteredMatrix: Record<string, Record<string, HeatmapCell>> = {};
    let minScore = Infinity;
    let maxScore = -Infinity;

    for (const rowSystem of ySystems) {
        const rowData = matrix.matrix[rowSystem] ?? {};

        const filteredRow: Record<string, HeatmapCell> = {};
        for (const colSystem of xSystems) {
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
        allSystems: matrix.allSystems,
    };
}
