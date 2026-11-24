# The web page calls these through window.AquaNative; R8 must not rename/remove them.
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}
-keep class app.netlify.create_app_4a7921.twa.MainActivity$Bridge { *; }
