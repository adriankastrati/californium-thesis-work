package org.eclipse.californium.core.observe;

import java.io.ByteArrayOutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;

import org.eclipse.californium.core.coap.CoAP;
import org.eclipse.californium.core.coap.OptionSet;
import org.eclipse.californium.core.coap.Response;
import org.eclipse.californium.core.coap.Token;
import org.eclipse.californium.core.network.serialization.DataSerializer;
import org.eclipse.californium.elements.util.DatagramWriter;


/**
 * Represents the informative response payload (5.03 Service Unavailable)
 * as defined in draft-ietf-core-observe-multicast-notifications-13.
 * 
 * <pre>
 * informative_response_payload = {
 *    0 => array, ; 'tp_info' (transport-specific information)
 *  ? 1 => bstr,  ; 'ph_req' (transport-independent information)
 *  ? 2 => bstr,  ; 'last_notif' (transport-independent information)
 *  ? 3 => uint,  ; 'next_not_before'
 *  ? 4 => ~time  ; 'ending'
 * }
 * </pre>
 * 
 * CBOR encoding and decoding is done without external libraries using
 * lightweight helpers for the small subset of CBOR types required.
 * 
 * @see <a href="https://datatracker.ietf.org/doc/draft-ietf-core-observe-multicast-notifications/">
 *      draft-ietf-core-observe-multicast-notifications</a>
 */
public class ObservationInfo {

    // CBOR key 0 — REQUIRED: transport protocol information
    private TpInfo tpInfo;

    // CBOR key 1 — OPTIONAL: byte serialization of phantom request
    // (transport-independent: code byte + serialized options + optional payload marker + payload)
    private byte[] phReq;

    // CBOR key 2 — OPTIONAL: byte serialization of the last multicast notification
    // (transport-independent: code byte + serialized options + optional payload marker + payload)
    private byte[] lastNotifBytes;

    // CBOR key 3 — OPTIONAL: seconds until next notification (uint)
    private Long nextNotBefore;

    // CBOR key 4 — OPTIONAL: ending time of group observation (~time)
    private Long ending;

    public ObservationInfo() {
    }

    public ObservationInfo(TpInfo tpInfo) {
        this.tpInfo = tpInfo;
    }

    public ObservationInfo(InetSocketAddress serverAddress, InetSocketAddress multicastAddress, Token token) {
        this.tpInfo = new TpInfo(serverAddress, multicastAddress, token);
    }

    public Token getToken() {
        return this.tpInfo.token;
    }

    public TpInfo getTpInfo() {
        return tpInfo;
    }

    public void setTpInfo(TpInfo tpInfo) {
        this.tpInfo = tpInfo;
    }

    public byte[] getPhReq() {
        return phReq;
    }

    public void setPhReq(byte[] phReq) {
        this.phReq = phReq;
    }

    /**
     * Gets the transport-independent serialization of the last multicast notification.
     * 
     * @return the serialized bytes, or {@code null}
     */
    public byte[] getLastNotifBytes() {
        return lastNotifBytes;
    }

    /**
     * Sets the transport-independent serialization of the last multicast notification.
     * 
     * @param lastNotifBytes serialized as per Section 4.2.2:
     *        code byte + serialized options + optional (0xFF + payload)
     */
    public void setLastNotifBytes(byte[] lastNotifBytes) {
        this.lastNotifBytes = lastNotifBytes;
    }

    /**
     * Sets last_notif from a Californium {@link Response} by serializing it
     * to the transport-independent format defined in Section 4.2.2 of the draft.
     * <p>
     * The format is: code_byte | serialized_options | [0xFF | payload]
     * 
     * @param response the last multicast notification response
     */
    public void setLastNotif(Response response) {
        if (response == null) {
            this.lastNotifBytes = null;
            return;
        }
        this.lastNotifBytes = serializeTransportIndependent(response);
    }

    public Long getNextNotBefore() {
        return nextNotBefore;
    }

    public void setNextNotBefore(Long nextNotBefore) {
        this.nextNotBefore = nextNotBefore;
    }

    public Long getEnding() {
        return ending;
    }

    public void setEnding(Long ending) {
        this.ending = ending;
    }

    /**
     * Serializes this ObservationInfo to CBOR bytes.
     * <p>
     * CBOR structure (draft-ietf-core-observe-multicast-notifications-13, Section 11):
     * <pre>
     * informative_response_payload = {
     *    0 => array, ; 'tp_info'
     *  ? 1 => bstr,  ; 'ph_req'
     *  ? 2 => bstr,  ; 'last_notif'
     *  ? 3 => uint,  ; 'next_not_before'
     *  ? 4 => ~time  ; 'ending'
     * }
     * </pre>
     * 
     * @return CBOR encoded bytes
     * @throws IllegalStateException if tp_info is not set
     */
    public byte[] toCbor() {
        if (tpInfo == null) {
            throw new IllegalStateException("missing tp_info");
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();

        // Count map entries
        int entries = 1; // key 0 (tp_info) always present
        if (phReq != null) entries++;
        if (lastNotifBytes != null) entries++;
        if (nextNotBefore != null) entries++;
        if (ending != null) entries++;

        cborWriteMapHeader(out, entries);

        // 0 => tp_info (REQUIRED)
        cborWriteUint(out, 0);
        tpInfo.writeCbor(out);

        // 1 => ph_req (OPTIONAL)
        if (phReq != null) {
            cborWriteUint(out, 1);
            cborWriteBstr(out, phReq);
        }

        // 2 => last_notif (OPTIONAL)
        if (lastNotifBytes != null) {
            cborWriteUint(out, 2);
            cborWriteBstr(out, lastNotifBytes);
        }

        // 3 => next_not_before (OPTIONAL)
        if (nextNotBefore != null) {
            cborWriteUint(out, 3);
            cborWriteUint(out, nextNotBefore);
        }

        // 4 => ending (OPTIONAL)
        if (ending != null) {
            cborWriteUint(out, 4);
            cborWriteUint(out, ending);
        }

        return out.toByteArray();
    }

    /**
     * Serializes a CoAP Response to the transport-independent format
     * defined in Section 4.2.2 of the draft.
     * <p>
     * The format is the concatenation of:
     * <ol>
     *   <li>A single byte: the Code field value</li>
     *   <li>The byte serialization of the complete sequence of CoAP options</li>
     *   <li>If the message has a non-zero length payload: 0xFF followed by the payload</li>
     * </ol>
     * 
     * @param response the CoAP response to serialize
     * @return the transport-independent byte serialization
     */
    public static byte[] serializeTransportIndependent(Response response) {
        DatagramWriter writer = new DatagramWriter();
        writer.writeByte((byte) response.getRawCode());
        DataSerializer.serializeOptionsAndPayload(writer, response.getOptions(), response.getPayload());
        return writer.toByteArray();
    }

    /**
     * Parse ObservationInfo from CBOR bytes.
     * 
     * @param cborBytes the CBOR encoded bytes
     * @return parsed ObservationInfo
     * @throws IllegalArgumentException if the CBOR structure is invalid
     */
    public static ObservationInfo fromCbor(byte[] cborBytes) {
        if (cborBytes == null) {
            throw new IllegalArgumentException("cborBytes must not be null");
        }
        CborReader reader = new CborReader(cborBytes);
        int mapSize = reader.readMapHeader();

        ObservationInfo info = new ObservationInfo();
        boolean hasTpInfo = false;

        for (int i = 0; i < mapSize; i++) {
            int key = (int) reader.readUint();
            switch (key) {
                case 0: // tp_info
                    info.setTpInfo(TpInfo.readCbor(reader));
                    hasTpInfo = true;
                    break;
                case 1: // ph_req
                    info.setPhReq(reader.readBstr());
                    break;
                case 2: // last_notif
                    info.setLastNotifBytes(reader.readBstr());
                    break;
                case 3: // next_not_before
                    info.setNextNotBefore(reader.readUint());
                    break;
                case 4: // ending
                    info.setEnding(reader.readUint());
                    break;
                default:
                    reader.skipItem(); // ignore unknown keys
                    break;
            }
        }

        if (!hasTpInfo) {
            throw new IllegalArgumentException("Missing required key 0 (tp_info)");
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
        if (lastNotifBytes != null) {
            sb.append("  lastNotif=").append(lastNotifBytes.length).append(" bytes,\n");
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
         * Writes tp_info as CBOR array: [ tpi_server, tpi_client, tpi_token ]
         */
        void writeCbor(ByteArrayOutputStream out) {
            cborWriteArrayHeader(out, 3);
            if (tpiServer != null) tpiServer.writeCbor(out);
            if (tpiClient != null) tpiClient.writeCbor(out);
            if (token != null) cborWriteBstr(out, token.getBytes());
        }

        static TpInfo readCbor(CborReader reader) {
            int arraySize = reader.readArrayHeader();
            if (arraySize < 3) {
                throw new IllegalArgumentException("tp_info must have at least 3 elements for CoAP/UDP");
            }
            TpInfo tp = new TpInfo();
            tp.setTpiServer(Cri.readCbor(reader));
            tp.setTpiClient(Cri.readCbor(reader));
            tp.setToken(Token.fromProvider(reader.readBstr()));
            // skip any extra elements
            for (int i = 3; i < arraySize; i++) reader.skipItem();
            return tp;
        }

        @Override
        public String toString() {
            return "TpInfo{server=" + tpiServer + ", client=" + tpiClient + ", token=" + token + "}";
        }
    }

    public static class Cri {
        public static final int SCHEME_COAP = -1;
        public static final int SCHEME_COAPS = -2;

        private int schemeId;
        private InetAddress host;
        private int port;

        public Cri() { this.schemeId = SCHEME_COAP; }

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

        public byte[] getHostBytes() {
            return host != null ? host.getAddress() : null;
        }

        public boolean isDefaultPort() {
            return port == CoAP.DEFAULT_COAP_PORT;
        }

        public InetSocketAddress toSocketAddress() {
            return new InetSocketAddress(host, port);
        }

        /**
         * Writes CRI as CBOR array: [ scheme, host-ip, port? ]
         * <p>
         * scheme: negative int (-1 coap, -2 coaps), host-ip: bstr, port: uint (omitted if default)
         */
        void writeCbor(ByteArrayOutputStream out) {
            int elements = isDefaultPort() ? 2 : 3;
            cborWriteArrayHeader(out, elements);
            cborWriteNegInt(out, schemeId);
            if (host != null) {
                cborWriteBstr(out, host.getAddress());
            }
            if (!isDefaultPort()) {
                cborWriteUint(out, port);
            }
        }

        static Cri readCbor(CborReader reader) {
            int arraySize = reader.readArrayHeader();
            if (arraySize < 2) {
                throw new IllegalArgumentException("CRI must have at least [scheme, host]");
            }
            Cri cri = new Cri();
            cri.setSchemeId((int) reader.readInt());
            byte[] hostBytes = reader.readBstr();
            try {
                cri.setHost(InetAddress.getByAddress(hostBytes));
            } catch (UnknownHostException e) {
                throw new IllegalArgumentException("Invalid CRI host byte length: " + hostBytes.length, e);
            }
            if (arraySize >= 3) {
                cri.setPort((int) reader.readUint());
            } else {
                cri.setPort(cri.getSchemeId() == SCHEME_COAPS
                        ? CoAP.DEFAULT_COAP_SECURE_PORT : CoAP.DEFAULT_COAP_PORT);
            }
            // skip any extra elements
            for (int i = 3; i < arraySize; i++) reader.skipItem();
            return cri;
        }

        @Override
        public String toString() {
            String scheme = (schemeId == SCHEME_COAP) ? "coap"
                    : (schemeId == SCHEME_COAPS) ? "coaps" : String.valueOf(schemeId);
            return scheme + "://" + (host != null ? host.getHostAddress() : "null") + ":" + port;
        }
    }


    /** Writes a CBOR head byte (major type + argument). */
    private static void cborWriteHead(ByteArrayOutputStream out, int majorType, long value) {
        int mt = majorType << 5;
        if (value < 24) {
            out.write(mt | (int) value);
        } else if (value <= 0xFF) {
            out.write(mt | 24);
            out.write((int) value);
        } else if (value <= 0xFFFF) {
            out.write(mt | 25);
            out.write((int) (value >> 8) & 0xFF);
            out.write((int) value & 0xFF);
        } else if (value <= 0xFFFFFFFFL) {
            out.write(mt | 26);
            out.write((int) (value >> 24) & 0xFF);
            out.write((int) (value >> 16) & 0xFF);
            out.write((int) (value >> 8) & 0xFF);
            out.write((int) value & 0xFF);
        } else {
            out.write(mt | 27);
            for (int i = 7; i >= 0; i--) {
                out.write((int) (value >> (i * 8)) & 0xFF);
            }
        }
    }

    /** Writes a CBOR unsigned integer (major type 0). */
    static void cborWriteUint(ByteArrayOutputStream out, long value) {
        cborWriteHead(out, 0, value);
    }

    /** Writes a CBOR negative integer (major type 1). Value must be negative (e.g. -1, -2). */
    static void cborWriteNegInt(ByteArrayOutputStream out, long value) {
        cborWriteHead(out, 1, -1 - value);
    }

    /** Writes a CBOR byte string (major type 2). */
    static void cborWriteBstr(ByteArrayOutputStream out, byte[] bytes) {
        cborWriteHead(out, 2, bytes.length);
        out.write(bytes, 0, bytes.length);
    }

    /** Writes a CBOR array header (major type 4). */
    static void cborWriteArrayHeader(ByteArrayOutputStream out, int length) {
        cborWriteHead(out, 4, length);
    }

    /** Writes a CBOR map header (major type 5). */
    static void cborWriteMapHeader(ByteArrayOutputStream out, int length) {
        cborWriteHead(out, 5, length);
    }

    // ---- Lightweight CBOR decoding helper ----

    /**
     * Minimal CBOR reader for the data types used in this class.
     * Supports unsigned int, negative int, byte strings, arrays, and maps.
     */
    static class CborReader {
        private final byte[] data;
        private int pos;

        CborReader(byte[] data) {
            this.data = data;
            this.pos = 0;
        }

        private int nextByte() {
            return data[pos++] & 0xFF;
        }

        private long readArgument(int additionalInfo) {
            if (additionalInfo < 24) return additionalInfo;
            if (additionalInfo == 24) return nextByte();
            if (additionalInfo == 25) return (nextByte() << 8) | nextByte();
            if (additionalInfo == 26) return ((long) nextByte() << 24) | (nextByte() << 16)
                    | (nextByte() << 8) | nextByte();
            if (additionalInfo == 27) {
                long val = 0;
                for (int i = 0; i < 8; i++) val = (val << 8) | nextByte();
                return val;
            }
            throw new IllegalArgumentException("Unsupported CBOR additional info: " + additionalInfo);
        }

        /** Reads an unsigned integer (major type 0). */
        long readUint() {
            int b = nextByte();
            int mt = b >> 5;
            if (mt != 0) throw new IllegalArgumentException("Expected CBOR uint, got major type " + mt);
            return readArgument(b & 0x1F);
        }

        /** Reads a signed integer: unsigned (type 0) or negative (type 1). */
        long readInt() {
            int b = nextByte();
            int mt = b >> 5;
            long arg = readArgument(b & 0x1F);
            if (mt == 0) return arg;
            if (mt == 1) return -1 - arg;
            throw new IllegalArgumentException("Expected CBOR integer, got major type " + mt);
        }

        /** Reads a byte string (major type 2). */
        byte[] readBstr() {
            int b = nextByte();
            int mt = b >> 5;
            if (mt != 2) throw new IllegalArgumentException("Expected CBOR bstr, got major type " + mt);
            int len = (int) readArgument(b & 0x1F);
            byte[] result = new byte[len];
            System.arraycopy(data, pos, result, 0, len);
            pos += len;
            return result;
        }

        /** Reads an array header and returns the array length. */
        int readArrayHeader() {
            int b = nextByte();
            int mt = b >> 5;
            if (mt != 4) throw new IllegalArgumentException("Expected CBOR array, got major type " + mt);
            return (int) readArgument(b & 0x1F);
        }

        /** Reads a map header and returns the number of key-value pairs. */
        int readMapHeader() {
            int b = nextByte();
            int mt = b >> 5;
            if (mt != 5) throw new IllegalArgumentException("Expected CBOR map, got major type " + mt);
            return (int) readArgument(b & 0x1F);
        }

        /** Skips one complete CBOR data item. */
        void skipItem() {
            int b = nextByte();
            int mt = b >> 5;
            long arg = readArgument(b & 0x1F);
            switch (mt) {
                case 0: case 1: break;                          // integer
                case 2: case 3: pos += (int) arg; break;        // byte/text string
                case 4: for (int i = 0; i < arg; i++) skipItem(); break;          // array
                case 5: for (int i = 0; i < arg; i++) { skipItem(); skipItem(); } break; // map
                case 6: skipItem(); break;                       // tag — skip content
                case 7: break;                                   // simple / float
            }
        }
    }
}