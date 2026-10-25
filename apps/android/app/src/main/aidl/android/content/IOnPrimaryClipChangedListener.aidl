package android.content;

// Re-declares the framework callback so the daemon can hand a Binder stub to the hidden
// IClipboard.addPrimaryClipChangedListener. Package, name and method must match the platform's.
oneway interface IOnPrimaryClipChangedListener {
    void dispatchPrimaryClipChanged();
}
