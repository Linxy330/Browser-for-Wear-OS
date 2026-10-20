# Error Prone's compile-time annotation refers to the host JDK language model.
# Android instrumentation never executes that annotation API; keep this exclusion
# limited to the test APK so production missing-class checks remain unchanged.
-dontwarn javax.lang.model.element.Modifier
