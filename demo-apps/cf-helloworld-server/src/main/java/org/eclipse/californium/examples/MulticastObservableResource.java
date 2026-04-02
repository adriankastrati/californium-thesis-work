/*******************************************************************************
 * Copyright (c) 2015, 2017 Institute for Pervasive Computing, ETH Zurich and others.
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
 *    Martin Lanter - architect and re-implementation
 *    Dominique Im Obersteg - parsers and initial implementation
 *    Daniel Pauli - parsers and initial implementation
 *    Kai Hudalla - logging
 *    Achim Kraus (Bosch Software Innovations GmbH) - add nextObserveNumber
 *                                                    (for use by subclasses)
 *    Achim Kraus (Bosch Software Innovations GmbH) - replace nextObserveNumber
 *                                                    by ObserveRelationFilter
 *                                                    (for use by subclasses)
 *    Kai Hudalla (Bosch Software Innovations GmbH) - use Logger's message formatting instead of
 *                                                    explicit String concatenation
 *    Bosch Software Innovations GmbH - migrate to SLF4J
 *    Achim Kraus (Bosch Software Innovations GmbH) - don't add canceled
 *                                                    observation-relations again.
 *    Achim Kraus (Bosch Software Innovations GmbH) - add iPATCH
 *                                                    cleanup source according
 *                                                    coding guidelines
 ******************************************************************************/
package org.eclipse.californium.examples;

import java.net.InetSocketAddress;
import java.util.List;
import java.util.Timer;
import java.util.TimerTask;

import org.eclipse.californium.core.CoapExchange;
import org.eclipse.californium.core.CoapResource;
import org.eclipse.californium.core.coap.CoAP.ResponseCode;
import org.eclipse.californium.core.coap.CoAP.Type;
import org.eclipse.californium.core.coap.MediaTypeRegistry;
import org.eclipse.californium.core.coap.Request;
import org.eclipse.californium.core.coap.Response;
import org.eclipse.californium.core.coap.Token;
import org.eclipse.californium.core.network.CoapEndpoint;
import org.eclipse.californium.core.network.Exchange;
import org.eclipse.californium.core.network.Exchange.Origin;
import org.eclipse.californium.core.observe.GroupObservationsInfo;
import org.eclipse.californium.core.observe.ObservationInfo;
import org.eclipse.californium.core.observe.ObserveRelation;
import org.eclipse.californium.core.server.MessageDeliverer;
import org.eclipse.californium.core.server.resources.Resource;
import org.eclipse.californium.elements.AddressEndpointContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A CoapResource that supports multicast group observation (RFC 7641 + RFC 7390).
 * <p>
 * When enough observers register, this resource sets up a group observation by
 * creating a phantom request/exchange. Subsequent clients receive an informative
 * response (5.03) directing them to the multicast group.
 */
public class MulticastObservableResource extends CoapResource {

	private static final Logger LOGGER = LoggerFactory.getLogger(MulticastObservableResource.class);

	private volatile int content = 1;
	private volatile boolean sentFirstNotifications = false;

	private final boolean isGroupObservable;
	private final MessageDeliverer serverMessageDeliverer;
	private final Timer timer = new Timer();

	public MulticastObservableResource(String uri, boolean groupObservable, MessageDeliverer serverMessageDeliverer) {
		super(uri);
		this.serverMessageDeliverer = serverMessageDeliverer;
		getAttributes().setTitle("Multicast Observable Resource");
		setObservable(true);
		setObserveType(null);
		this.isGroupObservable = groupObservable;
		LOGGER.info("MulticastObservableResource created - URI: {}, Path: {}, Observable: {}",
				uri, this.getPath(), isObservable());
	}

	@Override
	public void handleGET(CoapExchange exchange) {
		GroupObservationsInfo groupObservationsInfo = GroupObservationsInfo.getInstance();
		String uriPath = this.getURI();

		if (exchange.getRequestOptions().hasObserve() && exchange.getRequestOptions().getObserve() == 0) {
			if (!sentFirstNotifications) {
				timer.schedule(new TimerTask() {
					@Override
					public void run() {
						content++;
						changed();
					}
				}, 0, 15000);
				sentFirstNotifications = true;
			}

			if (exchange.advanced().isPhantomRequest()) {
				handlePhantomRequest(exchange);
			} else if (groupObservationsInfo.isOngoingGroupObservation(uriPath)) {
				// Group observation already active, send informative response
				ObservationInfo obsInfo = groupObservationsInfo.getGroupObservationInfo(uriPath);
				sendInformativeResponse(exchange, obsInfo);
			} else if (shouldGroupObservationStart()) {
				groupObservationsInfo.addPendingClient(this.getURI(), exchange);
				setUpGroupObservation(exchange);
			}
		} else {
			exchange.respond(ResponseCode.CONTENT, Integer.toString(content));
		}
	}

	/**
	 * Sends an informative response (5.03 Service Unavailable) with tp_info
	 * to redirect a client to the multicast group observation.
	 */
	private void sendInformativeResponse(CoapExchange exchange, ObservationInfo obsInfo) {
		Response response = new Response(ResponseCode.SERVICE_UNAVAILABLE);
		response.getOptions().setContentFormat(MediaTypeRegistry.APPLICATION_INFORMATIVE_RESPONSE_CBOR);
		response.setConfirmable(true);
		byte[] payload = obsInfo.toCbor();
		response.setPayload(payload);

		LOGGER.debug("Sending informative response (5.03) to client {} with token {}",
				exchange.getSourceSocketAddress(), exchange.advanced().getRequest().getToken());

		exchange.respond(response);
	}

	/**
	 * Sends informative responses to all pending clients waiting for
	 * group observation setup to complete.
	 */
	private void sendInformativeResponsesToClients(String uriPath, ObservationInfo obsInfo) {
		GroupObservationsInfo groupInfo = GroupObservationsInfo.getInstance();
		List<CoapExchange> pendingClients = groupInfo.removePendingClients(uriPath);

		LOGGER.info("Sending informative responses to {} pending clients for resource {}",
				pendingClients.size(), uriPath);

		for (CoapExchange clientExchange : pendingClients) {
			Response response = new Response(ResponseCode.SERVICE_UNAVAILABLE);
			response.getOptions().setContentFormat(MediaTypeRegistry.APPLICATION_INFORMATIVE_RESPONSE_CBOR);

			byte[] payload = groupInfo.getGroupObservationInfo(uriPath).toCbor();
			response.setPayload(payload);

			response.setDestinationContext(clientExchange.advanced().getRequest().getSourceContext());
			response.setToken(clientExchange.advanced().getRequest().getToken());

			LOGGER.info("Sending informative response (5.03) to pending client {} with token {}",
					clientExchange.advanced().getRequest().getSourceContext().getPeerAddress(),
					clientExchange.advanced().getRequest().getToken());

			clientExchange.respond(response);
		}
	}

	@Override
	public void changed() {
		LOGGER.info("Observer count at change of resource {}", this.getObserverCount());
		super.changed();
	}

	@Override
	public void removeObserveRelation(ObserveRelation relation) {
		super.removeObserveRelation(relation);
		LOGGER.info("Observe relation removed by client");
	}

	@Override
	public void handlePUT(CoapExchange exchange) {
		changed();
	}

	public boolean isGroupObservable() {
		return isGroupObservable;
	}

	/**
	 * Handles a phantom request that establishes or uses the group observation.
	 * <p>
	 * If the token matches the pending multicast token, this is the initial
	 * phantom establishing the group observation. Otherwise, it is a
	 * notification for an already-established phantom exchange.
	 */
	private void handlePhantomRequest(CoapExchange exchange) {
		String uriPath = this.getURI();
		GroupObservationsInfo groupObservationsInfo = GroupObservationsInfo.getInstance();
		Token exchangeToken = exchange.advanced().getRequest().getToken();

		Token pendingToken = groupObservationsInfo.getPendingToken(uriPath);

		if (pendingToken != null && pendingToken.equals(exchangeToken)) {
			// Suppress the response -- it establishes observation but is not sent on wire
			exchange.advanced().setSuppressResponse(true);

			// Create ObservationInfo for the group observation
			InetSocketAddress localAddress = exchange.advanced().getEndpoint().getAddress();
			InetSocketAddress multicastAddress = groupObservationsInfo.getMulticastAddress();
			ObservationInfo observationInfo = new ObservationInfo(localAddress, multicastAddress, exchangeToken);

			// Start the group observation (removes token from pending map)
			groupObservationsInfo.startGroupObservation(uriPath, observationInfo);
			// Attach the phantom request for future reference
			groupObservationsInfo.getGroupObservationInfo(uriPath).setPhReq(exchange.advanced().getProtectedRequest());

			// Clear setup-in-progress flag
			groupObservationsInfo.setGroupObservationSetupInProgress(uriPath, false);

			// Respond to establish the observe relation internally (response is suppressed)
			exchange.respond(ResponseCode.CONTENT, Integer.toString(this.content));

			// Send informative responses to all pending clients
			sendInformativeResponsesToClients(uriPath, observationInfo);
		} else {
			// Notification for an established phantom exchange -- send to multicast address
			LOGGER.info("Sending multicast notification for {} with content: {}", uriPath, this.content);
			exchange.respond(ResponseCode.CONTENT, Integer.toString(this.content));
		}
	}

	private boolean shouldGroupObservationStart() {
		return this.getObserverCount() >= 0;
	}

	/**
	 * Creates a phantom request and exchange for multicast group observation.
	 * <p>
	 * The phantom request is a self-generated observe request whose source context
	 * is set to the server's own address. ObserveLayer detects it as a phantom
	 * and redirects responses to the multicast group address.
	 *
	 * @param resource the resource to observe
	 * @param multicastToken the token allocated for multicast notifications
	 * @param triggeringExchange the original client exchange that triggered setup
	 * @return the phantom exchange ready to be delivered
	 */
	protected Exchange createPhantomExchange(Resource resource, Token multicastToken, Exchange triggeringExchange) {
		Request phantomRequest = Request.newGet();
		phantomRequest.setToken(multicastToken);
		phantomRequest.setObserve();
		phantomRequest.getOptions().setUriPath(resource.getURI());
		phantomRequest.setType(Type.NON);

		InetSocketAddress localAddress = triggeringExchange.getEndpoint().getAddress();

		// Source is set to server's own address so ObserveLayer can detect phantom requests
		phantomRequest.setSourceContext(new AddressEndpointContext(localAddress));
		LOGGER.debug("Phantom request source set to server address: {}", localAddress);

		Exchange phantomExchange = new Exchange(phantomRequest, localAddress, Origin.REMOTE,
				triggeringExchange.getEndpoint().getExecutor());

		if (triggeringExchange.getEndpoint() != null) {
			phantomExchange.setEndpoint(triggeringExchange.getEndpoint());
		}

		LOGGER.debug("Created phantom exchange for resource {} with multicast token {}",
				resource.getURI(), multicastToken);

		return phantomExchange;
	}

	/**
	 * Initiates group observation setup by allocating a multicast token
	 * and injecting a phantom request into the CoAP stack.
	 */
	private void setUpGroupObservation(CoapExchange exchange) {
		String resourceUri = this.getURI();
		GroupObservationsInfo groupObservationsInfo = GroupObservationsInfo.getInstance();

		LOGGER.debug("Group observation for {} starting", resourceUri);

		synchronized (groupObservationsInfo) {
			if (groupObservationsInfo.isGroupObservationSetupInProgress(resourceUri)) {
				if (exchange.advanced().isPhantomRequest()) {
					LOGGER.debug("Phantom request for {} - allowing delivery during setup", resourceUri);
				} else {
					groupObservationsInfo.addPendingClient(resourceUri, exchange);
					LOGGER.debug("Group observation setup in progress for {}, added client as pending", resourceUri);
					return;
				}
			} else {
				groupObservationsInfo.setGroupObservationSetupInProgress(resourceUri, true);
			}
		}

		if (exchange.advanced().isPhantomRequest()) {
			return;
		}

		// Allocate multicast token
		Token multicastToken = groupObservationsInfo.allocateMulticastToken(resourceUri);
		LOGGER.debug("Allocated multicast token {} for group observation on {}", multicastToken, resourceUri);

		// Create and send phantom request through the CoAP stack
		final Request phantomRequest = groupObservationsInfo.createPhantomRequest(
				this, multicastToken, exchange, false);

		LOGGER.debug("Injecting phantom request into stack for {} with token {}", resourceUri, multicastToken);
		CoapEndpoint endpoint = (CoapEndpoint) exchange.advanced().getEndpoint();
		endpoint.sendRequest(phantomRequest);

		LOGGER.debug("Phantom request injected for {} with token {}", resourceUri, multicastToken);
	}
}
