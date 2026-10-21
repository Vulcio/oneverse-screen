# Hybrid View web controls

Hybrid View places the configured web page above the selected NDI source. Its WebView exposes a deliberately small JavaScript interface named `OneverseHybrid`; the old `SongverseHybrid` name remains available as a compatibility alias. All methods now return JavaScript Promises; use `await` when reading their results. The ordinary Web View does not expose either interface.

## Label NDI sources

In Settings, assign a unique label to each discovered NDI source and press its Save button or Save Settings. Suggested labels include **Main camera**, **Secondary**, **Computer**, **Presentation**, and **Stage**. These are examples only; labels are editable and can describe any source in your setup. Leave a label blank if you do not want to select that source by label.

Labels are saved on this device for the discovered source name and address. The label must be unique among currently discovered sources to use the label-based selector. If two sources have the same label, the request is rejected as ambiguous.

## JavaScript API

```js
async function configureDisplay() {
  const api = window.OneverseHybrid;
  // Discovery returns a JSON string after the Promise resolves.
  const sources = JSON.parse(await api.getAvailableNdiSources());
  // [{ name: "Studio Camera", address: "192.168.1.20", label: "Main camera" }]

  await api.setNdiViewport(10, 10, 80, 80); // Percentages; rectangle must fit on screen.
  await api.resetNdiViewport();
  const accepted = await api.selectNdiSourceByLabel("Main camera");
  const exactAccepted = await api.selectNdiSource("Studio Camera", "192.168.1.20");
}

if (window.OneverseHybrid) configureDisplay().catch(console.error);
else window.addEventListener("oneversehybridready", () => {
  configureDisplay().catch(console.error);
}, { once: true });
```

Each source's `name` and `address` should be passed back unchanged to `selectNdiSource`. The `address` may be an empty string if discovery did not provide one. `selectNdiSourceByLabel` ignores letter case but otherwise matches the saved label exactly after trimming whitespace.

Pages that already use `SongverseHybrid` can continue using that name, but must also await method results. New pages should use `OneverseHybrid`. Requests reject on protocol errors or after a five-second reply timeout. These controls require a WebView provider with `WEB_MESSAGE_LISTENER` support; on older providers, viewing still works but the control API and ready event are unavailable. Update Android System WebView to enable them.

`setNdiViewport`, `resetNdiViewport`, and source selection resolve to `true` when a request is valid and accepted in Hybrid View. Changes are applied asynchronously on the Android UI thread. The NDI image keeps its aspect-fit behavior inside the requested rectangle; viewport calls move and size its display area without changing the incoming NDI stream or cropping decoded frames.

A successful source-selection response means the request was queued. It does not guarantee that the receiver will connect. When a source switch succeeds, the existing web page remains open.

## Trust and navigation

Only the Hybrid View WebView receives this bridge. Calls are limited to viewport changes, listing discovered sources, and selecting one discovered source. The bridge does not expose Android APIs, arbitrary NDI calls, camera access, or file access.

The AndroidX WebKit message listener is restricted to the configured URL's exact origin (scheme, host and effective port). Native code also checks that each message comes from the main frame and the currently active Hybrid WebView. Embedded frames cannot issue commands, including frames from the same origin. There is no unrestricted Android JavaScript interface.

Top-level navigation is restricted to the configured origin, including on Android 6. Scripts executing in the top-level configured page can invoke controls, so configure a site you trust. Prefer HTTPS because HTTP content and commands are unencrypted in transit.
