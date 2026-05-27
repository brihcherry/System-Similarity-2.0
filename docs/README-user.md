# System Similarity — User Guide

This application answers the question: **"How similar are military health IT systems to one another?"** It queries the TAP_Core_Data knowledge graph to compute pairwise similarity scores across a universe of defense health systems, then displays those scores as an interactive color-coded heatmap. Use it to identify systems with overlapping capabilities, find consolidation candidates, or understand functional gaps across the MHS IT portfolio.

---

## Getting Started

### Logging In

Navigate to the app in your SEMOSS environment. If you are not already authenticated, you will be redirected to the login page.

- Enter your **Username** and **Password**
- Click **Log In** (or press Enter in the password field)
- If credentials are incorrect, an error message appears inline: *"Username or password is incorrect."*

Once logged in, the app loads your heatmap data automatically. A full-screen loading indicator is shown while data is being fetched.

To log out, click the **user icon** in the top-right corner of the navigation bar and select **Logout**.

### Navigation Bar

| Link | What it shows |
|---|---|
| **System Similarity Heatmap** | The main heatmap visualization (the only page) |

---

## The System Similarity Heatmap

The heatmap is a 2D grid where every row and every column represents a military health IT system. Each **cell** at the intersection of two systems shows how similar those two systems are to one another based on shared attributes in the knowledge graph.

- **Rows** = System 2 (labeled horizontally on the left, 160 px wide)
- **Columns** = System 1 (labeled vertically at the top, rotated)
- **Cells** = pairwise similarity score between the two systems

The row and column headers are **sticky** — they stay visible as you scroll the grid. The top-left corner is also pinned.

### What the Colors Mean

Each cell is filled with a color indicating how similar two systems are. Higher similarity = darker/more saturated color. Lower similarity = lighter color.

A **color legend** is pinned above the grid showing a gradient bar with the min and max values in range, plus swatches for "No Data" and "Filtered Out" cells.

| Cell appearance | Meaning |
|---|---|
| Colored (dark to light) | Two systems have a similarity score within the display range |
| Light gray (`#f3f4f6`) | No similarity data available for this pair, or self-comparison |
| Near-white (`#f9fafb`) | A score exists but falls outside the current display range (filtered out) |
| Self-comparison (diagonal) | Always gray — comparing a system to itself is excluded by design |

### Hover Tooltips

Hover over any cell to see a detailed tooltip:

**For a scored cell:**
- System names (bold row system, `↔` column system)
- **Similarity score** (yellow)
- **Percentile** — rank of this pair among all scored pairs (blue)
- **Per-category scores** (if available) — each of the 6 scoring variables and its individual score

**For a no-data cell:**
- System names
- *"No similarity data available"* or *"Score ≤ 50 — Filtered Out"* (italic gray)

**For a filtered-out cell:**
- Same score and percentile display as a normal cell
- *"Filtered out by display range"* notice at the bottom

---

## Understanding the Scores

### What Is a Similarity Score?

Each pair of systems receives a score from **0 to 100** based on how many attributes they share across up to six categories:

| Category | What it measures |
|---|---|
| **Environment** | Whether both systems operate in the same deployment environment (Theater vs. Garrison) |
| **Business Processes Supported** | Overlap in the business processes each system supports |
| **User Types** | Overlap in the types of personnel who use each system |
| **Data Subject Area** | Overlap in the data domains each system manages |
| **Interfaces** | Overlap in the system interfaces each system connects to |
| **Activities Supported** | Overlap in the operational activities each system supports |

The final score is the **simple average** of whichever categories are included. Only pairs with a score above the minimum threshold (default: 50) are shown.

### What Is a Percentile?

A **percentile** is the relative rank of a pair's score among all pairs returned. A percentile of 100 means this is the most similar pair in the dataset; a percentile of 0 means it is the least similar. Pairs with identical scores receive the same percentile. Percentiles are computed entirely on the client side from the returned data.

### Score vs. Percentile Display

Use the **Display Mode** toggle in the right sidebar to switch between:
- **Score** — colors cells by their raw similarity score
- **Percentile** — colors cells by their relative rank (0–100)

---

## Controls (Right Sidebar)

The sidebar on the right contains all display and scoring controls. It can be collapsed with the **Hide** button and re-expanded with **Show Controls**.

---

### System Filter

**What it does:** Limits which systems appear on the heatmap axes.

**How to use:** Click the toggle button to switch between modes:
- **All Systems** — shows every system in the knowledge graph that has similarity data
- **DBS Only** — restricts both axes to a curated list of ~76 named military health IT systems (e.g., MHS GENESIS, AHLTA, CHCS, AERO). Systems in the DBS list with no data still appear as empty rows/columns.

**What you see:** Switching modes triggers a full reload of both data sources and scores.

---

### Display Mode

**What it does:** Changes whether cell colors and tooltip values reflect raw scores or percentile ranks.

**How to use:** Click the toggle button to switch between **Score** and **Percentile**.

**What you see:** Cell colors and the color legend scale update immediately. The tooltip values update on the next hover.

---

### Color Scheme

**What it does:** Changes the color palette used for the heatmap gradient.

**How to use:** Select one of four radio options. A gradient preview swatch is shown next to each option.

| Option | Low → High |
|---|---|
| **Red** (default) | Light yellow → medium-dark red |
| **Blue** | Very light blue → dark blue |
| **Green** | Light green → dark green |
| **Traffic Light** | Yellow → orange → red |

---

### Display Range (Minimum / Maximum)

**What it does:** Sets the visible score or percentile range. Pairs outside this range are shown as near-white "filtered out" cells rather than colored cells — they still have data, they are just visually suppressed.

**How to use:** Enter values (0–100) in the **Minimum** and **Maximum** number inputs. The range is applied the next time you click **Refresh Heatmap**.

**What you see:** The header below the page title shows the active range as *"score range: {min} – {max}"*.

---

### Refresh Heatmap

**What it does:** Re-runs the similarity scoring with a custom selection of variables and optional minimum score filters per variable, without reloading the page.

**How to use:**
1. In the **Refresh Heatmap** section at the bottom of the sidebar, check or uncheck the six scoring variables to include or exclude them.
2. Optionally, enter an integer **weight** next to any checked variable. This acts as a **minimum score filter** for that variable — pairs where that variable scores below the specified value are excluded entirely.
3. Click the blue **Refresh Heatmap** button.

**What you see:**
- The button label changes to *"Refreshing..."* and is disabled while the request is in flight.
- On success: the heatmap updates with the new scores and the display range snapshots to the values currently in the min/max inputs.
- On failure: a red error box appears below the button showing *"Refresh failed"* and the error detail.
- If no variables are checked: a red box shows *"Select at least one variable before refreshing."*

**Default variables** (all enabled):
- Environment
- Business Processes Supported
- User Types
- Data Subject Area
- Interfaces
- Activities Supported

---

## Page Header

Below the **"System Similarity"** page title, a status line shows:

`"{N} x-systems · {M} y-systems · score range: {min} – {max}"`

This reflects the number of systems currently on each axis and the active display range.

---

## Error States

| Situation | What you see |
|---|---|
| App fails to connect to SEMOSS | Full-screen error page with a triangle icon and *"An error has occurred. Please try again or contact support if the problem persists."* |
| Login credentials incorrect | Inline message: *"Username or password is incorrect."* |
| Heatmap data fails to load | Red bordered card: *"Failed to load heatmap"* with error detail |
| Refresh fails | Red box below the Refresh button: *"Refresh failed"* with error detail |
| No variables selected for refresh | Red box: *"Select at least one variable before refreshing."* |
| Unknown URL | Automatically redirected to the heatmap page |
