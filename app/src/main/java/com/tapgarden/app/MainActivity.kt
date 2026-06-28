package com.tapgarden.app

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.webkit.GeolocationPermissions
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import java.io.ByteArrayInputStream
import org.json.JSONObject

/**
 * Standalone full-screen Radio Garden browser for RayNeo X3 Pro.
 *
 * One WebView mirrored to both lenses, with the custom keyboard rendered inside
 * the mirrored viewport so it projects correctly in both eyes.
 */
class MainActivity : android.app.Activity(), CustomKeyboardView.OnKeyboardActionListener {
    private lateinit var webView: WebView
    private lateinit var viewport: FrameLayout
    private lateinit var keyboardContainer: FrameLayout
    private var keyboardView: CustomKeyboardView? = null
    private val imeSuppressor = Handler(Looper.getMainLooper())
    private var suppressImeUntilMs = 0L
    private val homeUrl = "https://radio.garden"
    private var pendingGeoOrigin: String? = null
    private var pendingGeoCallback: GeolocationPermissions.Callback? = null
    private var freshLocationListener: LocationListener? = null
    private val playbackWakeLock: PowerManager.WakeLock by lazy {
        (getSystemService(Context.POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "TapGarden:StreamingAudio").apply {
                setReferenceCounted(false)
            }
    }
    private val playbackWifiLock: WifiManager.WifiLock? by lazy {
        (applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager)?.run {
            @Suppress("DEPRECATION")
            createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "TapGarden:StreamingAudio").apply {
                setReferenceCounted(false)
            }
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        runCatching { com.ffalcon.mercury.android.sdk.MercurySDK.init(application) }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        holdPlaybackResources()
        startPlaybackService()
        window.setSoftInputMode(
            WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN or
                WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING
        )
        enableImmersiveFullscreen()

        webView = WebView(this).apply {
            setLayerType(View.LAYER_TYPE_HARDWARE, null)
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
            scrollBarSize = (16f * resources.displayMetrics.density).toInt()
            isVerticalScrollBarEnabled = true
            isHorizontalScrollBarEnabled = true
            configure(this)
        }

        keyboardContainer = FrameLayout(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM
            )
            visibility = View.GONE
            elevation = 3000f
        }

        viewport = FrameLayout(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
            addView(webView)
            addView(keyboardContainer)
        }

        val binocular = BinocularSbsLayout(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
            menuActionHandler = { action -> triggerRadioGardenAction(action) }
            logicalClickHandler = { x, y -> handleLogicalClick(x, y) }
            edgePanHandler = { dx, dy -> panRadioGardenMap(dx, dy) }
            edgePanStopHandler = { stopRadioGardenMapPan() }
            contentInteractionBlocked = { keyboardContainer.visibility == View.VISIBLE }
            addView(viewport, 0)
            setWebViewTarget(webView)
        }
        setContentView(binocular)
        enableImmersiveFullscreen()

        if (!hasLocationPermission()) requestLocationPermission() else requestFreshLocation()
        webView.loadUrl(homeUrl)
    }

    private fun holdPlaybackResources() {
        runCatching {
            if (!playbackWakeLock.isHeld) playbackWakeLock.acquire()
        }.onFailure {
            Log.w("TapGarden", "Unable to acquire playback wake lock: ${it.message}")
        }
        runCatching {
            val lock = playbackWifiLock
            if (lock != null && !lock.isHeld) lock.acquire()
        }.onFailure {
            Log.w("TapGarden", "Unable to acquire playback Wi-Fi lock: ${it.message}")
        }
    }

    private fun releasePlaybackResources() {
        runCatching {
            if (playbackWifiLock?.isHeld == true) playbackWifiLock?.release()
        }
        runCatching {
            if (playbackWakeLock.isHeld) playbackWakeLock.release()
        }
    }

    private fun startPlaybackService() {
        val intent = Intent(this, TapGardenPlaybackService::class.java)
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(intent)
            } else {
                @Suppress("DEPRECATION")
                startService(intent)
            }
        }.onFailure {
            Log.w("TapGarden", "Unable to start playback service: ${it.message}")
        }
    }

    private fun stopPlaybackService() {
        runCatching {
            stopService(Intent(this, TapGardenPlaybackService::class.java))
        }.onFailure {
            Log.w("TapGarden", "Unable to stop playback service: ${it.message}")
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun configure(wv: WebView) {
        wv.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            @Suppress("DEPRECATION") databaseEnabled = true
            mediaPlaybackRequiresUserGesture = false
            setOffscreenPreRaster(true)
            setGeolocationEnabled(true)
            loadWithOverviewMode = true
            useWideViewPort = true
            builtInZoomControls = false
            displayZoomControls = false
            textZoom = 100
            cacheMode = WebSettings.LOAD_DEFAULT
            allowFileAccess = false
            setSupportMultipleWindows(false)
            javaScriptCanOpenWindowsAutomatically = false
        }
        runCatching {
            wv.setRendererPriorityPolicy(WebView.RENDERER_PRIORITY_BOUND, true)
        }
        disableSystemKeyboard(wv)
        wv.addJavascriptInterface(TgBridge(), "TgBridge")

        wv.webChromeClient = object : WebChromeClient() {
            override fun onGeolocationPermissionsShowPrompt(
                origin: String?,
                callback: GeolocationPermissions.Callback?
            ) {
                if (hasLocationPermission()) {
                    callback?.invoke(origin, true, false)
                    injectBestKnownLocation()
                    requestFreshLocation()
                } else {
                    pendingGeoOrigin = origin
                    pendingGeoCallback = callback
                    requestLocationPermission()
                }
            }
        }

        wv.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(
                view: WebView?,
                request: WebResourceRequest?
            ): WebResourceResponse? {
                val url = request?.url?.toString() ?: return null
                return if (shouldBlockRequest(url)) emptyBlockedResponse() else null
            }

            override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                super.onPageStarted(view, url, favicon)
                injectRadioGardenDarkMode()
                installGeolocationBridge()
                injectBestKnownLocation()
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                installGeolocationBridge()
                injectBestKnownLocation()
                requestFreshLocation()
                injectRadioGardenDarkMode()
                injectAdCleanup()
                injectRadioGardenHelpers()
                injectKeyboardSupport()
                injectMapPanSupport()
            }
        }
    }

    private fun disableSystemKeyboard(wv: WebView) {
        runCatching {
            WebView::class.java.getMethod(
                "setShowSoftInputOnFocus",
                java.lang.Boolean.TYPE
            )
                .invoke(wv, false)
        }
        wv.setOnFocusChangeListener { view, hasFocus ->
            if (hasFocus) hideSystemKeyboard(view)
        }
    }

    private inner class TgBridge {
        @JavascriptInterface fun onInputFocus(value: String?) = runOnUiThread {
            // Editing now operates directly on the live DOM field, so no native
            // buffer is kept here (that was the stale-text bug).
            suppressImeFor(1800L)
            showKeyboard()
        }
        @JavascriptInterface fun onInputBlur() = runOnUiThread {
            hideSystemKeyboard()
        }
    }

    private fun suppressImeFor(durationMs: Long) {
        suppressImeUntilMs = System.currentTimeMillis() + durationMs
        hideSystemKeyboard()
        fun tick() {
            hideSystemKeyboard()
            if (System.currentTimeMillis() < suppressImeUntilMs) {
                imeSuppressor.postDelayed({ tick() }, 90L)
            }
        }
        imeSuppressor.removeCallbacksAndMessages(null)
        tick()
    }

    private fun hideSystemKeyboard(view: View = webView) {
        runCatching {
            (getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager)
                ?.hideSoftInputFromWindow(view.windowToken, 0)
        }
    }

    private fun injectKeyboardSupport() {
        val js = """
            (function(){
              if (window.__tgHooked) return; window.__tgHooked = true;
              function isInput(el){ return el && (el.tagName==='INPUT' || el.tagName==='TEXTAREA' || el.isContentEditable); }
              var style=document.createElement('style');
              style.textContent='[data-tg-keyboard-active="1"]{outline:2px solid #d6a25a!important;outline-offset:2px!important;box-shadow:0 0 0 3px rgba(214,162,90,.28)!important;}';
              document.documentElement.appendChild(style);
              function markActive(el){
                document.querySelectorAll('[data-tg-keyboard-active="1"]').forEach(function(n){
                  if(n!==el) n.removeAttribute('data-tg-keyboard-active');
                });
                try{ el.setAttribute('data-tg-keyboard-active','1'); }catch(_){}
              }
              function visible(el){
                if(!el || !el.getClientRects || !el.getClientRects().length) return false;
                var s=getComputedStyle(el);
                return s.visibility!=='hidden' && s.display!=='none' && el.offsetWidth>0 && el.offsetHeight>0;
              }
              function findSearchInput(){
                var candidates=[].slice.call(document.querySelectorAll('input, textarea, [contenteditable="true"]'));
                return candidates.find(function(el){
                  var p=((el.getAttribute('placeholder')||'')+' '+(el.getAttribute('aria-label')||'')+' '+(el.name||'')+' '+(el.type||'')).toLowerCase();
                  return visible(el) && (p.indexOf('country')>=0 || p.indexOf('city')>=0 || p.indexOf('station')>=0 || p.indexOf('search')>=0);
                }) || candidates.find(visible) || null;
              }
              function remember(el){
                if(isInput(el)){
                  window.__tgActiveInput = el;
                  markActive(el);
                  try{ TgBridge.onInputFocus(typeof el.value === 'string' ? el.value : (el.textContent || '')); }catch(_){}
                }
              }
              function activeInput(){
                var el = window.__tgActiveInput;
                if(!isInput(el) || !document.contains(el)) el = document.activeElement;
                if(!isInput(el)) el = findSearchInput();
                if(!isInput(el)) return null;
                window.__tgActiveInput = el;
                markActive(el);
                try {
                  if(el.focus) el.focus({preventScroll:true});
                } catch(e) {
                  try{ el.focus(); }catch(_){}
                }
                return el;
              }
              function setNativeValue(el, value){
                var oldValue = typeof el.value === 'string' ? el.value : '';
                var proto = Object.getPrototypeOf(el);
                var desc = proto && Object.getOwnPropertyDescriptor(proto, 'value');
                if(!desc && el instanceof HTMLInputElement) desc = Object.getOwnPropertyDescriptor(HTMLInputElement.prototype, 'value');
                if(!desc && el instanceof HTMLTextAreaElement) desc = Object.getOwnPropertyDescriptor(HTMLTextAreaElement.prototype, 'value');
                if(desc && desc.set) desc.set.call(el, value); else el.value = value;
                if(el._valueTracker) {
                  try { el._valueTracker.setValue(oldValue); } catch(_){}
                }
              }
              function notify(el){
                try {
                  el.dispatchEvent(new InputEvent('input',{bubbles:true,inputType:'insertText',data:el.value}));
                } catch(e) {
                  el.dispatchEvent(new Event('input',{bubbles:true}));
                }
                el.dispatchEvent(new Event('change',{bubbles:true}));
                el.dispatchEvent(new KeyboardEvent('keyup',{key:'Unidentified',bubbles:true}));
              }
              document.addEventListener('focusin', function(e){ remember(e.target); }, true);
              document.addEventListener('pointerdown', function(e){ if(isInput(e.target)) remember(e.target); }, true);
              document.addEventListener('mousedown', function(e){ if(isInput(e.target)) remember(e.target); }, true);
              document.addEventListener('touchstart', function(e){ if(isInput(e.target)) remember(e.target); }, true);
              document.addEventListener('click', function(e){ if(isInput(e.target)) remember(e.target); }, true);
              document.addEventListener('focusout', function(e){ if(isInput(e.target)){ try{ TgBridge.onInputBlur(); }catch(_){} } }, true);
              if (isInput(document.activeElement)) remember(document.activeElement);
              window.__tgDefocus = function(){
                var el = window.__tgActiveInput || document.activeElement;
                if(isInput(el)){
                  try{ el.blur(); }catch(_){}
                }
                window.__tgActiveInput = null;
              };
              window.__tgSetText = function(value, cursor){
                var el=activeInput(); if(!el) return;
                if(el.isContentEditable){
                  el.textContent = value;
                  el.dispatchEvent(new InputEvent('input',{bubbles:true,inputType:'insertText'}));
                  return;
                }
                if(typeof el.value!=='string') return;
                setNativeValue(el,value);
                if(el.selectionStart!=null) el.selectionStart=el.selectionEnd=Math.max(0, Math.min(value.length, cursor == null ? value.length : cursor));
                notify(el);
                setTimeout(function(){ activeInput(); }, 0);
              };
              function caret(el){
                var s = (typeof el.selectionStart === 'number') ? el.selectionStart : (el.value||'').length;
                var e = (typeof el.selectionEnd === 'number') ? el.selectionEnd : s;
                return [s, e];
              }
              window.__tgInsert = function(text){
                var el=activeInput(); if(!el) return;
                if(el.isContentEditable){
                  try{ el.focus({preventScroll:true}); }catch(_){ try{el.focus();}catch(__){} }
                  if(!document.execCommand || !document.execCommand('insertText', false, text)){
                    el.textContent = (el.textContent||'') + text;
                  }
                  el.dispatchEvent(new InputEvent('input',{bubbles:true,inputType:'insertText',data:text}));
                  return;
                }
                if(typeof el.value!=='string') return;
                var c=caret(el), s=c[0], e=c[1], v=el.value;
                var nv=v.slice(0,s)+text+v.slice(e);
                setNativeValue(el,nv);
                var pos=s+text.length;
                if(typeof el.selectionStart==='number') el.selectionStart=el.selectionEnd=pos;
                notify(el);
              };
              window.__tgBackspace = function(){
                var el=activeInput(); if(!el) return;
                if(el.isContentEditable){
                  try{ el.focus({preventScroll:true}); }catch(_){ try{el.focus();}catch(__){} }
                  if(!document.execCommand || !document.execCommand('delete', false)){
                    el.textContent = (el.textContent||'').slice(0,-1);
                  }
                  el.dispatchEvent(new InputEvent('input',{bubbles:true,inputType:'deleteContentBackward'}));
                  return;
                }
                if(typeof el.value!=='string') return;
                var c=caret(el), s=c[0], e=c[1], v=el.value, nv, pos;
                if(s!==e){ nv=v.slice(0,s)+v.slice(e); pos=s; }
                else if(s>0){ nv=v.slice(0,s-1)+v.slice(s); pos=s-1; }
                else return;
                setNativeValue(el,nv);
                if(typeof el.selectionStart==='number') el.selectionStart=el.selectionEnd=pos;
                notify(el);
              };
              window.__tgClear = function(){
                var el=activeInput(); if(!el) return;
                if(el.isContentEditable){
                  el.textContent='';
                  el.dispatchEvent(new InputEvent('input',{bubbles:true,inputType:'deleteContent'}));
                  return;
                }
                if(typeof el.value!=='string') return;
                setNativeValue(el,'');
                if(typeof el.selectionStart==='number') el.selectionStart=el.selectionEnd=0;
                notify(el);
              };
              window.__tgMoveCaret = function(delta){
                var el=activeInput(); if(!el) return;
                if(typeof el.selectionStart!=='number') return;
                var len=(el.value||'').length;
                var pos=Math.max(0, Math.min(len, el.selectionStart + delta));
                el.selectionStart=el.selectionEnd=pos;
              };
              window.__tgEnter = function(){
                var el=activeInput(); if(!el) return;
                ['keydown','keypress','keyup'].forEach(function(t){ el.dispatchEvent(new KeyboardEvent(t,{key:'Enter',code:'Enter',keyCode:13,which:13,bubbles:true})); });
                if(el.form){ try{ el.form.requestSubmit ? el.form.requestSubmit() : el.form.submit(); }catch(e){} }
              };
            })();
        """.trimIndent()
        runCatching { webView.evaluateJavascript(js, null) }
    }

    private fun injectMapPanSupport() {
        val js = """
            (function(){
              if(window.__tgMapPanInstalled) return; window.__tgMapPanInstalled = true;
              window.__tgEdgePanState = {active:false,target:null,x:0,y:0};
              function pickTarget(x,y){
                var selectors = [
                  'canvas',
                  '[class*="map"] canvas',
                  '[class*="Map"] canvas',
                  '[class*="gl"] canvas',
                  '[class*="viewer"] canvas'
                ];
                for(var i=0;i<selectors.length;i++){
                  var found=document.querySelector(selectors[i]);
                  if(found) return found;
                }
                var el = document.elementFromPoint(x,y);
                while(el && (el.tagName === 'INPUT' || el.tagName === 'TEXTAREA' || el.isContentEditable)) {
                  el = el.parentElement;
                }
                return el || document.body || document.documentElement;
              }
              function fireMouse(target,type,x,y){
                target.dispatchEvent(new MouseEvent(type,{
                  bubbles:true,cancelable:true,view:window,
                  clientX:x,clientY:y,screenX:x,screenY:y,
                  button:0,buttons:type==='mouseup'?0:1
                }));
              }
              function firePointer(target,type,x,y){
                if(typeof PointerEvent === 'undefined') return;
                target.dispatchEvent(new PointerEvent(type,{
                  bubbles:true,cancelable:true,view:window,
                  pointerId:7,pointerType:'mouse',isPrimary:true,
                  clientX:x,clientY:y,screenX:x,screenY:y,
                  button:0,buttons:type==='pointerup'?0:1
                }));
              }
              function startPan(x,y){
                var st=window.__tgEdgePanState;
                if(st.active) return st;
                st.active=true;
                st.x=x; st.y=y;
                st.target=pickTarget(x,y);
                firePointer(st.target,'pointerdown',st.x,st.y);
                fireMouse(st.target,'mousedown',st.x,st.y);
                return st;
              }
              window.__tgMapPan = function(dx,dy){
                var w = Math.max(1, window.innerWidth || document.documentElement.clientWidth || 640);
                var h = Math.max(1, window.innerHeight || document.documentElement.clientHeight || 480);
                var st=startPan(Math.round(w * 0.5), Math.round(h * 0.52));
                var scale = 1.15;
                st.x = Math.max(8, Math.min(w - 8, st.x - dx * scale));
                st.y = Math.max(8, Math.min(h - 8, st.y - dy * scale));
                firePointer(st.target,'pointermove',st.x,st.y);
                fireMouse(st.target,'mousemove',st.x,st.y);
              };
              window.__tgMapPanStop = function(){
                var st=window.__tgEdgePanState;
                if(!st || !st.active) return;
                firePointer(st.target || document.body,'pointerup',st.x,st.y);
                fireMouse(st.target || document.body,'mouseup',st.x,st.y);
                st.active=false;
                st.target=null;
              };
            })();
        """.trimIndent()
        runCatching { webView.evaluateJavascript(js, null) }
    }

    private fun showKeyboard() {
        suppressImeFor(1200L)
        if (keyboardView == null) {
            keyboardView = CustomKeyboardView(this).apply {
                layoutParams = FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    Gravity.BOTTOM
                )
                setOnKeyboardActionListener(this@MainActivity)
            }
            keyboardContainer.addView(keyboardView)
        }
        keyboardView?.visibility = View.VISIBLE
        keyboardContainer.visibility = View.VISIBLE
        keyboardContainer.bringToFront()
        keyboardView?.bringToFront()
    }

    private fun hideKeyboard() {
        keyboardContainer.visibility = View.GONE
    }

    private fun handleLogicalClick(x: Float, y: Float): Boolean {
        suppressImeFor(1500L)
        if (keyboardContainer.visibility != View.VISIBLE) {
            webView.evaluateJavascript("window.__tgDefocus && window.__tgDefocus()", null)
            return false
        }
        val keyboard = keyboardView ?: return false
        val top = keyboardContainer.top.toFloat()
        val bottom = keyboardContainer.bottom.toFloat()
        if (y < top || y > bottom) {
            hideKeyboard()
            webView.evaluateJavascript("window.__tgDefocus && window.__tgDefocus()", null)
            return false
        }
        if (!keyboard.handleAnchoredTap(x, y - top)) return true
        suppressImeFor(900L)
        return true
    }

    private fun panRadioGardenMap(dx: Int, dy: Int) {
        if (dx == 0 && dy == 0) return
        webView.evaluateJavascript("window.__tgMapPan && window.__tgMapPan($dx,$dy)", null)
    }

    private fun stopRadioGardenMapPan() {
        webView.evaluateJavascript("window.__tgMapPanStop && window.__tgMapPanStop()", null)
    }

    private fun js(expr: String) = runCatching {
        suppressImeFor(900L)
        webView.requestFocus()
        webView.evaluateJavascript(expr, null)
    }

    override fun onKeyPressed(key: String) {
        // Edit the field's live value at its real caret (no stale native buffer).
        Log.d("TapGarden", "keyboard key='${key.replace("\n", "\\n")}'")
        js("window.__tgInsert && window.__tgInsert(${JSONObject.quote(key)})")
    }

    override fun onBackspacePressed() {
        Log.d("TapGarden", "keyboard backspace")
        js("window.__tgBackspace && window.__tgBackspace()")
    }

    override fun onEnterPressed() {
        Log.d("TapGarden", "keyboard enter")
        js("window.__tgEnter && window.__tgEnter()")
    }
    override fun onHideKeyboard() = hideKeyboard()
    override fun onClearPressed() {
        Log.d("TapGarden", "keyboard clear")
        js("window.__tgClear && window.__tgClear()")
    }
    override fun onMoveCursorLeft() {
        js("window.__tgMoveCaret && window.__tgMoveCaret(-1)")
    }
    override fun onMoveCursorRight() {
        js("window.__tgMoveCaret && window.__tgMoveCaret(1)")
    }
    override fun onMicrophonePressed() { /* no voice input in TapGarden */ }

    private fun enableImmersiveFullscreen() {
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = (
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                or View.SYSTEM_UI_FLAG_FULLSCREEN
                or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
            )
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        holdPlaybackResources()
        if (hasFocus) enableImmersiveFullscreen()
    }

    private fun shouldBlockRequest(url: String): Boolean {
        val uri = runCatching { android.net.Uri.parse(url) }.getOrNull() ?: return false
        val scheme = uri.scheme?.lowercase().orEmpty()
        if (scheme != "http" && scheme != "https") return false
        val host = uri.host?.lowercase()?.trim('.') ?: return false
        val path = uri.encodedPath?.lowercase().orEmpty()
        val query = uri.encodedQuery?.lowercase().orEmpty()

        if (host == "radio.garden" || host.endsWith(".radio.garden")) return false
        if (AD_HOSTS.any { host == it || host.endsWith(".$it") }) return true
        if (AD_HOST_KEYWORDS.any { host.contains(it) }) return true
        if (AD_PATH_KEYWORDS.any { path.contains(it) || query.contains(it) }) return true
        return false
    }

    private fun emptyBlockedResponse(): WebResourceResponse =
        WebResourceResponse(
            "text/plain",
            "utf-8",
            204,
            "No Content",
            mapOf("Cache-Control" to "no-store", "Access-Control-Allow-Origin" to "*"),
            ByteArrayInputStream(ByteArray(0))
        )

    private fun triggerRadioGardenAction(action: String) {
        val label = when (action) {
            "explore" -> "Explore"
            "favorites" -> "Favorites"
            "browse" -> "Browse"
            "search" -> "Search"
            "settings" -> "Settings"
            else -> return
        }
        val escaped = label.replace("'", "\\'")
        val js = """
            (function() {
              function textOf(el) { return ((el && el.innerText) || '').replace(/\s+/g, ' ').trim(); }
              var nodes = document.querySelectorAll('button, a, [role="button"], [role="tab"], div, span');
              for (var i = 0; i < nodes.length; i++) {
                var el = nodes[i];
                if (textOf(el).toLowerCase() !== '$escaped'.toLowerCase()) continue;
                var target = el.closest('button, a, [role="button"], [role="tab"]') || el;
                var r = target.getBoundingClientRect();
                if (r.width > 0 && r.height > 0) {
                  target.dispatchEvent(new MouseEvent('mousedown', {bubbles:true, clientX:r.left+r.width/2, clientY:r.top+r.height/2}));
                  target.dispatchEvent(new MouseEvent('mouseup', {bubbles:true, clientX:r.left+r.width/2, clientY:r.top+r.height/2}));
                  target.click();
                  return true;
                }
              }
              return false;
            })();
        """.trimIndent()
        runCatching { webView.evaluateJavascript(js, null) }
    }

    private fun injectRadioGardenHelpers() {
        val js = """
            (function() {
              if (window.__tapgardenGlassesLayout) {
                try { window.__tapgardenGlassesLayout.refresh(); } catch(e) {}
                return;
              }
              function textOf(el){ return ((el && el.innerText) || '').replace(/\s+/g,' ').trim(); }
              function rectOk(r){ return r && r.width > 0 && r.height > 0; }
              function ensureScrollbarStyle(){
                if(document.getElementById('tapgarden-wide-panel-scrollbars')) return;
                var style=document.createElement('style');
                style.id='tapgarden-wide-panel-scrollbars';
                style.textContent=[
                  '[data-tapgarden-panel="1"], [data-tapgarden-panel="1"] * { scrollbar-width: auto !important; }',
                  '[data-tapgarden-panel="1"]::-webkit-scrollbar, [data-tapgarden-panel="1"] *::-webkit-scrollbar { width: 18px !important; height: 18px !important; }',
                  '[data-tapgarden-panel="1"]::-webkit-scrollbar-thumb, [data-tapgarden-panel="1"] *::-webkit-scrollbar-thumb { min-height: 42px !important; min-width: 42px !important; border-radius: 9px !important; background: rgba(245,245,245,.82) !important; border: 3px solid rgba(24,24,24,.72) !important; }',
                  '[data-tapgarden-panel="1"]::-webkit-scrollbar-track, [data-tapgarden-panel="1"] *::-webkit-scrollbar-track { background: rgba(20,20,20,.42) !important; border-radius: 9px !important; }'
                ].join('\n');
                document.head.appendChild(style);
              }
              function findPanel(){
                var nodes=document.querySelectorAll('body *');
                var best=null;
                for(var i=0;i<nodes.length;i++){
                  var el=nodes[i], t=textOf(el).toLowerCase();
                  if(!(t.indexOf('explore')>=0 && t.indexOf('favorites')>=0 && t.indexOf('browse')>=0 && t.indexOf('search')>=0 && t.indexOf('settings')>=0)) continue;
                  var r=el.getBoundingClientRect();
                  if(rectOk(r) && r.left<=24 && r.top<=32 && r.width>=220 && r.width<=430){ best=el; break; }
                }
                while(best && best.parentElement && best.parentElement!==document.body){
                  var pr=best.parentElement.getBoundingClientRect();
                  if(rectOk(pr) && pr.left<=24 && pr.top<=32 && pr.width<=430) best=best.parentElement; else break;
                }
                return best;
              }
              function refresh(){
                ensureScrollbarStyle();
                var viewport=document.querySelector('meta[name="viewport"]');
                if(!viewport){ viewport=document.createElement('meta'); viewport.name='viewport'; document.head.appendChild(viewport); }
                viewport.content='width=device-width, initial-scale=1.0, maximum-scale=1.0, user-scalable=no, viewport-fit=cover';
                document.documentElement.style.setProperty('width','100%','important');
                document.body.style.setProperty('width','100%','important');
                var panel=findPanel();
                if(!panel) return;
                panel.setAttribute('data-tapgarden-panel','1');
                panel.style.setProperty('max-height','calc(100vh - 4px)','important');
                panel.style.setProperty('overflow-y','auto','important');
                panel.style.setProperty('border-radius','8px','important');
                panel.querySelectorAll('button, a, [role="button"], [role="tab"]').forEach(function(btn){
                  btn.style.setProperty('min-height','34px','important');
                  btn.style.setProperty('padding','3px 5px','important');
                  btn.style.setProperty('font-size','12px','important');
                  btn.style.setProperty('line-height','1.05','important');
                });
              }
              var obs=new MutationObserver(function(){ clearTimeout(refresh.timer); refresh.timer=setTimeout(refresh,120); });
              obs.observe(document.documentElement,{childList:true,subtree:true,attributes:true,attributeFilter:['class','style']});
              window.__tapgardenGlassesLayout={refresh:refresh};
              refresh(); setTimeout(refresh,500); setTimeout(refresh,1500);
            })();
        """.trimIndent()
        runCatching { webView.evaluateJavascript(js, null) }
    }

    private fun injectRadioGardenDarkMode() {
        val js = """
            (function() {
              if (window.__tapgardenDarkModeBootstrapped) return;
              window.__tapgardenDarkModeBootstrapped = true;
              try {
                ['theme','color-scheme','colorScheme','radio.garden.theme','radiogarden.theme'].forEach(function(key) {
                  try { localStorage.setItem(key, 'dark'); } catch(e) {}
                });
                ['darkMode','isDarkMode','radio.garden.darkMode','radiogarden.darkMode'].forEach(function(key) {
                  try { localStorage.setItem(key, 'true'); } catch(e) {}
                });
              } catch(e) {}
              try {
                if (!document.getElementById('tapgarden-dark-mode-default')) {
                  var style = document.createElement('style');
                  style.id = 'tapgarden-dark-mode-default';
                  style.textContent = ':root{color-scheme:dark!important}html,body{background:#07090b!important}';
                  document.head.appendChild(style);
                }
              } catch(e) {}
              try {
                var nativeMatchMedia = window.matchMedia && window.matchMedia.bind(window);
                if (nativeMatchMedia && !window.__tapgardenNativeMatchMedia) {
                  window.__tapgardenNativeMatchMedia = nativeMatchMedia;
                  window.matchMedia = function(query) {
                    if (String(query).indexOf('prefers-color-scheme') >= 0) {
                      var isDark = String(query).indexOf('dark') >= 0;
                      return {
                        matches: isDark,
                        media: query,
                        onchange: null,
                        addListener: function(){},
                        removeListener: function(){},
                        addEventListener: function(){},
                        removeEventListener: function(){},
                        dispatchEvent: function(){ return false; }
                      };
                    }
                    return window.__tapgardenNativeMatchMedia(query);
                  };
                }
              } catch(e) {}
            })();
        """.trimIndent()
        runCatching { webView.evaluateJavascript(js, null) }
    }

    private fun injectAdCleanup() {
        val js = """
            (function() {
              if (window.__tapgardenAdCleanupInstalled) return;
              window.__tapgardenAdCleanupInstalled = true;
              var selectors = [
                '[id*="ad-" i]', '[id*="_ad" i]', '[class*="ads" i]',
                '[aria-label*="advert" i]',
                'iframe[src*="doubleclick" i]', 'iframe[src*="googlesyndication" i]',
                'iframe[src*="googleadservices" i]', 'iframe[src*="adservice" i]'
              ];
              function hideAds(){
                selectors.forEach(function(sel){
                  document.querySelectorAll(sel).forEach(function(el){
                    var text=((el.innerText || el.getAttribute('aria-label') || '')+'').toLowerCase();
                    var src=((el.src || '')+'').toLowerCase();
                    if(text.indexOf('advert')>=0 || text.indexOf('sponsored')>=0 || /doubleclick|googlesyndication|googleadservices|adservice/.test(src)){
                      el.style.setProperty('display','none','important');
                    }
                  });
                });
              }
              hideAds();
              new MutationObserver(function(){ clearTimeout(hideAds.timer); hideAds.timer=setTimeout(hideAds,120); })
                .observe(document.documentElement,{childList:true,subtree:true});
            })();
        """.trimIndent()
        runCatching { webView.evaluateJavascript(js, null) }
    }

    private fun hasLocationPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    private fun requestLocationPermission() {
        if (hasLocationPermission()) return
        ActivityCompat.requestPermissions(
            this,
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION),
            REQ_LOCATION
        )
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_LOCATION) {
            val granted = grantResults.any { it == PackageManager.PERMISSION_GRANTED }
            pendingGeoCallback?.invoke(pendingGeoOrigin, granted, false)
            pendingGeoOrigin = null
            pendingGeoCallback = null
            if (granted) {
                injectBestKnownLocation()
                requestFreshLocation()
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun bestKnownLocation(): Location? {
        if (!hasLocationPermission()) return null
        val lm = getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return null
        var best: Location? = null
        for (provider in locationProviders(lm)) {
            val loc = runCatching { lm.getLastKnownLocation(provider) }.getOrNull() ?: continue
            if (best == null || loc.time > best!!.time) best = loc
        }
        return best
    }

    private fun locationProviders(lm: LocationManager): List<String> =
        listOf(
            LocationManager.FUSED_PROVIDER,
            LocationManager.GPS_PROVIDER,
            LocationManager.NETWORK_PROVIDER,
            LocationManager.PASSIVE_PROVIDER
        ).filter { provider ->
            runCatching { lm.allProviders.contains(provider) && lm.isProviderEnabled(provider) }
                .getOrDefault(false)
        }

    private fun installGeolocationBridge() {
        val js = """
            (function(){
              if(window.__tapgardenGeoInstalled) return;
              window.__tapgardenGeoInstalled = true;
              if(!window.__tapgardenGeoWatchers) window.__tapgardenGeoWatchers = {};
              if(!window.__tapgardenGeoNextWatchId) window.__tapgardenGeoNextWatchId = 1;
              function wait(success,error){
                if(window.__injectedPosition){ setTimeout(function(){success(window.__injectedPosition);},10); return; }
                var settled=false;
                function done(pos){ if(settled) return; settled=true; window.removeEventListener('tapgarden-location-updated', onUpdate); success(pos || window.__injectedPosition); }
                function onUpdate(ev){ done(ev && ev.detail); }
                window.addEventListener('tapgarden-location-updated', onUpdate);
                setTimeout(function(){
                  if(settled) return;
                  window.removeEventListener('tapgarden-location-updated', onUpdate);
                  if(window.__injectedPosition) done(window.__injectedPosition);
                  else if(error) error({code:2,message:'Position unavailable'});
                },8000);
              }
              if(navigator.permissions){
                var oq=navigator.permissions.query.bind(navigator.permissions);
                navigator.permissions.query=function(p){
                  if(p && p.name==='geolocation') return Promise.resolve({state:'granted',onchange:null});
                  return oq(p);
                };
              }
              var mock={
                getCurrentPosition:function(s,e,o){ wait(s,e); },
                watchPosition:function(s,e,o){ var id=window.__tapgardenGeoNextWatchId++; window.__tapgardenGeoWatchers[id]=s; wait(s,e); return id; },
                clearWatch:function(id){ delete window.__tapgardenGeoWatchers[id]; }
              };
              try{ Object.defineProperty(navigator,'geolocation',{value:mock,configurable:true}); }
              catch(e){ navigator.geolocation.getCurrentPosition=mock.getCurrentPosition; navigator.geolocation.watchPosition=mock.watchPosition; navigator.geolocation.clearWatch=mock.clearWatch; }
            })();
        """.trimIndent()
        runCatching { webView.evaluateJavascript(js, null) }
    }

    private fun injectBestKnownLocation() {
        installGeolocationBridge()
        bestKnownLocation()?.let { injectLocation(it) }
    }

    @SuppressLint("MissingPermission")
    private fun requestFreshLocation() {
        if (!hasLocationPermission()) return
        val lm = getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return
        val providers = locationProviders(lm)
        if (providers.isEmpty()) return
        freshLocationListener?.let { runCatching { lm.removeUpdates(it) } }
        val listener = object : LocationListener {
            override fun onLocationChanged(location: Location) {
                injectLocation(location)
                runCatching { lm.removeUpdates(this) }
                if (freshLocationListener === this) freshLocationListener = null
            }

            @Deprecated("Deprecated in Java")
            override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = Unit
            override fun onProviderEnabled(provider: String) = Unit
            override fun onProviderDisabled(provider: String) = Unit
        }
        freshLocationListener = listener
        providers.forEach { provider ->
            runCatching { lm.requestSingleUpdate(provider, listener, mainLooper) }
        }
    }

    private fun injectLocation(location: Location) {
        val acc = if (location.hasAccuracy()) location.accuracy.toDouble() else 50.0
        val js = """
            (function(){
              var pos = { coords: { latitude: ${location.latitude}, longitude: ${location.longitude}, accuracy: $acc,
                altitude: null, altitudeAccuracy: null, heading: null, speed: null }, timestamp: Date.now() };
              window.__injectedPosition = pos;
              var watchers = window.__tapgardenGeoWatchers || {};
              for(var id in watchers){ if(watchers.hasOwnProperty(id) && typeof watchers[id] === 'function'){ try{ watchers[id](pos); }catch(e){} } }
              window.dispatchEvent(new CustomEvent('tapgarden-location-updated', { detail: pos }));
            })();
        """.trimIndent()
        runCatching { webView.evaluateJavascript(js, null) }
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        when {
            this::keyboardContainer.isInitialized && keyboardContainer.visibility == View.VISIBLE -> hideKeyboard()
            this::webView.isInitialized && webView.canGoBack() -> webView.goBack()
            else -> @Suppress("DEPRECATION") super.onBackPressed()
        }
    }

    override fun onDestroy() {
        freshLocationListener?.let { listener ->
            runCatching {
                (getSystemService(Context.LOCATION_SERVICE) as? LocationManager)?.removeUpdates(listener)
            }
        }
        freshLocationListener = null
        releasePlaybackResources()
        stopPlaybackService()
        runCatching { webView.destroy() }
        super.onDestroy()
    }

    companion object {
        private const val REQ_LOCATION = 1001
        private val AD_HOSTS = setOf(
            "doubleclick.net",
            "googlesyndication.com",
            "googleadservices.com",
            "googletagservices.com",
            "google-analytics.com",
            "adservice.google.com",
            "adsystem.com",
            "amazon-adsystem.com",
            "adsrvr.org",
            "adnxs.com",
            "rubiconproject.com",
            "pubmatic.com",
            "openx.net",
            "criteo.com",
            "criteo.net",
            "taboola.com",
            "outbrain.com",
            "scorecardresearch.com",
            "quantserve.com",
            "moatads.com"
        )
        private val AD_HOST_KEYWORDS = setOf("adserver", "adservice", "adnxs", "tracking", "telemetry", "metrics", "beacon")
        private val AD_PATH_KEYWORDS = setOf("/ads", "/ad/", "/ad?", "/advert", "/analytics", "/tracking", "/track?", "/beacon", "/pixel", "google_ads", "prebid")
    }
}
