import type { RefreshHeatmapRequest } from "../types";

export interface RefreshVariableChoice {
    name: string;
    selected: boolean;
    weightInput: string;
}

/**
 * Formats user selections from the sidebar widget into the refresh endpoint payload.
 *
 * Only checked variables are added to selectedVars.
 * specifiedWeights includes only checked variables with a valid integer input.
 */
export function formatRefreshHeatmapPayload(
    choices: RefreshVariableChoice[],
): RefreshHeatmapRequest {
    const selectedVars: string[] = [];
    const specifiedWeights: Record<string, number> = {};

    for (const choice of choices) {
        if (!choice.selected) {
            continue;
        }

        selectedVars.push(choice.name);

        const trimmed = choice.weightInput.trim();
        if (trimmed.length === 0) {
            continue;
        }

        const parsed = Number.parseInt(trimmed, 10);
        if (Number.isInteger(parsed)) {
            specifiedWeights[choice.name] = parsed;
        }
    }

    return {
        selectedVars,
        specifiedWeights,
    };
}
