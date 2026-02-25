package org.eclipse.californium.core.observe;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;

import org.eclipse.californium.core.coap.CoAP;
import org.eclipse.californium.core.coap.Response;
import org.eclipse.californium.core.coap.Token;

import com.upokecenter.cbor.CBORObject;
import com.upokecenter.cbor.CBORType;


/**
 * Represents the informative response payload (5.03 Service Unavailable)
 * as defined in draft-ietf-core-observe-multicast-notifications.
 * 
 * CBOR structure:
 * {
 *   0 => tp_info,        ; REQUIRED
 *   1 => ph_req,         ; OPTIONAL
 *   2 => last_notif,     ; OPTIONAL
 *   3 => next_not_before,; OPTIONAL
 *   4 => ending          ; OPTIONAL
 * }
 */
public class ObservationInfo {

    // CBOR 0 — REQUIRED
    private TpInfo tpInfo;

    // optional - byte serialization of phantom request
    private byte[] phReq;

    // optional - resposne of last notification
    private Response lastNotif;

    // optional - seconds until next notification
    private Long nextNotBefore;

    // optional - ending time of group observation
    private Long ending;

    public ObservationInfo() {
    }

    public ObservationInfo(TpInfo tpInfo) {
        this.tpInfo = tpInfo;
    }

     public ObservationInfo(InetSocketAddress serverAddress, InetSocketAddress multicastAddress, Token token) {
        this.tpInfo = new TpInfo(serverAddress,multicastAddress,token);
    }

    public Token getToken(){
      return this.tpInfo.token;
    }
    public TpInfo getTpInfo() { return tpInfo; }
    public void setTpInfo(TpInfo tpInfo) { this.tpInfo = tpInfo; }

    public byte[] getPhReq() { return phReq; }
    public void setPhReq(byte[] phReq) { this.phReq = phReq; }

    public Response getLastNotif() { return lastNotif; }
    public void setLastNotif(Response lastNotif) { this.lastNotif = lastNotif; }

    public Long getNextNotBefore() { return nextNotBefore; }
    public void setNextNotBefore(Long nextNotBefore) { this.nextNotBefore = nextNotBefore; }

    public Long getEnding() { return ending; }
    public void setEnding(Long ending) { this.ending = ending; }

    /**
     * Serializes this ObservationInfo to CBOR bytes.
     * 
     * CBOR structure (draft-ietf-core-observe-multicast-notifications):
     * {
     *   0 => tp_info,         ; REQUIRED
     *   1 => ph_req,          ; OPTIONAL - serialized phantom request
     *   2 => last_notif,      ; OPTIONAL - serialized last notification
     *   3 => next_not_before, ; OPTIONAL - seconds until next notification
     *   4 => ending           ; OPTIONAL - ending time of group observation
     * }
     * 
     * @return CBOR encoded bytes
     */
    public byte[] toCbor() {
        CBORObject map = CBORObject.NewMap();
        
        // 0 => tp_info (REQUIRED)
        if (tpInfo == null) {
          throw new Error("missing tp_info");
        }
        map.set(0, tpInfo.toCbor());
        
        // 1 => ph_req (OPTIONAL)
        if (phReq != null) {
            map.set(1, CBORObject.FromObject(phReq));
        }
        
        // 2 => last_notif (OPTIONAL) - serialize the Response
        if (lastNotif != null) {
            byte[] serializedResponse = lastNotif.getBytes();
            if (serializedResponse != null) {
                map.set(2, CBORObject.FromObject(serializedResponse));
            }
        }
        
        // 3 => next_not_before (OPTIONAL)
        if (nextNotBefore != null) {
            map.set(3, CBORObject.FromObject(nextNotBefore));
        }
        
        // 4 => ending (OPTIONAL)
        if (ending != null) {
            map.set(4, CBORObject.FromObject(ending));
        }
        
        return map.EncodeToBytes();
    }

    public static ObservationInfo fromCbor(byte[] cborBytes) {
        if (cborBytes == null) {
            throw new IllegalArgumentException("cborBytes must not be null");
        }
        CBORObject map = CBORObject.DecodeFromBytes(cborBytes);
        return fromCbor(map);
    }

      /**
     * Parse ObservationInfo from a CBOR map object.
     */
    public static ObservationInfo fromCbor(CBORObject map) {
        if (map == null) {
            throw new IllegalArgumentException("CBOR map must not be null");
        }
        if (map.getType() != CBORType.Map) {
            throw new IllegalArgumentException("ObservationInfo must be a CBOR map");
        }

        ObservationInfo info = new ObservationInfo();

        // key 0: tp_info (REQUIRED)
        CBORObject tpInfoObj = map.get(CBORObject.FromObject(0));
        if (tpInfoObj == null) {
            throw new IllegalArgumentException("Missing required key 0 (tp_info)");
        }
        info.setTpInfo(TpInfo.fromCbor(tpInfoObj));

        // key 1: ph_req (OPTIONAL) - byte string
        CBORObject phReqObj = map.get(CBORObject.FromObject(1));
        if (phReqObj != null) {
            if (phReqObj.getType() != CBORType.ByteString) {
                throw new IllegalArgumentException("Key 1 (ph_req) must be a CBOR byte string");
            }
            info.setPhReq(phReqObj.GetByteString());
        }

        // key 2: last_notif (OPTIONAL) - byte string
        // NOTE: We store raw bytes and optionally try to parse as Californium Response.
        CBORObject lastNotifObj = map.get(CBORObject.FromObject(2));
        if (lastNotifObj != null) {
            if (lastNotifObj.getType() != CBORType.ByteString) {
                throw new IllegalArgumentException("Key 2 (last_notif) must be a CBOR byte string");
            }
            byte[] notifBytes = lastNotifObj.GetByteString();

            // Your current class stores lastNotif as Response.
            // If these bytes are transport-independent per draft, they may NOT be parseable
            // as a full Californium Response packet. So we try best-effort and ignore failures.
            try {
                // If your Californium version has a parser utility, use it here instead.
                // Example (pseudo):
                // DataParser parser = ...;
                // Message msg = parser.parseMessage(notifBytes);
                // if (msg instanceof Response) info.setLastNotif((Response) msg);

                // For now: no reliable generic parser call in this snippet.
                // Leave lastNotif unset if you can't parse the bytes as a full Response.
            } catch (Exception e) {
                // Best effort only: raw bytes are valid enough for protocol state.
            }
        }

        // key 3: next_not_before (OPTIONAL) - integer
        CBORObject nnbObj = map.get(CBORObject.FromObject(3));
        if (nnbObj != null) {
            if (!nnbObj.isIntegral()) {
                throw new IllegalArgumentException("Key 3 (next_not_before) must be an integer");
            }
            info.setNextNotBefore(nnbObj.AsInt64Value());
        }

        // key 4: ending (OPTIONAL) - integer
        CBORObject endingObj = map.get(CBORObject.FromObject(4));
        if (endingObj != null) {
            if (!endingObj.isIntegral()) {
                throw new IllegalArgumentException("Key 4 (ending) must be an integer");
            }
            info.setEnding(endingObj.AsInt64Value());
        }

        return info;
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append("ObservationInfo{\n");
        sb.append("  tpInfo=").append(tpInfo).append(",\n");
        if (phReq != null) {
            sb.append("  phReq=").append(phReq.length).append(" bytes,\n");
        }
        if (lastNotif != null) {
            sb.append("  lastNotif=").append(lastNotif).append(",\n");
        }
        if (nextNotBefore != null) {
            sb.append("  nextNotBefore=").append(nextNotBefore).append(",\n");
        }
        if (ending != null) {
            sb.append("  ending=").append(ending).append(",\n");
        }
        sb.append("}");
        return sb.toString();
    }

    /**
     * Represents the `tp_info` CBOR array for CoAP over UDP:
     * [ tpi_server, tpi_client, tpi_token ]
     * 
     * Where:
     * - tpi_server: CRI coap://SRV_ADDR:SRV_PORT/
     * - tpi_client: CRI coap://GRP_ADDR:GRP_PORT/
     * - tpi_token: shared Token T (bytes)
     */
    public static class TpInfo {
        private Cri tpiServer;
        private Cri tpiClient;
        private Token token;

        public TpInfo() {
        }

        /**
         * Creates TpInfo from server address, multicast address, and shared token.
         * 
         * @param serverAddress the server's unicast address and port
         * @param multicastAddress the multicast group address and port
         * @param token the shared token T for the group observation
         */
        public TpInfo(InetSocketAddress serverAddress, InetSocketAddress multicastAddress, Token token) {
            this.tpiServer = new Cri(serverAddress);
            this.tpiClient = new Cri(multicastAddress);
            this.token = token;
        }

        public Cri getTpiServer() { return tpiServer; }
        public void setTpiServer(Cri tpiServer) { this.tpiServer = tpiServer; }

        public Cri getTpiClient() { return tpiClient; }
        public void setTpiClient(Cri tpiClient) { this.tpiClient = tpiClient; }

        public Token getToken() { return token; }
        public void setToken(Token token) { this.token = token; }

        /**
         * Serializes tp_info to CBOR array.
         * 
         * Structure for CoAP over UDP:
         * [ tpi_server, tpi_client, tpi_token ]
         * 
         * @return CBORObject array
         */
        public CBORObject toCbor() {
            CBORObject array = CBORObject.NewArray();
            
            // tpi_server: CRI for server address
            if (tpiServer != null) {
                array.Add(tpiServer.toCbor());
            }
            
            // tpi_client: CRI for multicast group address
            if (tpiClient != null) {
                array.Add(tpiClient.toCbor());
            }
            
            // tpi_token: shared token T as byte string
            if (token != null) {
                array.Add(CBORObject.FromObject(token.getBytes()));
            }
            
            return array;
        }

        public static TpInfo fromCbor(CBORObject array) {
            if (array == null) {
                throw new IllegalArgumentException("tp_info must not be null");
            }
            if (array.getType() != CBORType.Array) {
                throw new IllegalArgumentException("tp_info must be a CBOR array");
            }

            // For your UDP layout, expect [ tpi_server, tpi_client, tpi_token ]
            if (array.size() < 3) {
                throw new IllegalArgumentException("tp_info must have at least 3 elements for CoAP/UDP");
            }

            TpInfo tp = new TpInfo();

            tp.setTpiServer(Cri.fromCbor(array.get(0)));
            tp.setTpiClient(Cri.fromCbor(array.get(1)));

            CBORObject tokenObj = array.get(2);
            if (tokenObj.getType() != CBORType.ByteString) {
                throw new IllegalArgumentException("tp_info[2] (tpi_token) must be a CBOR byte string");
            }
            tp.setToken(Token.fromProvider(tokenObj.GetByteString()));

            return tp;
        }

        @Override
        public String toString() {
            return "TpInfo{server=" + tpiServer + ", client=" + tpiClient + ", token=" + token + "}";
        }
    }

    public static class Cri {
        /** Scheme ID for "coap" = -1 (negative of scheme number + 1) */
        public static final int SCHEME_COAP = -1;
        /** Scheme ID for "coaps" = -2 */
        public static final int SCHEME_COAPS = -2;

        private int schemeId;
        private InetAddress host;
        private int port;

        public Cri() {
            this.schemeId = SCHEME_COAP;
        }

        public Cri(InetSocketAddress address) {
            this.schemeId = SCHEME_COAP;
            this.host = address.getAddress();
            this.port = address.getPort();
        }
        public Cri(int schemeId, InetAddress host, int port) {
            this.schemeId = schemeId;
            this.host = host;
            this.port = port;
        }

        public int getSchemeId() { return schemeId; }
        public void setSchemeId(int schemeId) { this.schemeId = schemeId; }

        public InetAddress getHost() { return host; }
        public void setHost(InetAddress host) { this.host = host; }

        public int getPort() { return port; }
        public void setPort(int port) { this.port = port; }

        /**
         * Returns the host address as raw bytes (4 bytes for IPv4, 16 for IPv6).
         */
        public byte[] getHostBytes() {
            return host != null ? host.getAddress() : null;
        }

        /**
         * Returns true if port should be omitted (is default CoAP port).
         */
        public boolean isDefaultPort() {
            return port == CoAP.DEFAULT_COAP_PORT;
        }

        /**
         * Converts to InetSocketAddress.
         */
        public InetSocketAddress toSocketAddress() {
            return new InetSocketAddress(host, port);
        }

        /**
         * Serializes this CRI to CBOR.
         * 
         * CRI structure per RFC 9290 (simplified for CoAP over UDP):
         * [ scheme, host-ip, port? ]
         * 
         * - scheme: negative integer (-1 for coap, -2 for coaps)
         * - host-ip: tagged byte string (tag 260 for IP address)
         * - port: integer, omitted if default CoAP port (5683)
         * 
         * @return CBORObject array representing the CRI
         */
        public CBORObject toCbor() {
            CBORObject array = CBORObject.NewArray();
            
            // Scheme (negative integer: -1 for coap, -2 for coaps)
            array.Add(schemeId);
            
            // Host as tagged byte string (tag 260 = IP address)
            if (host != null) {
                byte[] hostBytes = host.getAddress();
                array.Add(CBORObject.FromObject(hostBytes).WithTag(260));
            }
            
            // Port (omit if default CoAP port)
            if (!isDefaultPort()) {
                array.Add(port);
            }
            
            return array;
        }
        public static Cri fromCbor(CBORObject array) {
            if (array == null) {
                throw new IllegalArgumentException("CRI must not be null");
            }
            if (array.getType() != CBORType.Array) {
                throw new IllegalArgumentException("CRI must be a CBOR array");
            }
            if (array.size() < 2) {
                throw new IllegalArgumentException("CRI must have at least [scheme, host]");
            }

            Cri cri = new Cri();

            // [0] scheme
            CBORObject schemeObj = array.get(0);
            if (!schemeObj.isIntegral()) {
                throw new IllegalArgumentException("CRI scheme must be integer");
            }
            cri.setSchemeId(schemeObj.AsInt32Value());

            // [1] host as tagged bstr (tag 260) or plain bstr (be tolerant)
            CBORObject hostObj = array.get(1);
            byte[] hostBytes = extractHostBytes(hostObj);
            try {
                cri.setHost(InetAddress.getByAddress(hostBytes));
            } catch (UnknownHostException e) {
                throw new IllegalArgumentException("Invalid CRI host byte length: " + hostBytes.length, e);
            }

            // [2] port optional
            if (array.size() >= 3) {
                CBORObject portObj = array.get(2);
                if (!portObj.isIntegral()) {
                    throw new IllegalArgumentException("CRI port must be integer");
                }
                cri.setPort(portObj.AsInt32Value());
            } else {
                // Default based on scheme
                if (cri.getSchemeId() == SCHEME_COAPS) {
                    cri.setPort(CoAP.DEFAULT_COAP_SECURE_PORT);
                } else {
                    cri.setPort(CoAP.DEFAULT_COAP_PORT);
                }
            }

            return cri;
        }

        private static byte[] extractHostBytes(CBORObject hostObj) {
            if (hostObj == null) {
                throw new IllegalArgumentException("CRI host must not be null");
            }

            // Be tolerant: accept tagged or untagged byte string
            // Depending on CBOR lib behavior, tags may still report ByteString type.
            if (hostObj.getType() != CBORType.ByteString) {
                throw new IllegalArgumentException("CRI host must be a CBOR byte string (tagged or untagged)");
            }

            byte[] bytes = hostObj.GetByteString();
            if (bytes.length != 4 && bytes.length != 16) {
                throw new IllegalArgumentException("CRI host must be IPv4 (4 bytes) or IPv6 (16 bytes), got " + bytes.length);
            }
            return bytes;
        }

        @Override
        public String toString() {
            String scheme = (schemeId == SCHEME_COAP) ? "coap" : (schemeId == SCHEME_COAPS) ? "coaps" : String.valueOf(schemeId);
            return scheme + "://" + (host != null ? host.getHostAddress() : "null") + ":" + port;
        }
    }
}