<div align="center">

<img width="128" height="128" src="src/main/resources/META-INF/pluginIcon.svg" />

# **Mindmap**

An IntelliJ IDEA / Android Studio plugin that generates interactive call graph visualizations for Kotlin functions. Place your cursor on any function and press **Alt+G** to view the call chain - callers, callees, and function depth.

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

## ✨ Features

### Graph View
- **Hierarchical Layout** - Callers on the left, root in the center, callees on the right
- **Box Selection** - Drag on empty canvas to select multiple nodes
- **Multi-node Move** - Drag any selected node to move all selected together
- **Free Positioning** - Move nodes freely in both horizontal and vertical axes
- **Smart Labels** - Duplicate function names show their file name as subtitle
- **Pan** - Right-click drag or middle-click drag to pan the viewport
- **Zoom** - Scroll wheel, `+`/`-` keys, or zoom buttons

### Tree View
- **Collapsible Tree** - Expand/collapse with chevron icons
- **IDE Native** - Strict nesting with standard padding for an integrated look
- **SVG Icons** - Function icons, file icons, directional arrows for calls/callers
- **Section Headers** - `→ fetchUser calls` / `← fetchUser called by` with count badges

### Analysis
- **Bidirectional** - Outbound calls (children) + inbound callers (parents)
- **Trace** - Double-click a node to merge its call graph into the view
- **Expand** - Cmd+Click to re-center the entire graph on a different function
- **Library Toggle** - Show/hide library/SDK calls with one click

### Navigation
- **History** - Navigate back/forward through explored functions (`Alt+←` / `Alt+→`)
- **Click-to-Navigate** - Single click jumps to source code in the editor
- **Search Filter** - Filter nodes by name across both views
- **Hover Info Cards** - Signature, file location, depth, and LOC count


## 🖱️ Mouse Controls

| Action | Effect |
|---|---|
| **Left-click** node | Navigate to source code |
| **Cmd+Click** node | Expand - re-center graph on that function |
| **Double-click** node | Trace - merge its call graph into current view |
| **Left-drag** on empty space | Box/marquee selection |
| **Left-drag** selected node | Move all selected nodes freely |
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
| `L` | Toggle Library API calls |
| `?` | Show Keyboard Shortcuts modal |
| `Escape` | Deselect all nodes or close modals |
| `Enter` | Navigate to selected node's source |
| `Arrow keys` | Navigate between connected nodes |


## 📦 Installation

### Requirements
- **IntelliJ IDEA** 2024.3+ or **Android Studio** Ladybug+
- **Kotlin** plugin enabled (bundled with IntelliJ/AS)

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


## 👤 Author

**Vishal Singh** - [@vishal2376](https://github.com/vishal2376) 


## 📄 License

MIT License — see [LICENSE](LICENSE) for details.
