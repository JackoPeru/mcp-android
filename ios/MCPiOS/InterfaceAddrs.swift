import Foundation
import MCPiOSCore

#if canImport(Darwin)
import Darwin
#else
import Glibc
#endif

/// Lettura interfacce IPv4 senza dipendenze: primo RFC1918 (Wi-Fi/LAN)
/// e primo 100.64/10 (Tailscale). Nessun bind su 0.0.0.0.
public enum InterfaceAddrs {
    public static func allIPv4() -> [String] {
        var out: [String] = []
        var head: UnsafeMutablePointer<ifaddrs>?
        guard getifaddrs(&head) == 0, let first = head else { return out }
        defer { freeifaddrs(head) }
        var cursor: UnsafeMutablePointer<ifaddrs>? = first
        while let node = cursor?.pointee {
            if node.ifa_addr.pointee.sa_family == UInt8(AF_INET),
               let name = node.ifa_name.map({ String(cString: $0) }),
               !name.hasPrefix("lo") {
                var host = [CChar](repeating: 0, count: Int(NI_MAXHOST))
                if getnameinfo(node.ifa_addr, socklen_t(MemoryLayout<sockaddr>.size),
                               &host, socklen_t(host.count), nil, 0, NI_NUMERICHOST) == 0 {
                    out.append(String(cString: host))
                }
            }
            cursor = node.ifa_next
        }
        return out
    }
    public static func firstRFC1918() -> String? { allIPv4().first(where: HttpServer.isRfc1918) }
    public static func firstTailscale() -> String? { allIPv4().first(where: HttpServer.isTailscale) }
}
