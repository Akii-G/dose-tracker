// Checks SyncCrypto.swift against the values test/test_model.py checks on the PC, so the
// iPhone and the PC encrypt the same way. Run by the iOS workflow:
//   swiftc -o crypto-check ios/Dose/SyncCrypto.swift ios/CryptoCheck/main.swift && ./crypto-check
import CryptoKit
import Foundation

func hex(_ data: Data) -> String { data.map { String(format: "%02x", $0) }.joined() }

let key = SyncCrypto.key("test-token-0123456789")
let keyHex = hex(key.withUnsafeBytes { Data($0) })
guard keyHex == "3fbf719d97272440ee91a3f08240c8bae2cb00600c386941d0a7ec35357340ca" else {
    print("key mismatch: \(keyHex)"); exit(1)
}
let box = try AES.GCM.seal(Data("{\"a\":1}".utf8), using: key, nonce: try AES.GCM.Nonce(data: Data(count: 12)),
                           authenticating: SyncCrypto.aad("/api/e/ping", reply: false))
let ct = hex(box.ciphertext + box.tag)
guard ct == "8795b68f38be54580422caa7482cff3c237c0969349bed" else { print("ciphertext mismatch: \(ct)"); exit(1) }
let reply = try AES.GCM.seal(Data("{\"ok\":true}".utf8), using: key, nonce: AES.GCM.Nonce(),
                             authenticating: SyncCrypto.aad("/api/e/sync", reply: true))
let doc: [String: Any] = ["v": 1, "n": reply.nonce.withUnsafeBytes { Data($0) }.base64EncodedString(),
                          "c": (reply.ciphertext + reply.tag).base64EncodedString()]
let opened = try SyncCrypto.open(try JSONSerialization.data(withJSONObject: doc), token: "test-token-0123456789", path: "/api/e/sync")
guard String(data: opened, encoding: .utf8) == "{\"ok\":true}" else { print("round trip failed"); exit(1) }
print("crypto vectors OK")
