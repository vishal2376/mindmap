<div align="center">

<img width="128" height="128" src="src/main/resources/META-INF/pluginIcon.svg" />

# **Mindmap**

An IntelliJ IDEA / Android Studio / PyCharm plugin that generates interactive call graph visualizations for **Kotlin**, **Java**, and **Python** functions. Place your cursor on any function and press **Alt+G** to view the call chain - callers, callees, and function depth.

<br/>

<img src="https://img.shields.io/github/stars/vishal2376/mindmap?style=for-the-badge&logo=powerpages&color=cba6f7&logoColor=D9E0EE&labelColor=302D41"/>
<img src="https://img.shields.io/github/last-commit/vishal2376/mindmap?style=for-the-badge&logo=github&color=a6da95&logoColor=D9E0EE&labelColor=302D41"/>
<img src="https://img.shields.io/github/repo-size/vishal2376/mindmap?style=for-the-badge&logo=dropbox&color=7dc4e4&logoColor=D9E0EE&labelColor=302D41"/>

<br/><br/>
</div>

## 🏁 Table of Contents

- [Features](#-features)
- [Controls](#️-mouse-controls)
- [Shortcuts](#️-keyboard-shortcuts)
- [Installation](#-installation)
- [Architecture](#️-architecture)
- [Security & Performance](#-security)
- [Troubleshooting](#-troubleshooting)
- [License](#-license)

---

## 📸 Showcase

**Graph View**<br/>
<img src="showcase/screenshots/graph_view.png" width="800"/>

**Tree View**<br/>
<img src="showcase/screenshots/tree_view.png" width="800"/>

**Keyboard Shortcuts**<br/>
<img src="showcase/screenshots/keyboard_shortcuts.png" width="800"/>

**Context Menu**<br/>
<img src="showcase/screenshots/context_menu.png" width="800"/>

---

## ✨ Features

### Language Support
- **Kotlin** — Full support (K1 & K2), IntelliJ IDEA & Android Studio
- **Java & Python** — *See [Future Roadmap](#-future-roadmap)*

### Graph View
- **Hierarchical Layout** - Callers on the left, root in the center, callees on the right
- **Box Selection** - Drag on empty canvas (select mode) to select multiple nodes
- **Multi-node Move** - Drag any selected node to move all selected together
- **Free Positioning** - Move nodes freely in both horizontal and vertical axes
- **Smart Labels** - Duplicate function names show their file name as subtitle
- **Pan Mode (H)** - Drag a node to move it; drag empty space to pan the canvas
- **Select Mode (V)** - Drag empty space for box/marquee selection
- **Zoom** - Scroll wheel, pinch, `+`/`-` keys, or zoom buttons

### Tree View
- **Collapsible Tree** - Expand/collapse with chevron icons
- **IDE Native** - Strict nesting with standard padding for an integrated look
- **SVG Icons** - Function icons, file icons, directional arrows for calls/callers
- **Section Headers** - `→ fetchUser calls` / `← fetchUser called by` with count badges

### Analysis
- **Bidirectional** - Outbound calls (children) + inbound callers (parents)
- **Trace** - Double-click a node to merge its call graph into the view
- **Expand** - Cmd+Click (⌥+Click on macOS) to re-center the graph on a function
- **Library Toggle** - Show/hide library/SDK calls with one click

### Navigation
- **History** - Navigate back/forward through explored functions (`Alt+←` / `Alt+→`)
- **Smart Navigation** - Use `Arrow keys` to move to parents, children, or cycle through siblings
- **Click-to-Navigate** - Single click or `Enter` jumps to source code in the editor
- **Search Filter** - Filter nodes by name across both views (`/` or `f`)
- **Hover Info Cards** - Signature, file location, depth, and LOC count


## 🖱️ Mouse Controls

| Action | Effect |
|---|---|
| **Left-click** node / **Enter** | Navigate to source code |
| **Cmd+Click** node (macOS) / **Ctrl+Click** (Linux/Win) | Expand — re-center graph on that function |
| **Double-click** node | Trace — merge its call graph into current view |
| **Left-drag** node *(pan mode)* | Move the node |
| **Left-drag** empty space *(pan mode)* | Pan the canvas |
| **Space + Drag** empty space *(select mode)* | Temporary pan while in select mode |
| **Left-drag** empty space *(select mode)* | Box/marquee selection |
| **Left-drag** selected node *(select mode)* | Move all selected nodes freely |
| **Right-drag** / **Middle-drag** | Pan the viewport (either mode) |
| **Scroll wheel** / **Two-finger scroll** | Zoom in/out |
| **Pinch** | Zoom in/out |


## ⌨️ Keyboard Shortcuts

| Shortcut | Action |
|---|---|
| **`Alt+G`** | Generate call graph for function at cursor |
| **`Alt+←`** / **`Alt+→`** | Navigate history back / forward |
| **`1`** / **`2`** | Switch to **Graph** / **Tree** view |
| **`H`** / **`V`** | Switch to **Pan tool** / **Select tool** |
| **`Space` (hold)** | Temporary pan while in Select mode |
| **`Arrow keys`** | Navigate parents/children or cycle siblings |
| **`F`** | Fit all nodes in view |
| **`C`** | Center on selected node |
| **`+`** / **`-`** | Zoom in / out |
| **`/`** or **`f`** | Open search filter |
| **`L`** | Toggle Library API calls |
| **`Enter`** | Navigate to selected node's source |
| **`Escape`** | Deselect all nodes or close modals |
| **`?`** | Show Keyboard Shortcuts modal |


## 📦 Installation

### Requirements
- **IntelliJ IDEA** 2024.3+, **Android Studio** Ladybug+, or **PyCharm** 2024.3+
- **Kotlin** plugin enabled (bundled with IntelliJ/AS)
- **Python** plugin required for Python support (bundled with PyCharm)

### From JetBrains Marketplace
1. Open **Settings** → **Plugins** → **Marketplace**
2. Search for **"Mindmap"**
3. Click **Install** → Restart IDE

### Build from Source
```bash
git clone https://github.com/vishal2376/mindmap.git
cd mindmap
./gradlew buildPlugin
```
The plugin `.zip` will be at `build/distributions/Mindmap-*.zip`.


## 🏗️ Architecture

```
src/main/
├── kotlin/com/mindmap/plugin/
│   ├── actions/
│   │   └── ShowCallGraphAction.kt    # Entry point (Alt+G), language dispatch
│   ├── analysis/
│   │   ├── CallGraphModel.kt         # Data structures (nodes, edges)
│   │   └── GraphAnalyzer.kt          # Kotlin call graph builder (K1 & K2)
│   └── ui/
│       ├── GraphToolWindowFactory.kt  # Tool window + JCEF fallback
│       └── MindMapPanel.kt           # JCEF browser, JS bridge, history
└── resources/
    ├── META-INF/plugin.xml            # Plugin descriptor
    └── webview/graph.html             # UI (vis-network, CSS, event handlers)
```


## 🔒 Security

- **No external network calls** - CSP blocks all connections except vis-network CDN.
- **Base64 encoding** - Graph data is Base64-encoded before JS injection.
- **Message size limits** - JS→Kotlin bridge rejects messages >2KB.

## ⚡ Performance

- **300 node cap** - Prevents graph explosion on large codebases.
- **50 calls/function limit** - Limits outbound analysis per function body.
- **Lazy tool window** - Only initializes when triggered.


## ❓ Troubleshooting

### "JCEF Not Available" in Android Studio
Android Studio may ship with a JBR that doesn't include JCEF. To fix:
1. Go to **Help → Find Action** (or `Cmd+Shift+A`)
2. Search for **"Choose Boot Java Runtime"**
3. Select a runtime that includes **JCEF** (typically labeled with `JCEF`)
4. Click **OK** and **restart** the IDE

### Alt+G shortcut not working
If another plugin (like IdeaVIM) intercepts the shortcut, go to **Settings → Keymap** and assign a different shortcut to **"Generate Mindmap"**. Or simply use **right-click → Generate Mindmap**.


## 🚀 Future Roadmap

- [ ] **Java Support** — Full call graph analysis for Java methods
- [ ] **Python Support** — Call graph analysis for Python functions
- [ ] **Export Options** — Save graph as SVG, PNG, or JSON
- [ ] **Custom Themes** — Allow users to define their own color palettes
- [ ] **Advanced Filtering** — Filter by depth, visibility, or specific packages


## 👤 Author

**Vishal Singh** - [@vishal2376](https://github.com/vishal2376)


## 📄 License

MIT License — see [LICENSE](LICENSE) for details.
