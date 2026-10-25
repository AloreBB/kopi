package dev.alorebb.kopi.daemon;

// App -> KopiDaemon (Shizuku UserService running as the shell uid).
interface IKopiDaemon {
    // JSON of DaemonStatus.
    String status() = 1;

    // Blocks until the handshake ends (<= 15 s); call off the main thread.
    boolean pair(String pairingUri, String phoneName) = 2;

    // Deletes state.json and disconnects.
    void unpair() = 3;

    // Stops syncing and ends the process.
    void stop() = 4;

    // Reserved transaction code Shizuku requires on every UserService.
    void destroy() = 16777114;
}
