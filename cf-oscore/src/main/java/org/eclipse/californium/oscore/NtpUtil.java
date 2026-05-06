package org.eclipse.californium.oscore;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class NtpUtil {

	private static final Logger LOGGER = LoggerFactory.getLogger(NtpUtil.class);
	private static final String NTP_SERVER = "pool.ntp.org";
	private static final int NTP_PORT = 123;
	private static final int NTP_PACKET_SIZE = 48;
	private static final long NTP_EPOCH_OFFSET = 2208988800L;

	private static long offsetMs = 0;
	private static boolean initialized = false;

	public static synchronized void initialize() {
		try (DatagramSocket socket = new DatagramSocket()) {
			socket.setSoTimeout(5000);

			byte[] buffer = new byte[NTP_PACKET_SIZE];
			// LI=0, Version=3, Mode=3 (client)
			buffer[0] = 0x1B;

			InetAddress address = InetAddress.getByName(NTP_SERVER);
			DatagramPacket request = new DatagramPacket(buffer, buffer.length, address, NTP_PORT);

			long localSendTime = System.currentTimeMillis();
			socket.send(request);

			DatagramPacket response = new DatagramPacket(buffer, buffer.length);
			socket.receive(response);
			long localReceiveTime = System.currentTimeMillis();

			// Extract transmit timestamp (bytes 40-47)
			long seconds = 0;
			for (int i = 40; i <= 43; i++) {
				seconds = (seconds << 8) | (buffer[i] & 0xFF);
			}
			long fraction = 0;
			for (int i = 44; i <= 47; i++) {
				fraction = (fraction << 8) | (buffer[i] & 0xFF);
			}

			long ntpTimeMs = (seconds - NTP_EPOCH_OFFSET) * 1000 + (fraction * 1000 / 0x100000000L);
			long roundTrip = localReceiveTime - localSendTime;
			offsetMs = ntpTimeMs - localReceiveTime + (roundTrip / 2);
			initialized = true;

			LOGGER.info("[NTP] Synchronized with {}. Offset: {} ms, RTT: {} ms", NTP_SERVER, offsetMs, roundTrip);
		} catch (Exception e) {
			LOGGER.error("[NTP] Synchronization failed: {}. Using local clock.", e.getMessage());
			offsetMs = 0;
			initialized = true;
		}
	}

	public static long now() {
		if (!initialized) {
			initialize();
		}
		return System.currentTimeMillis() + offsetMs;
	}
}
