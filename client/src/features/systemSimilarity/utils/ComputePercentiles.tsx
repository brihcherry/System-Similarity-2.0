import type { OutputResponse } from "../types";

/** Each row extended with percentile: [system1Name, system2Name, score, percentile] */
export type SimilarityPercentileRow = [string, string, number, number];

/**
 * Output shape that mirrors OutputResponse but adds percentile to each row.
 * Keeping this close to the current API response makes rendering updates incremental.
 */
export interface OutputWithPercentiles {
    layout: string;
    pkqlOutput: OutputResponse["pkqlOutput"];
    headers: [string, string, string, string];
    data: SimilarityPercentileRow[];
}

/**
 * Creates a new response object where every score row also includes a percentile.
 * Percentiles are computed across all rows in the response and returned on a 0-100 scale.
 */
export function buildOutputWithPercentiles(
    output: OutputResponse,
): OutputWithPercentiles {
    const percentilesByScore = computePercentilesByScore(output.data.map(([, , score]) => score));

    const data: SimilarityPercentileRow[] = output.data.map(([system1, system2, score]) => {
        const percentile = percentilesByScore.get(score) ?? 0;
        return [system1, system2, score, percentile];
    });

    return {
        layout: output.layout,
        pkqlOutput: output.pkqlOutput,
        headers: ["System1", "System2", "Score", "Percentile"],
        data,
    };
}

/**
 * Computes percentile values for each distinct score using average ranks for ties.
 *
 * Example:
 * - Lowest score group tends toward 0
 * - Highest score group tends toward 100
 * - Tied scores receive the same percentile
 */
export function computePercentilesByScore(scores: number[]): Map<number, number> {
    const result = new Map<number, number>();

    if (scores.length === 0) {
        return result;
    }

    const validScores = scores.filter((score) => Number.isFinite(score));
    if (validScores.length === 0) {
        return result;
    }

    const counts = new Map<number, number>();
    for (const score of validScores) {
        counts.set(score, (counts.get(score) ?? 0) + 1);
    }

    const uniqueScoresAscending = Array.from(counts.keys()).sort((a, b) => a - b);

    if (validScores.length === 1) {
        result.set(validScores[0], 100);
        return result;
    }

    let currentRankStart = 1;
    const n = validScores.length;

    for (const score of uniqueScoresAscending) {
        const count = counts.get(score) ?? 0;
        const rankStart = currentRankStart;
        const rankEnd = rankStart + count - 1;
        const averageRank = (rankStart + rankEnd) / 2;

        const percentile = ((averageRank - 1) / (n - 1)) * 100;
        result.set(score, clampToPercent(percentile));

        currentRankStart = rankEnd + 1;
    }

    return result;
}

function clampToPercent(value: number): number {
    if (!Number.isFinite(value)) {
        return 0;
    }

    const clamped = Math.max(0, Math.min(100, value));
    return Number(clamped.toFixed(2));
}
