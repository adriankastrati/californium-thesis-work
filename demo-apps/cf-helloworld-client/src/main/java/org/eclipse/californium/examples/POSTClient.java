package org.eclipse.californium.examples;

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;

import org.eclipse.californium.core.CoapClient;
import org.eclipse.californium.core.CoapResponse;
import org.eclipse.californium.core.Utils;
import org.eclipse.californium.core.coap.MediaTypeRegistry;
import org.eclipse.californium.core.config.CoapConfig;
import org.eclipse.californium.elements.config.Configuration;
import org.eclipse.californium.elements.config.Configuration.DefinitionsProvider;
import org.eclipse.californium.elements.config.UdpConfig;
import org.eclipse.californium.elements.exception.ConnectorException;

public class POSTClient {

	private static final File CONFIG_FILE = new File("Californium3.properties");
	private static final String CONFIG_HEADER = "Californium CoAP Properties file for client";
	private static final int DEFAULT_MAX_RESOURCE_SIZE = 2 * 1024 * 1024; // 2 MB
	private static final int DEFAULT_BLOCK_SIZE = 512;

	static {
		CoapConfig.register();
		UdpConfig.register();
	}

	private static DefinitionsProvider DEFAULTS = (config) -> {
		config.set(CoapConfig.MAX_RESOURCE_BODY_SIZE, DEFAULT_MAX_RESOURCE_SIZE);
		config.set(CoapConfig.MAX_MESSAGE_SIZE, DEFAULT_BLOCK_SIZE);
		config.set(CoapConfig.PREFERRED_BLOCK_SIZE, DEFAULT_BLOCK_SIZE);
	};

	/**
	 * Sends a CoAP POST request with the given content to the specified URI.
	 */
	public static void main(String postUri, String content) {
		Configuration config = Configuration.createWithFile(CONFIG_FILE, CONFIG_HEADER, DEFAULTS);
		Configuration.setStandard(config);

		URI uri = null;

		try {
			uri = new URI(postUri);
		} catch (URISyntaxException e) {
			System.err.println("Invalid URI: " + e.getMessage());
			System.exit(-1);
		}

		CoapClient client = new CoapClient(uri);

		try {
			CoapResponse response = client.post(content, MediaTypeRegistry.TEXT_PLAIN);

			if (response != null) {
				System.out.println(response.getCode());
				System.out.println(response.getOptions());
				System.out.println(response.getResponseText());
				System.out.println(Utils.prettyPrint(response));
			} else {
				System.out.println("No response received.");
			}
		} catch (ConnectorException | IOException e) {
			System.err.println("Got an error: " + e);
		}

		client.shutdown();
	}
}
