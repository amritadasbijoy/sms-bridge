# Strip verbose/debug/info logging from release builds as a second line of defence
# against accidental disclosure. App code never logs message content regardless.
-assumenosideeffects class android.util.Log {
    public static int v(...);
    public static int d(...);
    public static int i(...);
}
