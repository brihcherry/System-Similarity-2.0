import { useEffect, useState } from "react";
import { useAppContext } from "@/contexts";
import {
    fetchInitialHeatmapFromReactor,
    fetchSystemSimilarityDataSources,
    HeatmapGrid,
    RefreshHeatmapWidget,
    refreshHeatmapOutput,
    transformHeatmap,
    type ColorScheme,
    type HeatmapMatrix,
    type RefreshHeatmapRequest,
    generateColorGradient,
} from "@/features/systemSimilarity";

/**
 * Full-screen page that loads and renders the System Similarity heatmap.
 */
export const SystemSimilarityPage = () => {
    const { runPixel } = useAppContext();
    const [data, setData] = useState<HeatmapMatrix | null>(null);
    const [isLoading, setIsLoading] = useState(true);
    const [error, setError] = useState<string | null>(null);
    const [colorScheme, setColorScheme] = useState<ColorScheme>("red");
    const [isSidebarOpen, setIsSidebarOpen] = useState(true);
    const [displayMode, setDisplayMode] = useState<"score" | "percentile">("score");
    const [minDisplayScore, setMinDisplayScore] = useState(0);
    const [maxDisplayScore, setMaxDisplayScore] = useState(100);
    const [refreshedData, setRefreshedData] = useState<HeatmapMatrix | null>(null);
    const [isRefreshing, setIsRefreshing] = useState(false);
    const [refreshError, setRefreshError] = useState<string | null>(null);

    const [reactorDumpPreview, setReactorDumpPreview] = useState<string>("");
    const [reactorDumpFileName, setReactorDumpFileName] = useState<string | null>(
        null,
    );
    const [reactorDumpSummary, setReactorDumpSummary] = useState<string | null>(
        null,
    );
    const [reactorDumpError, setReactorDumpError] = useState<string | null>(null);
    const [isDumpingReactorOutput, setIsDumpingReactorOutput] = useState(false);

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
                if (!cancelled) {
                    setIsLoading(false);
                }
            });

        return () => {
            cancelled = true;
        };
    }, [runPixel]);

    const displayData = refreshedData ?? data;
    const effectiveMinDisplayScore = Math.min(minDisplayScore, maxDisplayScore);
    const effectiveMaxDisplayScore = Math.max(minDisplayScore, maxDisplayScore);

    const handleRefreshHeatmap = async (payload: RefreshHeatmapRequest) => {
        if (payload.selectedVars.length === 0) {
            setRefreshError("Select at least one variable before refreshing.");
            return;
        }

        setIsRefreshing(true);
        setRefreshError(null);

        try {
            const output = await refreshHeatmapOutput(payload, runPixel);
            setRefreshedData(transformHeatmap(output));
        } catch (err: unknown) {
            setRefreshError(
                err instanceof Error
                    ? err.message
                    : "Failed to refresh heatmap data",
            );
        } finally {
            setIsRefreshing(false);
        }
    };

        const downloadJsonFile = (fileName: string, contents: string) => {
        const blob = new Blob([contents], { type: "application/json" });
        const url = URL.createObjectURL(blob);
        const link = document.createElement("a");
        link.href = url;
        link.download = fileName;
        document.body.appendChild(link);
        link.click();
        document.body.removeChild(link);
        URL.revokeObjectURL(url);
    };

    const handleDumpReactorOutput = async () => {
        setIsDumpingReactorOutput(true);
        setReactorDumpError(null);

        try {
            const output = await fetchSystemSimilarityDataSources(runPixel);
            const serialized = JSON.stringify(output, null, 2);
            const timestamp = new Date().toISOString().replace(/[.:]/g, "-");
            const fileName = `GetSystemSimilarityDataSources-${timestamp}.json`;
            const systems = Array.isArray(output.systems) ? output.systems.length : 0;
            const buckets =
                output.buckets && typeof output.buckets === "object"
                    ? Object.keys(output.buckets as Record<string, unknown>).length
                    : 0;

            setReactorDumpFileName(fileName);
            setReactorDumpSummary(`${systems} systems across ${buckets} buckets`);
            setReactorDumpPreview(serialized.slice(0, 12000));
            downloadJsonFile(fileName, serialized);
        } catch (err: unknown) {
            setReactorDumpError(
                err instanceof Error
                    ? err.message
                    : "Failed to dump GetSystemSimilarityDataSources output",
            );
        } finally {
            setIsDumpingReactorOutput(false);
        }
    };

    return (
        <div className="flex flex-col h-screen bg-white">
            {/* Header */}
            <header className="shrink-0 border-b border-gray-200 px-6 py-4">
                <h1 className="text-xl font-semibold text-gray-900">
                    System Similarity
                </h1>
                {displayData && (
                    <p className="text-sm text-gray-500 mt-0.5">
                        {displayData.xSystems.length} x-systems &middot; {displayData.ySystems.length} y-systems &middot; score range:{" "}
                        {displayData.minScore.toFixed(1)} – {displayData.maxScore.toFixed(1)}
                    </p>
                )}
            </header>

            {/* Content */}
            <main className="flex-1 flex overflow-hidden">
                {/* Sidebar for controls */}
                {isSidebarOpen && (
                    <aside className="w-80 min-w-[18rem] border-r border-gray-200 bg-gray-50 p-4 overflow-y-auto">
                        <div className="flex items-center justify-between mb-4">
                            <h2 className="text-sm font-medium text-gray-700">Controls</h2>
                            <button
                                type="button"
                                onClick={() => setIsSidebarOpen(false)}
                                className="rounded border border-gray-300 bg-white px-2 py-1 text-xs text-gray-700 hover:bg-gray-100"
                            >
                                Hide
                            </button>
                        </div>

                        <div className="space-y-2">
                            <div>
                                <h3 className="text-xs font-semibold text-gray-700 uppercase tracking-wide">
                                    Display Mode
                                </h3>
                                <p className="mt-1 text-xs text-gray-500 leading-relaxed">
                                    Toggle between score mode and percentile mode.
                                </p>
                            </div>

                            <button
                                type="button"
                                onClick={() =>
                                    setDisplayMode((current) =>
                                        current === "score" ? "percentile" : "score",
                                    )
                                }
                                aria-pressed={displayMode === "percentile"}
                                className="flex w-full items-center justify-between rounded-lg border border-gray-300 bg-white px-3 py-2 text-xs text-gray-700 hover:bg-gray-100"
                            >
                                <span className="font-semibold text-gray-600">Score</span>
                                <span className="relative mx-3 inline-flex h-6 w-11 flex-shrink-0 items-center rounded-full bg-gray-200 transition-colors">
                                    <span
                                        className={`inline-block h-5 w-5 rounded-full bg-white shadow-sm transition-transform ${
                                            displayMode === "percentile"
                                                ? "translate-x-5"
                                                : "translate-x-0.5"
                                        }`}
                                    />
                                </span>
                                <span className="font-semibold text-gray-600">Percentile</span>
                            </button>
                        </div>

                        <hr className="my-4" />

                        {/* Color Scheme Selector */}
                        <div className="space-y-2">
                            <label className="text-xs font-semibold text-gray-600">
                                Color Scheme
                            </label>
                            <div className="space-y-2">
                                {(["red", "blue", "green", "traffic-light"] as const).map(
                                    (scheme) => (
                                        <label
                                            key={scheme}
                                            className="flex items-center gap-2 cursor-pointer hover:bg-gray-100 px-2 py-1.5 rounded transition-colors"
                                        >
                                            <input
                                                type="radio"
                                                name="colorScheme"
                                                value={scheme}
                                                checked={colorScheme === scheme}
                                                onChange={(e) =>
                                                    setColorScheme(e.target.value as ColorScheme)
                                                }
                                                className="w-4 h-4 mt-0.5 flex-shrink-0"
                                            />
                                            <div className="flex flex-col gap-1 flex-1 min-w-0">
                                                <span className="text-xs text-gray-700 capitalize font-medium">
                                                    {scheme === "traffic-light"
                                                        ? "Traffic Light"
                                                        : scheme.charAt(0).toUpperCase() + scheme.slice(1)}
                                                </span>
                                                <div
                                                    className="h-3 rounded border border-gray-300"
                                                    style={{
                                                        background: generateColorGradient(scheme),
                                                    }}
                                                />
                                            </div>
                                        </label>
                                    ),
                                )}
                            </div>
                        </div>

                        <hr className="my-4" />

                        <div className="space-y-3">
                            <div>
                                <h3 className="text-xs font-semibold text-gray-700 uppercase tracking-wide">
                                    {displayMode === "percentile"
                                        ? "Visible Percentile Range"
                                        : "Visible Score Range"}
                                </h3>
                                <p className="mt-1 text-xs text-gray-500 leading-relaxed">
                                    {displayMode === "percentile"
                                        ? "Cells outside this percentile range will appear uncolored."
                                        : "Cells outside this score range will appear uncolored."}
                                </p>
                            </div>

                            <div className="grid grid-cols-2 gap-3">
                                <label className="space-y-1">
                                    <span className="text-xs font-semibold text-gray-600">
                                        Minimum
                                    </span>
                                    <input
                                        type="number"
                                        min={0}
                                        max={100}
                                        step={1}
                                        value={minDisplayScore}
                                        onChange={(event) =>
                                            setMinDisplayScore(Number(event.target.value))
                                        }
                                        className="w-full rounded border border-gray-300 px-2 py-1.5 text-xs text-gray-700"
                                    />
                                </label>

                                <label className="space-y-1">
                                    <span className="text-xs font-semibold text-gray-600">
                                        Maximum
                                    </span>
                                    <input
                                        type="number"
                                        min={0}
                                        max={100}
                                        step={1}
                                        value={maxDisplayScore}
                                        onChange={(event) =>
                                            setMaxDisplayScore(Number(event.target.value))
                                        }
                                        className="w-full rounded border border-gray-300 px-2 py-1.5 text-xs text-gray-700"
                                    />
                                </label>
                            </div>
                        </div>

                        <hr className="my-4" />

                        <RefreshHeatmapWidget
                            onRefresh={handleRefreshHeatmap}
                            isRefreshing={isRefreshing}
                        />
                       <hr className="my-4" />

                        <div className="space-y-3">
                            <div>
                                <h3 className="text-xs font-semibold text-gray-700 uppercase tracking-wide">
                                    Reactor-1 Output Test
                                </h3>
                                <p className="mt-1 text-xs text-gray-500 leading-relaxed">
                                    Runs <code>GetSystemSimilarityDataSources</code>, downloads the
                                    full JSON payload to a file, and shows a capped preview here.
                                </p>
                            </div>

                            <button
                                type="button"
                                onClick={handleDumpReactorOutput}
                                disabled={isDumpingReactorOutput}
                                className="w-full rounded border border-gray-300 bg-white px-3 py-2 text-xs font-medium text-gray-700 hover:bg-gray-100 disabled:cursor-not-allowed disabled:opacity-60"
                            >
                                {isDumpingReactorOutput
                                    ? "Running Reactor-1…"
                                    : "Run GetSystemSimilarityDataSources"}
                            </button>

                            {reactorDumpSummary && (
                                <div className="rounded border border-blue-200 bg-blue-50 p-3 text-xs text-blue-800">
                                    <p className="font-semibold">Latest dump</p>
                                    <p>{reactorDumpSummary}</p>
                                    {reactorDumpFileName && <p>Downloaded file: {reactorDumpFileName}</p>}
                                </div>
                            )}

                            {reactorDumpError && (
                                <div className="rounded border border-red-200 bg-red-50 p-3 text-xs text-red-700">
                                    <p className="font-semibold mb-1">Reactor-1 dump failed</p>
                                    <p>{reactorDumpError}</p>
                                </div>
                            )}

                            {reactorDumpPreview && (
                                <div className="space-y-1">
                                    <p className="text-xs font-semibold text-gray-600">
                                        Preview (first 12,000 chars)
                                    </p>
                                    <textarea
                                        readOnly
                                        value={reactorDumpPreview}
                                        className="h-64 w-full resize-y rounded border border-gray-300 bg-gray-950 p-3 font-mono text-[10px] text-gray-100"
                                    />
                                </div>
                            )}
                        </div>

                        {refreshError && (
                            <div className="mt-4 rounded-lg border border-red-200 bg-red-50 p-3 text-xs text-red-700">
                                <p className="font-semibold mb-1">Refresh failed</p>
                                <p>{refreshError}</p>
                            </div>
                        )}
                    </aside>
                )}

                {!isSidebarOpen && (
                    <div className="flex shrink-0 items-start border-r border-gray-200 bg-gray-50 px-2 py-3">
                        <button
                            type="button"
                            onClick={() => setIsSidebarOpen(true)}
                            className="rounded border border-gray-300 bg-white px-3 py-1.5 text-xs text-gray-700 shadow-sm hover:bg-gray-100"
                        >
                            Show Controls
                        </button>
                    </div>
                )}

                <section className="flex-1 relative overflow-hidden">
                    {isLoading && (
                        <div className="flex items-center justify-center h-full">
                            <div className="flex flex-col items-center gap-3 text-gray-500">
                                <div className="w-8 h-8 border-4 border-gray-300 border-t-blue-500 rounded-full animate-spin" />
                                <span className="text-sm">Loading heatmap data…</span>
                            </div>
                        </div>
                    )}

                    {error && !isLoading && (
                        <div className="flex items-center justify-center h-full">
                            <div className="rounded-lg border border-red-200 bg-red-50 px-6 py-4 text-sm text-red-700 max-w-md text-center">
                                <p className="font-semibold mb-1">Failed to load heatmap</p>
                                <p className="text-red-500">{error}</p>
                            </div>
                        </div>
                    )}

                    {displayData && !isLoading && (
                        <HeatmapGrid
                            key={`${displayData.xSystems.join(',')}|${displayData.ySystems.join(',')}|${displayMode}|${effectiveMinDisplayScore}|${effectiveMaxDisplayScore}`}
                            data={displayData}
                            colorScheme={colorScheme}
                            displayMode={displayMode}
                            minDisplayScore={effectiveMinDisplayScore}
                            maxDisplayScore={effectiveMaxDisplayScore}
                        />
                    )}
                </section>
            </main>
        </div>
    );
};
