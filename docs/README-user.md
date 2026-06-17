# System Similarity — User Guide

## Decisions

- **DBS surface (Q1):** Confirmed final. DBS is **not** a separate toggle; it appears as the **"DBS Systems"** entry in the Capability Group dropdown. The legacy "All Systems / DBS Only" toggle does not exist in the current app.
- **Page name (Q2):** Use **"System Similarity"** everywhere in this guide. (The navigation link is labeled "System Similarity Heatmap"; the page title shown at the top of the screen is "System Similarity". Both refer to the same and only page.)
- **Color schemes (Q14):** Confirmed final. All four schemes — Red, Blue, Green, and Traffic Light — are supported.

---

## 1. What This App Answers

This app answers a single question: **How similar are military health IT systems to one another?** It loads pairwise similarity data for the systems in the TAP_Core_Data knowledge graph and renders the results as an interactive color-coded heatmap, so you can quickly spot pairs of systems with overlapping capabilities, find consolidation candidates, or see where functional coverage is thin.

---

## 2. Getting Started

### Logging in

When you open the app while signed out, you are sent to the login page.

1. Enter your **Username**.
2. Enter your **Password**.
3. Click **Log in** (or press Enter from the password field).

While the request is in flight the button label changes to **Logging in...**. If the credentials are rejected, the form shows the inline message:

> Username or password is incorrect.

Once you are signed in, the heatmap page loads automatically.

### Navigating

The top navigation bar has the SEMOSS logo on the left and one navigation link:

| Link | What it opens |
|---|---|
| System Similarity Heatmap | The System Similarity page (the only page in this app) |

Clicking the SEMOSS logo takes you to the same page.

### The user menu

A person icon in the top-right corner opens your user menu. Hovering it shows the title **View user menu**. The menu contains:

| Item | What it does |
|---|---|
| (Your username) | Shown as a label so you can confirm who is signed in |
| Logout | Signs you out and reloads the app back to the login page |

---

## 3. The Heatmap

The heatmap is a grid where every column and every row is a system. Each cell shows how similar the two systems at that intersection are to one another.

- **Columns (X axis)** are labeled **System 1** along the top, with text rotated to fit.
- **Rows (Y axis)** are labeled **System 2** along the left side.
- Column and row headers are **sticky** — they stay visible as you scroll the grid, and the top-left corner is pinned as a small legend that reads `X-Axis: System 1` / `Y-Axis: System 2`.

A color legend is pinned just above the grid. It shows a gradient bar with the active range, the metric being displayed (**Score** or **Percentile**), and two swatches: one for `No data or incomplete categories` and one for `Filtered Out`.

### What each cell color means

| State | Background | Trigger |
|---|---|---|
| Scored | `scoreToColor(...)` gradient | Cell has a score in the display range |
| Filtered out | `#f9fafb` (near-white) | Cell has a score outside the active min/max |
| No data / incomplete | `#f3f4f6` (light gray) | Cell has no score (self-comparison, or pair absent from result) |
| Partial data | `#f3f4f6` with hover-only per-category scores | Pair exists in some categories but not all selected ones |

The diagonal — where a system is compared to itself — always falls into the **No data / incomplete** state by design.

---

## 4. Understanding the Scores

Each pair of systems gets a similarity **score** from **0 to 100**, computed as the simple average of however many of the six categories you have enabled. The six categories are:

| Category | What it measures |
|---|---|
| Environment | Whether the two systems operate in the same deployment environment (for example, Theater vs. Garrison). |
| Business Processes Supported | How much overlap there is between the business processes each system supports. |
| User Types | How much overlap there is between the kinds of personnel who use each system. |
| Data Subject Area | How much overlap there is between the data domains each system manages. |
| Interfaces | How much overlap there is between the system interfaces each system provides or consumes. |
| Activities Supported | How much overlap there is between the operational activities each system supports. |

A category that has no data for a given pair drops that pair out of the average. If a pair has data for some categories but not all of them, it appears as a **partial-data** cell rather than a scored cell.

### Percentile

A pair's **percentile** is its rank among every scored pair currently returned, expressed on a 0–100 scale.

- 100 means this is among the most similar pairs in the result set.
- 0 means it is among the least similar.
- Pairs with identical scores share the same percentile (average rank for ties).
- Percentile is computed in your browser from the scored pairs, so it changes whenever you refresh the heatmap with a different set of categories or thresholds.

You can switch the cells and tooltips between raw score and percentile using the **Display Mode** control in the right sidebar.

---

## 5. Controls (Left Sidebar)

Every display and scoring control lives in the left sidebar. The sidebar is open by default. Click **Hide** at the top of the sidebar to collapse it, and **Show Controls** in the left margin to bring it back.

The controls appear in this order from top to bottom.

### Capability Group

Filters the heatmap to systems within a single capability group. 

- The dropdown's first option is **All Systems**, which shows the full result set with no filter.
- The remaining options are the capability groups defined in the knowledge graph, plus the **"DBS Systems"** entry which is pinned at the top of the list.
- Selecting an entry filters both axes of the heatmap to the systems in that group. The filter is applied immediately and does not refetch data.

### Display Mode

Toggles between **Score** mode (cell colors and tooltip values reflect raw similarity scores) and **Percentile** mode (cells and tooltips reflect percentile rank from 0 to 100).

- Click the toggle to switch sides. The active side is highlighted.
- The legend gradient and scale update immediately.
- Tooltip values reflect the new mode the next time you hover.

### Color Scheme

Sets the color palette used to fill scored cells. A small preview swatch is shown next to each option so you can see the low-to-high gradient before picking.

| Option | Low → High gradient |
|---|---|
| Red | Light yellow → medium-dark red |
| Blue | Very light blue → dark blue |
| Green | Light green → dark green |
| Traffic Light | Yellow → orange → red |

Pick one with the radio button. The grid recolors immediately.

### Visible Score Range / Visible Percentile Range

Sets the score (or percentile) window that gets colored. Cells with values outside this window are not removed from the grid — they appear as the near-white **Filtered Out** color so you can still see where they live.

- Header is labeled **Visible Score Range** in Score mode and **Visible Percentile Range** in Percentile mode.
- Enter values from **0 to 100** in the **Minimum** and **Maximum** number inputs.
- The new range is **applied when you click Refresh Heatmap** — changing the inputs alone does not redraw the grid.
- The currently applied range is shown in the page header status line (see Section 7).

### Refresh Heatmap

Re-runs the similarity computation with the categories and category weights you choose. It does not reload the page.

To use it:

1. In the Refresh Heatmap section, check or uncheck the six categories to include or exclude them.
2. Optionally, type an integer in the **Weight (optional integer)** box next to any checked category. The adjusted weights range from 0 to 100 and alter the amount a specific category contributes to the overall score.
3. Click the blue **Refresh Heatmap** button.

While the request is running:

- The button label changes to **Refreshing...** and the button is disabled.

On success:

- The grid updates with the new scores.
- The visible range snaps to whatever was in the Minimum and Maximum inputs at the moment you clicked Refresh.

On failure or invalid input, see Section 8.

---

## 6. Hover Tooltips

Hover any cell to see a tooltip. The tooltip always shows the row system in bold and the column system below it after a `↔` symbol. The rest of the contents depend on the cell's state.

| Cell state | What the tooltip shows |
|---|---|
| Scored | `Similarity score: {n}` and `Percentile: {n}`, followed by an `Individual Category Scores` list with the rounded score for each category in the result. |
| Partial data | `Missing one or more categories`, followed by an `Individual Category Scores` list showing each category's score, or `N/A` for any category with no data for this pair. |
| Filtered out | `Similarity score: {n}` and `Percentile: {n}`, the `Individual Category Scores` list, and at the bottom the notice `Filtered out by display range`. |
| No data | `No similarity data available or categories are incomplete for this pair`. |
| Self (diagonal) | `Comparing a system with itself — score of 100 is excluded by default`. |

---

## 7. Page Header Status Line

Below the page title is a one-line status that summarizes what is currently on screen. The format is:

```
{N} x-systems · {M} y-systems · score range: {min} – {max}
```

- `{N}` is the number of columns currently drawn (X axis).
- `{M}` is the number of rows currently drawn (Y axis).
- `{min}` and `{max}` are the active display window, shown with one decimal place.

The label always reads `score range` regardless of whether Display Mode is set to Score or Percentile.

---

## 8. Error States

| Situation | What you see |
|---|---|
| The app itself fails to start | Full-page warning with a triangle icon and the message: `An error has occurred. Please try again or contact support if the problem persists.` |
| The heatmap fails to load | Red bordered card in the center of the heatmap area with the heading `Failed to load heatmap` and the error detail beneath. |
| A refresh fails | Red box at the bottom of the sidebar with the heading `Refresh failed` and the error detail beneath. |
| You click Refresh with no categories selected | Red box with the message: `Select at least one variable before refreshing.` |
| You enter an unknown URL | You are sent automatically to the heatmap page. |

---

## Glossary

| Term | Definition |
|---|---|
| Score | A 0–100 number for one pair of systems, equal to the average of the per-category scores for the categories included in the current run. |
| Percentile | A 0–100 rank of a pair's score among all currently scored pairs, with ties getting an average rank. |
| Partial pair | A pair of systems that has scores for some of the enabled categories but not all of them. Shown as a gray cell whose tooltip lists each category individually. |
| Capability group | A named group of systems defined in the knowledge graph. Used as a client-side filter for the heatmap axes. |
| DBS Systems | A pre-defined list of named defense health IT systems, available as a single entry at the top of the Capability Group dropdown. |
| TAP_Core_Data | The underlying knowledge graph the app reads from. You do not need to interact with it directly. |
