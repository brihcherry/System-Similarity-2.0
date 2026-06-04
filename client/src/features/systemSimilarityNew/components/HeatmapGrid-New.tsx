import { Fragment, useCallback, useState, type MouseEvent } from "react";
import type { HeatmapMatrix, TooltipState } from "../types";
import { scoreToColor, type ColorScheme } from "../utils/transformHeatmap-New";
import { formatDisplayName } from "../../../lib/utils"

interface HeatmapGridProps {
    data: HeatmapMatrix;
    colorScheme?: ColorScheme;
    displayMode?: "score" | "percentile";
    minDisplayScore?: number;
    maxDisplayScore?: number;
    dbsMode?: boolean;
}

const CELL_PX = 32;
const ROW_HEADER_PX = 160;
const COL_HEADER_PX = 160;

export const HeatmapGridNew = ({
    data,
    colorScheme = "red",
    displayMode = "score",
    minDisplayScore = 0,
    maxDisplayScore = 100,
    dbsMode = false,
}: HeatmapGridProps) => {
    const { xSystems, ySystems, matrix, minScore, maxScore } = data;
    const metricLabel = displayMode === "percentile" ? "Percentile" : "Score";
    const colorScaleMin = displayMode === "percentile" ? 0 : minScore;
    const colorScaleMax = displayMode === "percentile" ? 100 : maxScore;

    const [tooltip, setTooltip] = useState<TooltipState | null>(null);

    const handleMouseEnter = useCallback(
        (
            e: MouseEvent<HTMLDivElement>,
            rowSystem: string,
            colSystem: string,
            score?: number,
            percentile?: number,
            status?: "no-data" | "partial-data" | "filtered-out" | "self",
            categoryScores?: Record<string, number>,
        ) => {
            setTooltip({
                x: e.clientX + 12,
                y: e.clientY + 12,
                rowSystem,
                colSystem,
                score,
                percentile,
                categoryScores,
                status,
            });
        },
        [],
    );

    const handleMouseLeave = useCallback(() => {
        setTooltip(null);
    }, []);

    const handleMouseMove = useCallback((e: MouseEvent<HTMLDivElement>) => {
        if (tooltip) {
            setTooltip((prev) =>
                prev ? { ...prev, x: e.clientX + 12, y: e.clientY + 12 } : null,
            );
        }
    }, [tooltip]);

    return (
        <div className="relative w-full h-full overflow-auto" onMouseMove={handleMouseMove}>
            {/* Color legend */}
            <div className="sticky top-0 left-0 z-40 bg-white border-b border-gray-200 px-4 py-2 flex items-center gap-3">
                <span className="text-xs text-gray-500 font-medium">{metricLabel}:</span>
                <div className="flex items-center gap-1">
                    <div
                        className="w-24 h-4 rounded-sm"
                        style={{
                            background: `linear-gradient(to right, ${scoreToColor(colorScaleMin, colorScaleMin, colorScaleMax, colorScheme)}, ${scoreToColor((colorScaleMin + colorScaleMax) / 2, colorScaleMin, colorScaleMax, colorScheme)}, ${scoreToColor(colorScaleMax, colorScaleMin, colorScaleMax, colorScheme)})`,
                        }}
                    />
                </div>
                <span className="text-xs text-gray-400">{colorScaleMin.toFixed(1)}</span>
                <span className="text-xs text-gray-400">–</span>
                <span className="text-xs text-gray-400">{colorScaleMax.toFixed(1)}</span>
                <span className="ml-4 text-xs text-gray-400">
                    <span
                        className="inline-block w-4 h-4 rounded-sm align-middle mr-1"
                        style={{ backgroundColor: "#f3f4f6" }}
                    />
                    {dbsMode ? "no data" : "No data or incomplete categories"}
                </span>
                <span className="text-xs text-gray-400">
                    <span
                        className="inline-block w-4 h-4 rounded-sm align-middle mr-1 border border-gray-200"
                        style={{ backgroundColor: "#f9fafb" }}
                    />
                    Filtered Out
                </span>
            </div>

            {/* Scrollable grid */}
            <div
                style={{
                    display: "grid",
                    gridTemplateColumns: `${ROW_HEADER_PX}px repeat(${xSystems.length}, ${CELL_PX}px)`,
                    width: "max-content",
                }}
            >
                {/* ── Top-left corner ──────────────────────────────────────── */}
                <div
                    className="sticky top-0 left-0 z-30 bg-white border-b border-r border-gray-200"
                    style={{ height: COL_HEADER_PX }}
                >
                    <div className="h-full w-full px-2 py-2 text-[10px] text-gray-500 leading-tight">
                        <div>X-Axis: System 1</div>
                        <div>Y-Axis: System 2</div>
                    </div>
                </div>

                {/* ── Column headers ───────────────────────────────────────── */}
                {xSystems.map((sys) => (
                    <div
                        key={sys}
                        className="sticky top-0 z-20 bg-white border-b border-gray-200 flex items-end justify-center pb-1"
                        style={{ height: COL_HEADER_PX, width: CELL_PX }}
                    >
                        <span
                            className="text-[10px] text-gray-600 font-medium overflow-hidden"
                            style={{
                                writingMode: "vertical-rl",
                                textOrientation: "mixed",
                                transform: "rotate(180deg)",
                                maxHeight: COL_HEADER_PX - 8,
                                whiteSpace: "nowrap",
                                textOverflow: "ellipsis",
                                display: "block",
                            }}
                        >
                            {formatDisplayName(sys)}
                        </span>
                    </div>
                ))}

                {/* ── Data rows ────────────────────────────────────────────── */}
                {ySystems.map((rowSys) => (
                    <Fragment key={rowSys}>
                        {/* Row header */}
                        <div
                            className="sticky left-0 z-10 bg-white border-r border-gray-200 flex items-center px-2"
                            style={{ height: CELL_PX }}
                        >
                            <span
                                className="text-[10px] text-gray-600 font-medium truncate"
                                style={{ maxWidth: ROW_HEADER_PX - 12 }}
                            >
                                {formatDisplayName(rowSys)}
                            </span>
                        </div>

                        {/* Data cells */}
                        {xSystems.map((colSys) => {
                            const isSelf = rowSys === colSys;
                            const cell = matrix[rowSys]?.[colSys] ?? undefined;
                            const rawMetricValue = cell
                                ? displayMode === "percentile"
                                    ? cell.percentile
                                    : cell.score
                                : undefined;
                            const visibleMetricValue =
                                rawMetricValue !== undefined &&
                                rawMetricValue >= minDisplayScore &&
                                rawMetricValue <= maxDisplayScore
                                    ? rawMetricValue
                                    : undefined;

                            const cellStatus: "self" | "no-data" | "partial-data" | "filtered-out" | undefined =
                                isSelf ? "self"
                                : !cell ? "no-data"
                                : cell.isPartial ? "partial-data"
                                : visibleMetricValue === undefined ? "filtered-out"
                                : undefined;

                            const bgColor =
                                rawMetricValue !== undefined
                                    ? visibleMetricValue !== undefined
                                        ? scoreToColor(
                                              visibleMetricValue,
                                              colorScaleMin,
                                              colorScaleMax,
                                              colorScheme,
                                          )
                                        : "#f9fafb"
                                    : "#f3f4f6";

                            return (
                                <div
                                    key={colSys}
                                    style={{
                                        width: CELL_PX,
                                        height: CELL_PX,
                                        backgroundColor: bgColor,
                                        cursor: cell !== undefined ? "crosshair" : "default",
                                    }}
                                    onMouseEnter={(e) =>
                                        handleMouseEnter(
                                            e,
                                            rowSys,
                                            colSys,
                                            cell?.score,
                                            cell?.percentile,
                                            cellStatus,
                                            cell?.categoryScores,
                                        )
                                    }
                                    onMouseLeave={handleMouseLeave}
                                />
                            );
                        })}
                    </Fragment>
                ))}
            </div>

            {/* ── Floating tooltip ─────────────────────────────────────────── */}
            {tooltip && (
                <div
                    className="fixed z-50 pointer-events-none bg-gray-900 text-white text-xs rounded-md px-3 py-2 shadow-xl leading-relaxed"
                    style={{ left: tooltip.x, top: tooltip.y }}
                >
                    <div className="font-semibold">{formatDisplayName(tooltip.rowSystem)}</div>
                    <div className="text-gray-300">↔ {formatDisplayName(tooltip.colSystem)}</div>
                    {tooltip.status === "self" && (
                        <div className="mt-1 text-gray-400 italic">
                            Comparing a system with itself — score of 100 is excluded by default
                        </div>
                    )}
                    {tooltip.status === "no-data" && (
                        <div className="mt-1 text-gray-400 italic">
                            {dbsMode
                                ? "No similarity data available"
                                : "No similarity data available or categories are incomplete for this pair"}
                        </div>
                    )}
                    {tooltip.status === "partial-data" && (
                        <>
                            <div className="mt-1 text-gray-400 italic">Missing one or more categories</div>
                            {data.variablesUsed && data.variablesUsed.length > 0 && (
                                <div className="mt-2 border-t border-gray-600 pt-1 space-y-0.5">
                                    <div className="text-gray-400 text-[10px] uppercase tracking-wide mb-1">Individual Category Scores</div>
                                    {data.variablesUsed.map((varName) => {
                                        const catScore = tooltip.categoryScores?.[varName];
                                        return (
                                            <div key={varName} className="flex justify-between gap-4 text-gray-300">
                                                <span>{formatDisplayName(varName)}</span>
                                                <span className={`font-mono ${catScore !== undefined ? "text-gray-200" : "text-gray-500"}`}>
                                                    {catScore !== undefined ? Math.round(catScore) : "N/A"}
                                                </span>
                                            </div>
                                        );
                                    })}
                                </div>
                            )}
                        </>
                    )}
                    {tooltip.status === "filtered-out" && (
                        <>
                            <div className="mt-1 font-mono text-yellow-300">
                                Similarity score: {Math.round(tooltip.score!)}
                            </div>
                            <div className="font-mono text-blue-300">
                                Percentile: {Math.round(tooltip.percentile!)}
                            </div>
                            {tooltip.categoryScores && Object.keys(tooltip.categoryScores).length > 0 && (
                                <div className="mt-2 border-t border-gray-600 pt-1 space-y-0.5">
                                    <div className="text-gray-400 text-[10px] uppercase tracking-wide mb-1">Individual Category Scores</div>
                                    {Object.entries(tooltip.categoryScores).map(([cat, catScore]) => (
                                        <div key={cat} className="flex justify-between gap-4 text-gray-300">
                                            <span>{formatDisplayName(cat)}</span>
                                            <span className="font-mono text-gray-200">{Math.round(catScore)}</span>
                                        </div>
                                    ))}
                                </div>
                            )}
                            <div className="mt-1 text-gray-400 italic">Filtered out by display range</div>
                        </>
                    )}
                    {!tooltip.status && (
                        <>
                            <div className="mt-1 font-mono text-yellow-300">
                                Similarity score: {Math.round(tooltip.score!)}
                            </div>
                            <div className="font-mono text-blue-300">
                                Percentile: {Math.round(tooltip.percentile!)}
                            </div>
                            {tooltip.categoryScores && Object.keys(tooltip.categoryScores).length > 0 && (
                                <div className="mt-2 border-t border-gray-600 pt-1 space-y-0.5">
                                    <div className="text-gray-400 text-[10px] uppercase tracking-wide mb-1">Individual Category Scores</div>
                                    {Object.entries(tooltip.categoryScores).map(([cat, catScore]) => (
                                        <div key={cat} className="flex justify-between gap-4 text-gray-300">
                                            <span>{formatDisplayName(cat)}</span>
                                            <span className="font-mono text-gray-200">{Math.round(catScore)}</span>
                                        </div>
                                    ))}
                                </div>
                            )}
                        </>
                    )}
                </div>
            )}
        </div>
    );
};
