# Hybrid View web controls

Hybrid View places the configured web page above the selected NDI source. Its WebView exposes a deliberately small JavaScript interface named `OneverseHybrid`; the old `SongverseHybrid` name remains available as a compatibility alias. The ordinary Web View does not expose either interface.

## Label NDI sources

In Settings, assign a unique label to each discovered NDI source. Suggested labels include **Main camera**, **Secondary**, **Computer**, **Presentation**, and **Stage**. These are examples only; labels are editable and can describe any source in your setup. Leave a label blank if you do not want to select that source by label.

Labels are saved on this device for the discovered source name and address. The label must be unique among currently discovered sources to use the label-based selector. If two sources have the same label, the request is rejected as ambiguous.

## JavaScript API

```js
// Read current discovery results. The return value is a JSON string.
const sources = JSON.parse(OneverseHybrid.getAvailableNdiSources());
// [{ "name": "Studio Camera", "address": "192.168.1.20", "label": "Main camera" }, ...]

// Position and size the NDI display using percentages of the screen.
OneverseHybrid.setNdiViewport(10, 10, 80, 80);

// Restore the NDI video to the full screen.
OneverseHybrid.resetNdiViewport();

// Request a source using its unique Settings label.
const accepted = OneverseHybrid.selectNdiSourceByLabel("Main camera");

// Or use the exact discovered name/address pair.
const exactAccepted = OneverseHybrid.selectNdiSource("Studio Camera", "192.168.1.20");
```

Each source's `name` and `address` should be passed back unchanged to `selectNdiSource`. The `address` may be an empty string if discovery did not provide one. `selectNdiSourceByLabel` ignores letter case but otherwise matches the saved label exactly after trimming whitespace.

Pages that already use `SongverseHybrid` can continue using it; new pages should use `OneverseHybrid`.

`setNdiViewport`, `resetNdiViewport`, and source selection return `true` when a request is valid and accepted in Hybrid View. Changes are applied asynchronously on the Android UI thread. The NDI image keeps its aspect-fit behavior inside the requested rectangle; viewport calls move and size its display area without changing the incoming NDI stream or cropping decoded frames.

A successful source-selection response means the request was queued. It does not guarantee that the receiver will connect. When a source switch succeeds, the existing web page remains open.

## Trust and navigation

Only the Hybrid View WebView receives this bridge. Calls are limited to viewport changes, listing discovered sources, and selecting one discovered source. The bridge does not expose Android APIs, arbitrary NDI calls, camera access, or file access.

Top-level navigation is restricted to the configured URL's origin while in Hybrid View. The bridge is removed if the page starts on another origin. JavaScript running in the configured page, including scripts and frames it loads, should still be treated as trusted because that page can invoke the exposed controls. Configure only a site you trust; prefer HTTPS, since HTTP content and commands are unencrypted in transit.

