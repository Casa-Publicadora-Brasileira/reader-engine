# Keep @JavascriptInterface methods exposed to WebView
-keepattributes *Annotation*
-keepclassmembers class com.readerengine.app.ScrollProgressInterface {
    @android.webkit.JavascriptInterface <methods>;
}
