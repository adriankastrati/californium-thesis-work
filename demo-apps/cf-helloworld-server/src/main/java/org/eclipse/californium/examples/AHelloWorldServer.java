/*******************************************************************************
 * Copyright (c) 2015 Institute for Pervasive Computing, ETH Zurich and others.
 * 
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v2.0
 * and Eclipse Distribution License v1.0 which accompany this distribution.
 * 
 * The Eclipse Public License is available at
 *    http://www.eclipse.org/legal/epl-v20.html
 * and the Eclipse Distribution License is available at
 *    http://www.eclipse.org/org/documents/edl-v10.html.
 * 
 * Contributors:
 *    Matthias Kovatsch - creator and main architect
 *    Kai Hudalla (Bosch Software Innovations GmbH) - add endpoints for all IP addresses
 *    Achim Kraus (Bosch Software Innovations GmbH) - add TCP parameter
 ******************************************************************************/
package org.eclipse.californium.examples;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketException;

import org.eclipse.californium.core.observe.GroupObservationsInfo;
import org.eclipse.californium.core.observe.ObserveRelation;
import org.eclipse.californium.core.CoapExchange;
import org.eclipse.californium.core.CoapResource;
import org.eclipse.californium.core.CoapServer;
import org.eclipse.californium.core.coap.CoAP.ResponseCode;
import org.eclipse.californium.core.config.CoapConfig;
import org.eclipse.californium.core.network.CoapEndpoint;
import org.eclipse.californium.core.server.resources.MyIpResource;
import org.eclipse.californium.elements.config.Configuration;
import org.eclipse.californium.elements.config.TcpConfig;
import org.eclipse.californium.elements.config.UdpConfig;
import org.eclipse.californium.elements.util.NetworkInterfacesUtil;

import java.net.Inet4Address;


public class AHelloWorldServer extends CoapServer {

	static {
		CoapConfig.register();
		UdpConfig.register();
		TcpConfig.register();
	}

  
	/*
	 * Application entry point.
	 */
	public static void main(String[] args) {
		try {	
			AHelloWorldServer server = new AHelloWorldServer();

			server.addEndpoint();
			server.start();

		} catch (SocketException e) {
			System.err.println("Failed to init: " + e.getMessage());
		}
	}

	/**
	 * Add individual endpoint listening on default CoAP to adress
	 */
	private void addEndpoint() {
		int port = Configuration.getStandard().get(CoapConfig.COAP_PORT);
		Configuration config = Configuration.getStandard();

		// Loopback endpoint for local unicast
		InetAddress loopbackAddr = InetAddress.getLoopbackAddress();
		InetSocketAddress loopbackSocket = new InetSocketAddress(loopbackAddr, port);
		CoapEndpoint.Builder loopbackBuilder = new CoapEndpoint.Builder();
		loopbackBuilder.setInetSocketAddress(loopbackSocket);
		loopbackBuilder.setConfiguration(config);
		addEndpoint(loopbackBuilder.build());

		// Physical interface endpoint for multicast to work
		Inet4Address ipv4 = NetworkInterfacesUtil.getMulticastInterfaceIpv4();
		if (ipv4 != null) {
			InetSocketAddress physicalSocket = new InetSocketAddress(ipv4, port);
			CoapEndpoint.Builder physicalBuilder = new CoapEndpoint.Builder();
			physicalBuilder.setInetSocketAddress(physicalSocket);
			physicalBuilder.setConfiguration(config);
			addEndpoint(physicalBuilder.build());
			System.out.println("Added physical interface endpoint: " + physicalSocket);
		}
	}

	/*
	 * Constructor for a new Hello-World server. Here, the resources of the
	 * server are initialized.
	 */
	public AHelloWorldServer() throws SocketException { 
   
    // Add get and obs as children of pasta
    GroupObservationsInfo.init();
    add(new HelloWorldResource());
    add(new ObservableResource());
    add(new MulticastObservableResource("mult", true, this.getMessageDeliverer()));
    
}

	/*
	 * Definition of the Hello-World Resource
	 */
	static class HelloWorldResource extends CoapResource {
		private String content;
		
		public HelloWorldResource() {

			// set resource identifier
			super("get");
			
			// set display name
			getAttributes().setTitle("pasta Resource");
			this.content = "spaghetti";
			System.out.println(this.getPath());
		}

		@Override
		public void handleGET(CoapExchange exchange) {

			// respond to the request
			exchange.respond(content);
		}
		
		@Override
		public void handlePOST(CoapExchange exchange) {
			String requestText = exchange.getRequestText();
			String old = this.content;
			this.content = requestText;
			String response_payload = old + " ->" + content;
			exchange.respond(ResponseCode.CHANGED, response_payload);
			changed();
			
		}
	}
	
	static class ObservableResource extends CoapResource {

		private volatile String content;
		private final String originalContent;
		
		public ObservableResource() {

			// set resource identifier
			super("obs");
			// set display name
			getAttributes().setTitle("pub-sub Resource");
			setObservable(true);
			setObserveType(null);
			System.out.println(this.getPath());

		
			this.content = "1";
			this.originalContent = this.content;
		}

		
		@Override
		public void handleGET(CoapExchange exchange) {

			// respond to the request
			exchange.respond(ResponseCode.CONTENT, this.content);
		}
		
		@Override
		public void changed() {
			super.changed();
		}
		 
		@Override
		    public void removeObserveRelation(ObserveRelation relation) {
		        super.removeObserveRelation(relation);
		        System.out.println("Observe relation removed by client");
		 }
		 
		@Override
		public void handlePUT(CoapExchange exchange) {
			String requestText = exchange.getRequestText();
			String old = this.content;
			
			this.content = requestText;
			String response_payload = old + " -> " + this.content;
			
			exchange.respond(ResponseCode.CHANGED, response_payload);
			
			changed();
			
		}
		
	}
}
