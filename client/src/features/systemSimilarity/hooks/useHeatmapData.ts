import { useEffect, useState } from "react";
import { useAppContext } from "@/contexts";
import { fetchInitialHeatmapFromReactor } from "../api/systemSimilarityApi";
import type { HeatmapMatrix } from "../types";
import { transformHeatmap } from "../utils/transformHeatmap";

interface UseHeatmapDataResult {
    data: HeatmapMatrix | null;
    isLoading: boolean;
    error: string | null;
}

/**
 * Fetches the SystemSimilarity heatmap data on mount and returns it
 * as a transformed directional matrix ready for rendering.
 */
export function useHeatmapData(): UseHeatmapDataResult {
    const { runPixel } = useAppContext();
    const [data, setData] = useState<HeatmapMatrix | null>(null);
    const [isLoading, setIsLoading] = useState(true);
    const [error, setError] = useState<string | null>(null);

    useEffect(() => {
        let cancelled = false;

        setIsLoading(true);
        setError(null);

        fetchInitialHeatmapFromReactor(runPixel)
            .then((output) => {
                if (!cancelled) {
                    setData(transformHeatmap(output));
                }
            })
            .catch((err: unknown) => {
                if (!cancelled) {
                    setError(
                        err instanceof Error
                            ? err.message
                            : "Failed to load heatmap data",
                    );
                }
            })
            .finally(() => {
                if (!cancelled) setIsLoading(false);
            });

        return () => {
            cancelled = true;
        };
    }, [runPixel]);

    return { data, isLoading, error };
}
