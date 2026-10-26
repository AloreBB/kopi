package dev.alorebb.kopi.daemon;

// App -> KopiDaemon (Shizuku UserService running as the shell uid).
interface IKopiDaemon {
    // JSON of DaemonStatus.
    String status() = 1;

    // Code 2 was pair(pairingUri, phoneName): pairing is automatic now. Do not reuse it.

    // Deletes state.json, disconnects and looks for a Mac to pair with again.
    void unpair() = 3;

    // Stops syncing and ends the process.
    void stop() = 4;

    // Reserved transaction code Shizuku requires on every UserService.
    void destroy() = 16777114;
}
