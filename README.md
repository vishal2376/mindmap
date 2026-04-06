# Mindmap — Kotlin Call Graph Visualizer

An IntelliJ IDEA / Android Studio plugin that generates interactive call graph visualizations for Kotlin functions. Place your cursor on any function, press **Alt+G**, and instantly see who calls it, what it calls, and how deep the chain goes.

---

## ✨ Features

### Graph View
- **Hierarchical Layout** — callers on the left, root in the center, callees on the right
- **Box Selection** — drag on empty canvas to select multiple nodes (like 3D software)
- **Multi-node Move** — drag any selected node to move all selected together
- **Free Positioning** — move nodes anywhere in both horizontal and vertical axes
- **Smart Labels** — duplicate function names automatically show their file name as subtitle
- **Pan** — right-click drag or middle-click drag to pan the viewport
- **Zoom** — scroll wheel, `+`/`-` keys, or zoom buttons

### Tree View
- **Collapsible Tree** — expand/collapse with chevron icons
- **Continuous Connectors** — `tree` command-style guide lines (├── └── │)
- **SVG Icons** — function icons, file icons, directional arrows for calls/callers
- **Section Headers** — `→ fetchUser calls` / `← fetchUser called by` with count badges

### Analysis
- **Bidirectional** — outbound calls (children) + inbound callers (parents)
- **Trace** — double-click a node to merge its call graph without losing your position
- **Expand** — Cmd+Click to re-center the entire graph on a different function
- **Library Toggle** — show/hide library/SDK calls with one click
- **Large File Optimized** — 300 node cap, 50 calls/function limit, 30 inbound ref limit

### Navigation
- **Click-to-Navigate** — single click jumps to source code in the editor
- **History** — back/forward through explored functions (`Alt+←` / `Alt+→`)
- **Search Filter** — filter nodes by name across both views
- **Hover Info Cards** — signature, file location, depth, and LOC count

### UI
- **Catppuccin Mocha Theme** — premium dark UI with polished aesthetics
- **No Context Menu** — right-click is reserved for panning, no browser menus
- **Crash-proof** — all errors handled silently with IDE crash logging

---

## 🖱️ Mouse Controls

| Action | Effect |
|---|---|
| **Left-click** node | Navigate to source code |
| **Cmd+Click** node | Expand — re-center graph on that function |
| **Double-click** node | Trace — merge its call graph into current view |
| **Left-drag** on empty space | Box/marquee selection |
| **Left-drag** selected node | Move all selected nodes |
| **Right-drag** / **Middle-drag** | Pan the viewport |
| **Scroll wheel** | Zoom in/out |

## ⌨️ Keyboard Shortcuts

| Shortcut | Action |
|---|---|
| `Alt+G` | Generate call graph for function at cursor |
| `Alt+←` / `Alt+→` | Navigate history back / forward |
| `1` / `2` | Switch to Graph / Tree view |
| `F` | Fit all nodes in view |
| `C` | Center on selected node |
| `+` / `-` | Zoom in / out |
| `/` | Open search filter |
| `Escape` | Deselect all nodes |
| `Enter` | Navigate to selected node's source |
| `Arrow keys` | Navigate between connected nodes |

---

## 🔧 Requirements

- **IntelliJ IDEA** 2024.3+ or **Android Studio** Ladybug+
- **Kotlin** plugin enabled (bundled with IntelliJ/AS)
- Project must contain **Kotlin** source files

## 📦 Installation

### From JetBrains Marketplace (Recommended)

1. Open **Settings** → **Plugins** → **Marketplace**
2. Search for **"Mindmap"**
3. Click **Install** → Restart IDE

### From Disk

1. Download the latest `.zip` from [Releases](https://github.com/vishal2376/mindmap/releases)
2. Open **Settings** → **Plugins** → ⚙️ → **Install Plugin from Disk**
3. Select the downloaded `.zip` → Restart IDE

### Build from Source

```bash
git clone https://github.com/vishal2376/mindmap.git
cd mindmap
./gradlew buildPlugin
```

The plugin `.zip` will be at `build/distributions/Mindmap-*.zip`.

---

## 🏗️ Architecture

```
src/main/
├── kotlin/com/mindmap/plugin/
│   ├── actions/
│   │   └── ShowCallGraphAction.kt    # Entry point (Alt+G)
│   ├── analysis/
│   │   ├── CallGraphModel.kt         # Data structures (nodes, edges)
│   │   └── GraphAnalyzer.kt          # K2 Analysis API call graph builder
│   └── ui/
│       ├── GraphToolWindowFactory.kt  # Tool window + JCEF fallback
│       └── MindMapPanel.kt           # JCEF browser, JS bridge, history
└── resources/
    ├── META-INF/plugin.xml            # Plugin descriptor
    └── webview/graph.html             # UI (vis-network, CSS, event handlers)
```

## 🔒 Security

- **No external network calls** — CSP blocks all connections except vis-network CDN
- **Base64 encoding** — graph data is Base64-encoded before JS injection (prevents XSS)
- **Input validation** — node IDs validated with regex + length caps
- **Message size limits** — JS→Kotlin bridge rejects messages >2KB
- **Scoped search** — reference search limited to project source files only

## ⚡ Performance

- **300 node cap** — prevents graph explosion on large codebases
- **50 calls/function** — limits outbound analysis per function body
- **30 inbound refs** — limits reference search for widely-used utility functions
- **Lazy tool window** — only initializes when triggered (won't slow down IDE startup)
- **Position preservation** — trace merges new nodes without re-layouting existing ones

---

## ❓ Troubleshooting

### "JCEF Not Available" in Android Studio

Android Studio may ship with a JBR that doesn't include JCEF. To fix:

1. Go to **Help → Find Action** (or `Cmd+Shift+A`)
2. Search for **"Choose Boot Java Runtime"**
3. Select a runtime that includes **JCEF** (typically labeled with `JCEF`)
4. Click **OK** and **restart** the IDE

> **Note:** IntelliJ IDEA includes JCEF by default — this only affects some Android Studio versions.

### Alt+G shortcut not working

If another plugin (like IdeaVIM) intercepts the shortcut:
1. Go to **Settings → Keymap**
2. Search for **"Generate Mindmap"**
3. Assign a different shortcut

Or use **right-click → Generate Mindmap** from the editor context menu.

### Plugin not showing in tool window

The Mindmap panel only appears after you trigger **Alt+G** on a Kotlin function. It does not auto-activate on project open.

---

## 👤 Author

**Vishal Singh**
- GitHub: [@vishal2376](https://github.com/vishal2376)
- Email: vishalsingh2376@gmail.com

## 📄 License

MIT License — see [LICENSE](LICENSE) for details.
