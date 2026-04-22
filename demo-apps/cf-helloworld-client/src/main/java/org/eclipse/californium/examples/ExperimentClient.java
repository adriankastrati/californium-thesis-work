package org.eclipse.californium.examples;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Experiment client for Raspberry Pi multicast observe testing.
 * Run on Pis at 192.168.0.33, 192.168.0.44, 192.168.0.66.
 *
 * Usage: java ExperimentClient [serverIP]
 * Default server: 192.168.0.55
 */
public class ExperimentClient {

	private static final Logger LOGGER = LoggerFactory.getLogger(ExperimentClient.class);
	private static final String DEFAULT_SERVER_IP = "192.168.0.55";
	private static final int COAP_PORT = 5683;

	public static void main(String[] args) {
		String serverIP = args.length > 0 ? args[0] : DEFAULT_SERVER_IP;
		String uri = "coap://" + serverIP + ":" + COAP_PORT + "/mult";

		LOGGER.info("Starting multicast observe client targeting {}", uri);
		MulticastObserveClient.main(uri, 0);
	}
}
