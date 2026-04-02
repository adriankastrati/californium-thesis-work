package org.eclipse.californium.examples;

import java.util.Timer;
import java.util.TimerTask;

import org.eclipse.californium.TestTools;
import org.eclipse.californium.core.CoapExchange;
import org.eclipse.californium.core.CoapResource;
import org.eclipse.californium.core.CoapServer;
import org.eclipse.californium.core.coap.CoAP.Type;
import org.eclipse.californium.core.network.CoapEndpoint;
import org.eclipse.californium.core.network.Endpoint;
import org.eclipse.californium.cose.AlgorithmID;
import org.eclipse.californium.elements.util.Bytes;
import org.eclipse.californium.oscore.HashMapCtxDB;
import org.eclipse.californium.oscore.OSCoreCtx;
import org.eclipse.californium.oscore.OSCoreResource;
import org.eclipse.californium.oscore.OSException;

/**
 * Test server with OSCORE-protected observable resource at /oscore/observe2.
 */
public class OscoreServerTest {

	// OSCORE context parameters (must match client configuration)
	private final static HashMapCtxDB dbServer = new HashMapCtxDB();
	private final static AlgorithmID alg = AlgorithmID.AES_CCM_16_64_128;
	private final static AlgorithmID kdf = AlgorithmID.HKDF_HMAC_SHA_256;
	private final static byte[] master_secret = { 0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07, 0x08, 0x09, 0x0A, 0x0B,
			0x0C, 0x0D, 0x0E, 0x0F, 0x10 };
	private final static byte[] master_salt = { (byte) 0x9e, (byte) 0x7c, (byte) 0xa9, (byte) 0x22, (byte) 0x23,
			(byte) 0x78, (byte) 0x63, (byte) 0x40 };
	private final static int MAX_UNFRAGMENTED_SIZE = 4096;

	private Endpoint serverEndpoint;
	private CoapServer server;
	private Timer timer;

	/**
	 * Sets up the OSCORE context for the server.
	 * Server sender ID = 0x01, recipient ID = empty (matches client's sender ID).
	 */
	private void setServerContext() {
		byte[] sid = new byte[] { 0x01 };
		byte[] rid = Bytes.EMPTY;

		try {
			OSCoreCtx ctx = new OSCoreCtx(master_secret, false, alg, sid, rid, kdf, 32, master_salt, null,
					MAX_UNFRAGMENTED_SIZE);
			dbServer.addContext(ctx);
		} catch (OSException e) {
			System.err.println("Failed to set server OSCORE context: " + e.getMessage());
		}
	}

	/**
	 * Starts the server with the OSCORE observable resource at /oscore/observe2.
	 */
	public void start() throws InterruptedException {
		setServerContext();

		CoapEndpoint.Builder builder = new CoapEndpoint.Builder();
		builder.setCustomCoapStackArgument(dbServer);
		builder.setInetSocketAddress(TestTools.LOCALHOST_EPHEMERAL);
		serverEndpoint = builder.build();

		server = new CoapServer();
		server.addEndpoint(serverEndpoint);

		// Build resource hierarchy: /oscore/observe2
		OSCoreResource oscore = new OSCoreResource("oscore", true);
		OSCoreResource oscoreHello = new OSCoreResource("hello", true);

		timer = new Timer();
		ObserveResource oscoreObserve2 = new ObserveResource("observe2", true);

		oscore.add(oscoreHello);
		oscore.add(oscoreObserve2);
		server.add(oscore);

		server.start();
		System.out.println("Server started at: " + serverEndpoint.getAddress());
	}

	/**
	 * Returns the server endpoint for client URI resolution.
	 */
	public Endpoint getServerEndpoint() {
		return serverEndpoint;
	}

	/**
	 * Stops the server and cancels the update timer.
	 */
	public void stop() {
		if (timer != null) {
			timer.cancel();
		}
		if (server != null) {
			server.stop();
		}
	}

	/**
	 * Observable resource that responds with "one" initially,
	 * then updates to "two" after the first GET request is received.
	 * Timer fires every 750ms to trigger observer notifications.
	 */
	class ObserveResource extends CoapResource {

		public String value = "one";
		private boolean firstRequestReceived = false;

		public ObserveResource(String name, boolean visible) {
			super(name, visible);

			this.setObservable(true);
			this.setObserveType(Type.NON);
			this.getAttributes().setObservable();

			timer.schedule(new UpdateTask(), 0, 750);
		}

		@Override
		public void handleGET(CoapExchange exchange) {
			firstRequestReceived = true;
			exchange.respond(value);
		}

		class UpdateTask extends TimerTask {
			@Override
			public void run() {
				if (firstRequestReceived) {
					value = "two";
					changed();
				}
			}
		}
	}

	public static void main(String[] args) throws InterruptedException {
		OscoreServerTest server = new OscoreServerTest();
		server.start();
		Thread.currentThread().join();
	}
}
