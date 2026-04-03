# Mindmap — Kotlin Call Graph Visualizer

An IntelliJ IDEA / Android Studio plugin that generates interactive call graph visualizations for Kotlin functions. Place your cursor on any function, press **Alt+G**, and instantly see who calls it, what it calls, and how deep the chain goes.

---

## ✨ Features

- **Interactive Graph View** — Hierarchical left-to-right layout with callers on the left, root in the center, callees on the right
- **Tree View** — Collapsible tree with connecting guide lines and contextual section labels (`fetchUser calls`, `fetchUser called by`)
- **Navigation History** — Back/forward through previously explored functions with `Alt+←` / `Alt+→`
- **Trace & Expand** — `Cmd+Click` a node to re-center the graph on it; double-click to merge its call graph into the current view
- **Library Code Toggle** — Show or hide library/SDK functions with one click
- **Search Filter** — Filter nodes by name across both graph and tree views
- **Hover Info Cards** — Rich tooltips with function signature, file location, depth, and LOC count
- **Zoom Controls** — Mouse wheel, `+`/`-` keys, fit-all, and center-on-node
- **Arrow Key Navigation** — Navigate between connected nodes with arrow keys
- **Catppuccin Mocha Theme** — Dark, polished UI built on the Catppuccin color palette

## 📸 Preview

> Place your cursor on a Kotlin function → Press `Alt+G` → Explore the call graph

## ⚡ Keyboard Shortcuts

| Shortcut | Action |
|---|---|
| `Alt+G` | Generate call graph for function at cursor |
| `Alt+←` / `Alt+→` | Navigate history back / forward |
| `1` / `2` | Switch to Graph / Tree view |
| `Cmd+Click` | Expand — re-center graph on clicked node |
| `Double-click` | Trace — merge clicked node's call graph |
| `F` | Fit all nodes in view |
| `C` | Center on selected node |
| `+` / `-` | Zoom in / out |
| `/` | Open search filter |
| `Arrow keys` | Navigate between connected nodes |

## 🔧 Requirements

- **IntelliJ IDEA** 2024.3+ or **Android Studio** (Ladybug+)
- **Kotlin** plugin enabled (bundled with IntelliJ/AS)
- Project must use **Kotlin** source files

## 📦 Installation

### From JetBrains Marketplace (Recommended)

1. Open **Settings** → **Plugins** → **Marketplace**
2. Search for **"Mindmap"**
3. Click **Install** → Restart IDE

### From Disk

1. Download the latest `.zip` from [Releases](https://github.com/user/mindmap/releases)
2. Open **Settings** → **Plugins** → ⚙️ → **Install Plugin from Disk**
3. Select the downloaded `.zip` → Restart IDE

### Build from Source

```bash
git clone https://github.com/user/mindmap.git
cd mindmap
./gradlew buildPlugin
```

The plugin `.zip` will be at `build/distributions/Mindmap-*.zip`.

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
│       ├── GraphToolWindowFactory.kt  # Tool window registration
│       └── MindMapPanel.kt           # JCEF browser, JS bridge, history
└── resources/
    ├── META-INF/plugin.xml            # Plugin descriptor
    └── webview/graph.html             # UI (vis-network, CSS, event handlers)
```

## 🔒 Security

- **No external network calls** — CSP blocks all connections except vis-network CDN
- **Base64 encoding** — Graph data is Base64-encoded before JS injection, preventing XSS
- **Input validation** — Node IDs are validated with regex + length caps
- **Message size limits** — JS→Kotlin bridge rejects messages >2KB
- **Scoped search** — Reference search is limited to project source files only

## 📄 License

MIT License — see [LICENSE](LICENSE) for details.
