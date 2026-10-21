package org.openndimonitor.app

import android.webkit.WebView
import androidx.webkit.WebMessageCompat
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import org.json.JSONObject

/** Origin and frame identity are supplied by WebView, never by page-controlled data. */
internal object HybridWebControls {
    fun attach(view: WebView, origin: String, isActive: () -> Boolean, command: (String, org.json.JSONArray) -> Any): Boolean {
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) return false
        WebViewCompat.addWebMessageListener(view, "OneverseNative", setOf(origin)) { _, message, sourceOrigin, isMainFrame, reply ->
            if (!isActive() || !isMainFrame || MonitorPolicy.origin(sourceOrigin.toString()) != origin ||
                message.type != WebMessageCompat.TYPE_STRING) return@addWebMessageListener
            val data = message.data ?: return@addWebMessageListener
            if (data.length > 8192) return@addWebMessageListener
            val request = runCatching { JSONObject(data) }.getOrNull() ?: return@addWebMessageListener
            val id = request.optInt("id", -1)
            if (id < 0) return@addWebMessageListener
            val response = JSONObject().put("id", id)
            runCatching {
                command(request.getString("method"), request.getJSONArray("args"))
            }.onSuccess { response.put("result", it) }
                .onFailure { response.put("error", "Invalid Hybrid View request") }
            reply.postMessage(response.toString())
        }
        if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
            WebViewCompat.addDocumentStartJavaScript(view, script, setOf(origin))
        }
        return true
    }

    fun inject(view: WebView) { view.evaluateJavascript(script, null) }

    private val script = """
        (function () {
          if (window !== window.top || !window.OneverseNative || window.OneverseHybrid) return;
          var sequence = 0;
          var pending = new Map();
          OneverseNative.onmessage = function (event) {
            var response;
            try { response = JSON.parse(event.data); } catch (_) { return; }
            var entry = pending.get(response.id);
            if (!entry) return;
            pending.delete(response.id);
            clearTimeout(entry.timer);
            if (response.error) entry.reject(new Error(response.error));
            else entry.resolve(response.result);
          };
          function call(method, args) {
            return new Promise(function (resolve, reject) {
              if (pending.size >= 128) { reject(new Error('Too many pending Hybrid requests')); return; }
              var id = ++sequence;
              var timer = setTimeout(function () {
                pending.delete(id);
                reject(new Error('Hybrid View request timed out'));
              }, 5000);
              pending.set(id, { resolve: resolve, reject: reject, timer: timer });
              try { OneverseNative.postMessage(JSON.stringify({id: id, method: method, args: args})); }
              catch (error) { clearTimeout(timer); pending.delete(id); reject(error); }
            });
          }
          var api = {};
          ['getAvailableNdiSources', 'setNdiViewport', 'resetNdiViewport', 'selectNdiSource', 'selectNdiSourceByLabel'].forEach(function (method) {
            api[method] = function () { return call(method, Array.prototype.slice.call(arguments)); };
          });
          window.OneverseHybrid = Object.freeze(api);
          window.SongverseHybrid = window.OneverseHybrid;
          window.dispatchEvent(new Event('oneversehybridready'));
        })();
    """.trimIndent()
}
