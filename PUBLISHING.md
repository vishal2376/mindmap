# Publishing Mindmap to JetBrains Marketplace

This guide covers publishing the Mindmap plugin to the [JetBrains Marketplace](https://plugins.jetbrains.com/) for IntelliJ IDEA, Android Studio, and other JetBrains IDEs.

---

## Prerequisites

- [ ] JetBrains account — [Register here](https://account.jetbrains.com/login)
- [ ] Plugin builds successfully: `./gradlew buildPlugin`
- [ ] Plugin tested in the sandbox IDE: `./gradlew runIde`

---

## Step 1: Prepare the Plugin Metadata

### 1.1 Update `plugin.xml`

Ensure the following fields are correct in `src/main/resources/META-INF/plugin.xml`:

| Field | Current Value | Action |
|---|---|---|
| `<id>` | `com.mindmap.plugin` | ✅ Keep (must be unique on Marketplace) |
| `<name>` | `Mindmap` | ✅ Keep |
| `<vendor>` | `Mindmap Plugin` | ⚠️ Update `email` and `url` to your real values |
| `<description>` | HTML description | ✅ Already set |

### 1.2 Add a `<change-notes>` Section

Add release notes inside `plugin.xml` (after `<description>`):

```xml
<change-notes><![CDATA[
<h3>1.0.0</h3>
<ul>
    <li>Initial release</li>
    <li>Interactive graph and tree views</li>
    <li>History navigation, search filter, library toggle</li>
    <li>Catppuccin Mocha theme</li>
</ul>
]]></change-notes>
```

### 1.3 Set the Version

In `build.gradle.kts`, set a version:

```kotlin
version = "1.0.0"
```

---

## Step 2: Set Compatibility Range

The current `build.gradle.kts` targets IntelliJ 2024.3. To support **Android Studio** (which often lags behind IntelliJ releases), verify the `sinceBuild` and `untilBuild` values:

```kotlin
intellijPlatform {
    pluginConfiguration {
        ideaVersion {
            sinceBuild = "243"     // IntelliJ 2024.3+
            untilBuild = "252.*"   // Up to 2025.2.x
        }
    }
}
```

> **Android Studio Mapping:**
> - Ladybug (2024.2) = build 242.x
> - Meerkat (2025.1) = build 251.x
>
> Adjust `sinceBuild` to `242` if you want Ladybug support.

---

## Step 3: Build the Distribution

```bash
./gradlew clean buildPlugin
```

The distributable `.zip` will be at:
```
build/distributions/Mindmap-1.0.0.zip
```

---

## Step 4: Create a Marketplace Listing

### 4.1 Upload via Web UI

1. Go to [plugins.jetbrains.com](https://plugins.jetbrains.com/)
2. Log in with your JetBrains account
3. Click **Upload plugin**
4. Upload the `.zip` from `build/distributions/`
5. Fill in:
   - **Name**: Mindmap
   - **Category**: Code tools
   - **Tags**: `kotlin`, `call-graph`, `visualization`, `mindmap`
   - **License**: MIT (or your choice)
6. Add a **Description** (your README content works well)
7. Add **Screenshots** — take screenshots of the graph and tree views
8. Click **Upload**

### 4.2 Review Process

- JetBrains staff will review your plugin (typically 1-3 business days)
- You'll receive email notification when approved
- Common rejection reasons:
  - Missing `<vendor>` email
  - Description too short
  - Plugin ID conflicts with existing plugins

---

## Step 5: Automate Future Uploads (Optional)

### 5.1 Get a Marketplace Token

1. Go to [hub.jetbrains.com/users/me?tab=authentification](https://hub.jetbrains.com/users/me?tab=authentification)
2. Create a new **Permanent Token** with scope `Plugin Repository`
3. Save the token securely

### 5.2 Add Gradle Publishing

Add to `build.gradle.kts`:

```kotlin
intellijPlatform {
    publishing {
        token = providers.environmentVariable("PUBLISH_TOKEN")
    }
}
```

Then publish:

```bash
PUBLISH_TOKEN=your_token_here ./gradlew publishPlugin
```

### 5.3 GitHub Actions CI/CD

Create `.github/workflows/publish.yml`:

```yaml
name: Publish Plugin
on:
  release:
    types: [published]

jobs:
  publish:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-java@v4
        with:
          distribution: temurin
          java-version: 21
      - name: Publish
        env:
          PUBLISH_TOKEN: ${{ secrets.JETBRAINS_MARKETPLACE_TOKEN }}
        run: ./gradlew publishPlugin
```

Add `JETBRAINS_MARKETPLACE_TOKEN` as a GitHub repository secret.

---

## Step 6: IDE Compatibility

The plugin works on **any JetBrains IDE** that has the Kotlin plugin:

| IDE | Supported | Notes |
|---|---|---|
| IntelliJ IDEA (Community/Ultimate) | ✅ | Primary target |
| Android Studio | ✅ | Ladybug (2024.2)+ recommended |
| Fleet | ❌ | Uses different plugin system |
| WebStorm / PyCharm / etc. | ⚠️ | Only if Kotlin plugin is installed |

---

## Checklist Before Publishing

- [ ] `<vendor>` email and URL are real
- [ ] `<change-notes>` includes release notes
- [ ] Version is set in `build.gradle.kts`
- [ ] Plugin builds: `./gradlew clean buildPlugin`
- [ ] Plugin tested: `./gradlew runIde`
- [ ] Screenshots prepared (graph view + tree view)
- [ ] Description is clear and has feature list
- [ ] `.zip` size is reasonable (<5MB)
