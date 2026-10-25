// Shared protocol constants (see Global Constraints in the v1 plan).
public enum Limits {
    public static let protocolVersion = 1
    public static let appVersion = "1.0.0"
    public static let bundleID = "dev.alorebb.kopi"
    public static let bonjourServiceType = "_kopi._tcp"
    public static let serverPort: UInt16 = 47474
    public static let offerExpiryMs: Int64 = 120_000
    // Copies crossing in flight within this local-time window are resolved by sender ts.
    public static let conflictWindowMs: Int64 = 2_000
    public static let maxTextBytes = 4 * 1024 * 1024
    public static let allowedImageMimes = ["image/png", "image/jpeg"]
    public static let maxImageBytes = 20 * 1024 * 1024
    public static let imageChunkBytes = 256 * 1024
    public static let pasteWaitSeconds = 5
    public static let pingIntervalSeconds = 30
    public static let previewMaxCharacters = 40
}
