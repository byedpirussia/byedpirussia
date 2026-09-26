package org.amnezia.awg

object GoBackend {
    init {
        System.loadLibrary("wg-go")
    }

    /**
     * Turns on the AmneziaWG tunnel.
     * @param ifName Name of the interface (e.g. "awg0" or "tun")
     * @param tunFd File descriptor of the TUN interface (must be detached from ParcelFileDescriptor)
     * @param settings UAPI format configuration settings string
     * @return 0 on success or an error code / handle
     */
    @JvmStatic
    external fun awgTurnOn(ifName: String, tunFd: Int, settings: String): Int

    /**
     * Turns off the AmneziaWG tunnel.
     * @param handle Tunnel handle (or tunFd / 0)
     */
    @JvmStatic
    external fun awgTurnOff(handle: Int)

    /**
     * Retrieves the IPv4 socket file descriptor for VPN protection.
     */
    @JvmStatic
    external fun awgGetSocketV4(handle: Int): Int

    /**
     * Retrieves the IPv6 socket file descriptor for VPN protection.
     */
    @JvmStatic
    external fun awgGetSocketV6(handle: Int): Int

    /**
     * Retrieves current tunnel runtime configuration in UAPI format.
     */
    @JvmStatic
    external fun awgGetConfig(handle: Int): String?

    /**
     * Returns the AmneziaWG Go backend version.
     */
    @JvmStatic
    external fun awgVersion(): String
}
