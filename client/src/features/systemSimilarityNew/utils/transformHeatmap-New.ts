import type { HeatmapMatrix, OutputResponse } from "../types";
import { buildOutputWithPercentiles } from "./ComputePercentiles-New";

export type ColorScheme = "red" | "blue" | "green" | "traffic-light";

/**
 * Converts flat [System1, System2, Score] rows into a directional matrix
 * with sorted x-axis (System1) and y-axis (System2) labels and min/max bounds.
 *
 * If {@code allSystems} and {@code systemLabelMap} are provided, the heatmap matrix will
 * include all those systems as axes (normalized to labels), even if some have no similarity
 * data. This is useful for showing the complete requested universe (e.g., all DBS systems)
 * even if some lack RDF data.
 *
 * Only pairs that appear in the data will have an entry in matrix[row][col].
 * Cells with no data are represented by the absence of a key (undefined lookup).
 */
export function transformHeatmap(
    output: OutputResponse,
    allSystems?: string[],
    systemLabelMap?: Record<string, string>,
): HeatmapMatrix {
    const outputWithPercentiles = buildOutputWithPercentiles(output);

    let xSystems: string[];
    let ySystems: string[];
    let minScore = Infinity;
    let maxScore = -Infinity;

    if (allSystems && allSystems.length > 0 && systemLabelMap) {
        // Convert URIs to their corresponding labels using the systemLabelMap
        const normalizedSystems = allSystems
            .map((uri) => systemLabelMap[uri] || uri) // Use label if found, else use URI as-is
            .sort();
        xSystems = normalizedSystems;
        ySystems = normalizedSystems;
    } else {
        // Derive systems from the data alone (fallback for non-DBS mode)
        const xSystemSet = new Set<string>();
        const ySystemSet = new Set<string>();

        for (const [s1, s2, score] of outputWithPercentiles.data) {
            xSystemSet.add(s1);
            ySystemSet.add(s2);
            if (score < minScore) minScore = score;
            if (score > maxScore) maxScore = score;
        }

        xSystems = Array.from(xSystemSet).sort();
        ySystems = Array.from(ySystemSet).sort();
    }

    // Pre-populate so every y-axis system has an (initially empty) inner record.
    const matrix: HeatmapMatrix["matrix"] = {};
    for (const ySystem of ySystems) {
        matrix[ySystem] = {};
    }

    // Keep directionality: System1 maps to x-axis and System2 maps to y-axis.
    // Populate scores from the data (data contains labels, not URIs).
    for (const [s1, s2, score, percentile, categoryScores] of outputWithPercentiles.data) {
        if (score < minScore) minScore = score;
        if (score > maxScore) maxScore = score;
        const cell = { score, percentile, ...(categoryScores ? { categoryScores } : {}) };
        // s1 and s2 are labels from the reactor output, so they should match ySystem/xSystem keys
        if (matrix[s2]) {
            matrix[s2][s1] = cell;
        }
    }

    return {
        xSystems,
        ySystems,
        matrix,
        minScore: isFinite(minScore) ? minScore : 0,
        maxScore: isFinite(maxScore) ? maxScore : 100,
    };
}

/**
 * Maps a score value to an HSL color string based on the selected color scheme.
 *
 * - "red": yellow → red (default, warm scale)
 * - "blue": light blue → dark blue (cool scale)
 * - "green": light green → dark green (cool scale)
 * - "traffic-light": yellow → orange → red (intuitive for severity)
 */
export function scoreToColor(
    score: number,
    minScore: number,
    maxScore: number,
    colorScheme: ColorScheme = "red",
): string {
    if (maxScore === minScore) {
        return getColorForScheme(0, colorScheme);
    }

    const t = Math.max(0, Math.min(1, (score - minScore) / (maxScore - minScore)));
    return getColorForScheme(t, colorScheme);
}

/**
 * Returns an HSL color based on the normalized score (0-1) and color scheme.
 */
function getColorForScheme(t: number, colorScheme: ColorScheme): string {
    switch (colorScheme) {
        case "red":
            // yellow → orange → red
            return interpolateRed(t);
        case "blue":
            // light blue → dark blue
            return interpolateBlue(t);
        case "green":
            // light green → dark green
            return interpolateGreen(t);
        case "traffic-light":
            // yellow → orange → red (three-stop gradient)
            return interpolateTrafficLight(t);
        default:
            return interpolateRed(t);
    }
}

/**
 * Yellow to red gradient (warm scale)
 * t = 0 (min) → hsl(60, 40%, 92%)  light yellow
 * t = 0.5     → hsl(30, 65%, 67%)  light orange
 * t = 1 (max) → hsl(0,  90%, 42%)  medium-dark red
 */
function interpolateRed(t: number): string {
    const hue = Math.round(60 * (1 - t));
    const sat = Math.round(40 + 50 * t);
    const lit = Math.round(92 - 50 * t);
    return `hsl(${hue}, ${sat}%, ${lit}%)`;
}

/**
 * Light blue to dark blue gradient
 * t = 0 → hsl(200, 40%, 90%)  very light blue
 * t = 1 → hsl(220, 77%, 21%)  dark blue
 */
function interpolateBlue(t: number): string {
    const hue = Math.round(200 + 20 * t);
    const sat = Math.round(40 + 50 * t);
    const lit = Math.round(90 - 55 * t);
    return `hsl(${hue}, ${sat}%, ${lit}%)`;
}

/**
 * Light green to dark green gradient
 * t = 0 → hsl(120, 35%, 90%)  very light green
 * t = 1 → hsl(100, 87%, 18%)  dark green
 */
function interpolateGreen(t: number): string {
    const hue = Math.round(120 - 20 * t);
    const sat = Math.round(35 + 45 * t);
    const lit = Math.round(90 - 55 * t);
    return `hsl(${hue}, ${sat}%, ${lit}%)`;
}

/**
 * Traffic light gradient: red → orange → yellow → green
 * t = 0.0 → red (bad)
 * t ≈ 0.33 → orange
 * t = 0.5 → yellow (medium)
 * t = 1.0 → green (good)
 */
function interpolateTrafficLight(t: number): string {
    let hue, sat, lit;

    if (t < 0.33) {
        // Red to orange (0 to 0.33)
        const localT = t / 0.33; // 0 to 1
        hue = Math.round(0 + 30 * localT); // 0 to 30
        sat = Math.round(90 - 10 * localT); // 90% to 80%
        lit = Math.round(45 + 5 * localT); // 45% to 50%
    } else if (t < 0.5) {
        // Orange to yellow (0.33 to 0.5)
        const localT = (t - 0.33) / 0.17; // 0 to 1
        hue = Math.round(30 + 30 * localT); // 30 to 60
        sat = Math.round(80 + 5 * localT); // 80% to 85%
        lit = Math.round(50 + 5 * localT); // 50% to 55%
    } else {
        // Yellow to green (0.5 to 1.0)
        const localT = (t - 0.5) * 2; // 0 to 1
        hue = Math.round(60 + 60 * localT); // 60 to 120
        sat = Math.round(85 - 5 * localT); // 85% to 80%
        lit = Math.round(55 - 15 * localT); // 55% to 40%
    }

    return `hsl(${hue}, ${sat}%, ${lit}%)`;
}

/**
 * Generates a gradient string for displaying a color scheme preview.
 * Samples the color scheme at 10 points across the full range (0 to 1).
 */
export function generateColorGradient(colorScheme: ColorScheme): string {
    const steps = 10;
    const colors: string[] = [];

    for (let i = 0; i <= steps; i++) {
        const t = i / steps;
        colors.push(getColorForScheme(t, colorScheme));
    }

    return `linear-gradient(to right, ${colors.join(", ")})`;
}
