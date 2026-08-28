import { useState } from "react";
import type { RefreshHeatmapRequest } from "../types";
import { formatDisplayName } from "../../../lib/utils"
import {
    formatRefreshHeatmapPayload,
    type RefreshVariableChoice,
} from "../utils/formatRefreshPayload-New";

export const DEFAULT_REFRESH_VARIABLES = [
    "Business_Processes_Supported",
    "User_Types",
    "Data_Subject_Area",
    "Interfaces",
    "Activities_Supported",
] as const;

interface RefreshHeatmapWidgetProps {
    availableVariables?: readonly string[];
    onRefresh?: (payload: RefreshHeatmapRequest) => void | Promise<void>;
    isRefreshing?: boolean;
}

function createInitialChoices(
    variables: readonly string[],
): RefreshVariableChoice[] {
    return variables.map((name) => ({
        name,
        selected: true,
        weightInput: "",
    }));
}

export const RefreshHeatmapWidgetNew = ({
    availableVariables = DEFAULT_REFRESH_VARIABLES,
    onRefresh,
    isRefreshing = false,
}: RefreshHeatmapWidgetProps) => {
    const [choices, setChoices] = useState<RefreshVariableChoice[]>(() =>
        createInitialChoices(availableVariables),
    );

    const updateChoice = (
        name: string,
        next: Partial<Pick<RefreshVariableChoice, "selected" | "weightInput">>,
    ) => {
        setChoices((prev) =>
            prev.map((choice) =>
                choice.name === name ? { ...choice, ...next } : choice,
            ),
        );
    };

    const handleRefreshClick = () => {
        onRefresh?.(formatRefreshHeatmapPayload(choices));
    };

    return (
        <section className="space-y-3">
            <div>
                <h3 className="text-xs font-semibold text-gray-700 uppercase tracking-wide">
                    Refresh Heatmap
                </h3>
                <p className="mt-1 text-xs text-gray-500 leading-relaxed">
                    Choose variables to include and optionally enter integer
                    weights.
                </p>
            </div>

            <div className="rounded-md border border-gray-200 bg-white">
                <ul className="divide-y divide-gray-100">
                    {choices.map((choice) => (
                        <li key={choice.name} className="px-3 py-2">
                            <div className="flex items-start gap-2">
                                <input
                                    id={`refresh-var-new-${choice.name}`}
                                    type="checkbox"
                                    checked={choice.selected}
                                    onChange={(event) =>
                                        updateChoice(choice.name, {
                                            selected: event.target.checked,
                                        })
                                    }
                                    className="mt-1 h-4 w-4 rounded border-gray-300"
                                />
                                <div className="min-w-0 flex-1">
                                    <label
                                        htmlFor={`refresh-var-new-${choice.name}`}
                                        className="block text-xs font-medium text-gray-700 break-words"
                                    >
                                        {formatDisplayName(choice.name)}
                                    </label>
                                    <div className="mt-2">
                                        <input
                                            type="number"
                                            step={1}
                                            inputMode="numeric"
                                            value={choice.weightInput}
                                            onChange={(event) =>
                                                updateChoice(choice.name, {
                                                    weightInput: event.target.value,
                                                })
                                            }
                                            disabled={!choice.selected}
                                            placeholder="Weight (optional integer)"
                                            className="w-full rounded border border-gray-300 px-2 py-1 text-xs text-gray-700 disabled:cursor-not-allowed disabled:bg-gray-100"
                                        />
                                    </div>
                                </div>
                            </div>
                        </li>
                    ))}
                </ul>
            </div>

            <button
                type="button"
                onClick={handleRefreshClick}
                disabled={isRefreshing}
                className="w-full rounded-md bg-blue-600 px-3 py-2 text-xs font-semibold text-white hover:bg-blue-700 transition-colors"
            >
                {isRefreshing ? "Refreshing..." : "Refresh Heatmap"}
            </button>
        </section>
    );
};
