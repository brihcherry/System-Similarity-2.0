import { describe, expect, it } from "vitest";
import { filterHeatmapBySystems } from "./filterHeatmapBySystems-New";
import { transformHeatmap } from "./transformHeatmap-New";
import type { HeatmapMatrix } from "../types";

describe("filterHeatmapBySystems", () => {
    it("keeps all allowed systems on symmetric axes even when some have no scored pairs", () => {
        const matrix: HeatmapMatrix = {
            xSystems: ["SystemA", "SystemB"],
            ySystems: ["SystemA", "SystemB"],
            matrix: {
                SystemA: {},
                SystemB: {
                    SystemA: { score: 77, percentile: 85 },
                },
            },
            minScore: 77,
            maxScore: 77,
            allSystems: ["SystemA", "SystemB", "SystemC"],
        };

        const result = filterHeatmapBySystems(matrix, ["SystemA", "SystemB", "SystemC"]);

        expect(result.xSystems).toEqual(["SystemA", "SystemB", "SystemC"]);
        expect(result.ySystems).toEqual(["SystemA", "SystemB", "SystemC"]);
        expect(result.matrix.SystemC).toEqual({});
        expect(result.matrix.SystemB.SystemA?.score).toBe(77);
        expect(result.minScore).toBe(77);
        expect(result.maxScore).toBe(77);
    });

    it("renders zero-score groups with full symmetric axes and falls back to original score bounds", () => {
        const matrix: HeatmapMatrix = {
            xSystems: ["SystemA", "SystemB"],
            ySystems: ["SystemA", "SystemB"],
            matrix: {
                SystemA: {},
                SystemB: {},
            },
            minScore: 0,
            maxScore: 100,
        };

        const result = filterHeatmapBySystems(matrix, ["SystemA", "SystemB"]);

        expect(result.xSystems).toEqual(["SystemA", "SystemB"]);
        expect(result.ySystems).toEqual(["SystemA", "SystemB"]);
        expect(result.matrix.SystemA).toEqual({});
        expect(result.matrix.SystemB).toEqual({});
        expect(result.minScore).toBe(0);
        expect(result.maxScore).toBe(100);
    });

    it("returns an empty symmetric matrix when the selected group has zero systems", () => {
        const matrix: HeatmapMatrix = {
            xSystems: ["SystemA", "SystemB"],
            ySystems: ["SystemA", "SystemB"],
            matrix: {
                SystemA: {
                    SystemB: { score: 62 },
                },
                SystemB: {},
            },
            minScore: 62,
            maxScore: 62,
        };

        const result = filterHeatmapBySystems(matrix, []);

        expect(result.xSystems).toEqual([]);
        expect(result.ySystems).toEqual([]);
        expect(result.matrix).toEqual({});
        expect(result.minScore).toBe(62);
        expect(result.maxScore).toBe(62);
    });

    it("keeps capability-group views symmetric while supporting legacy compact non-DBS all-systems transform", () => {
        const output = {
            layout: "SystemSimilarity",
            pkqlOutput: { insights: [] },
            headers: ["System1", "System2", "Score"] as [string, string, string],
            data: [["SystemA", "SystemB", 80]] as [string, string, number][],
            partialPairs: [["SystemC", "SystemA", { Clinical: 70 }]] as [string, string, Record<string, number>][],
        };

        const legacyAllSystems = transformHeatmap(output as any);
        expect(legacyAllSystems.xSystems).toEqual(["SystemA"]);
        expect(legacyAllSystems.ySystems).toEqual(["SystemB"]);

        const symmetricGroup = filterHeatmapBySystems(
            {
                ...legacyAllSystems,
                xSystems: ["SystemA", "SystemB", "SystemC"],
                ySystems: ["SystemA", "SystemB", "SystemC"],
                matrix: {
                    SystemA: {},
                    SystemB: { SystemA: { score: 80 } },
                    SystemC: {},
                },
            },
            ["SystemA", "SystemB", "SystemC"],
        );

        expect(symmetricGroup.xSystems).toEqual(["SystemA", "SystemB", "SystemC"]);
        expect(symmetricGroup.ySystems).toEqual(["SystemA", "SystemB", "SystemC"]);
    });
});
