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
import org.eclipse.californium.core.coap.CoAP.ResponseCode;
import org.eclipse.californium.core.coap.CoAP.Type;
import org.eclipse.californium.core.coap.MediaTypeRegistry;
import org.eclipse.californium.core.coap.Request;
import org.eclipse.californium.core.coap.Response;
import org.eclipse.californium.core.coap.Token;
import org.eclipse.californium.core.network.CoapEndpoint;
import org.eclipse.californium.core.network.Exchange;
import org.eclipse.californium.core.observe.GroupObservationsInfo;
import org.eclipse.californium.core.observe.ObservationInfo;
import org.eclipse.californium.core.observe.ObserveRelation;
import org.eclipse.californium.core.server.MessageDeliverer;
import org.eclipse.californium.elements.util.Bytes;
import org.eclipse.californium.oscore.OSCoreResource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class OSCOREMulticastObservableResource extends OSCoreResource {

	private static final Logger LOGGER = LoggerFactory.getLogger(OSCOREMulticastObservableResource.class);
	private static final int CANCEL_AFTER_NOTIFICATIONS = 5;

	private volatile int content = 1;
	private Timer timer = new Timer();
	private volatile boolean cancelling = false;

	private final boolean isGroupObservable;
	private final MessageDeliverer serverMessageDeliverer;

	public OSCOREMulticastObservableResource(String uri, boolean groupObservable, MessageDeliverer serverMessageDeliverer) {
		super(uri, true);
		this.serverMessageDeliverer = serverMessageDeliverer;
		getAttributes().setTitle("Multicast Observable Resource");
		setObservable(true);
		setObserveType(null);
		this.isGroupObservable = groupObservable;
		timer.schedule(new UpdateTask(), 0, 10000);
	}


	@Override
	public void handleGET(CoapExchange exchange) {
		GroupObservationsInfo groupObservationsInfo = GroupObservationsInfo.getInstance();
		String uriPath = this.getURI();

		if (exchange.getRequestOptions().hasObserve() && exchange.getRequestOptions().getObserve() == 0) {
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
			// set new last_notif
			exchange.respond(ResponseCode.CONTENT, Integer.toString(this.content));
		}
	}
  
	/**
	 * Send informative response (5.03 Service Unavailable) with tp_info to a client.
	 * Per draft-ietf-core-observe-multicast-notifications Section 4.2.
	 */
	private void sendInformativeResponse(CoapExchange clientExchange, ObservationInfo obsInfo) {
		clientExchange.accept();

		Response response = new Response(ResponseCode.SERVICE_UNAVAILABLE);
		response.setType(Type.CON);
		response.getOptions().setContentFormat(MediaTypeRegistry.APPLICATION_INFORMATIVE_RESPONSE_CBOR);
		response.setPayload(obsInfo.toCbor());
		response.setDestinationContext(clientExchange.advanced().getRequest().getSourceContext());
		response.setToken(clientExchange.advanced().getRequest().getToken());
		LOGGER.debug("inforamtive response payload{}", obsInfo.toString());
		LOGGER.debug("Sending informative response (5.03) to client {} with token {}",
				clientExchange.advanced().getRequest().getSourceContext().getPeerAddress(),
				clientExchange.advanced().getRequest().getToken());

		clientExchange.advanced().getRequest().getOptions().removeObserve();
		clientExchange.respond(response);
	}
  
	/**
	 * Send informative responses to all pending clients waiting for group observation setup.
	 */
	private void sendInformativeResponsesToClients(String uriPath) {
		GroupObservationsInfo groupInfo = GroupObservationsInfo.getInstance();
		List<CoapExchange> pendingClients = groupInfo.removePendingClients(uriPath);

		LOGGER.debug("Sending informative responses to {} pending clients for {}", pendingClients.size(), uriPath);

		for (CoapExchange clientExchange : pendingClients) {
			sendInformativeResponse(clientExchange, groupInfo.getGroupObservationInfo(uriPath));
		}
	}
  
	@Override
	public void changed() {
		LOGGER.debug("Observer count at change: {}", this.getObserverCount());
		super.changed();
	}

	@Override
	public void removeObserveRelation(ObserveRelation relation) {
		super.removeObserveRelation(relation);
	}

	public boolean isGroupObservable() {
		return isGroupObservable;
	}

	/**
	 * Cancel the group observation per draft Section 4.5.
	 * Sends a multicast 5.03 with Token T (no payload, no Observe option),
	 * then cleans up all group observation state.
	 */
	public void cancelGroupObservation() {
		String uriPath = this.getURI();
		GroupObservationsInfo groupInfo = GroupObservationsInfo.getInstance();

		if (!groupInfo.isOngoingGroupObservation(uriPath)) {
			LOGGER.debug("No ongoing group observation to cancel for {}", uriPath);
			return;
		}

		LOGGER.info("Cancelling group observation for {}", uriPath);
		cancelling = true;
		changed();
		groupInfo.removeGroupObservation(uriPath);
		cancelling = false;
	}

	private void handlePhantomRequest(CoapExchange exchange) {
		String uriPath = this.getURI();
		GroupObservationsInfo groupObservationsInfo = GroupObservationsInfo.getInstance();
		Token exchangeToken = exchange.advanced().getRequest().getToken();
		Token pendingToken = groupObservationsInfo.getPendingToken(uriPath);

		if (pendingToken != null && pendingToken.equals(exchangeToken)) {
			// First phantom request — establish the group observation
			exchange.advanced().setSuppressResponse(true);

			InetSocketAddress localAddress = exchange.advanced().getEndpoint().getAddress();
			InetSocketAddress multicastAddress = groupObservationsInfo.getMulticastAddress();
			ObservationInfo observationInfo = new ObservationInfo(localAddress, multicastAddress, exchangeToken);

			groupObservationsInfo.startGroupObservation(uriPath, observationInfo);
			groupObservationsInfo.setGroupObservationSetupInProgress(uriPath, false);

			// Store OSCORE protected phantom request
			groupObservationsInfo.getGroupObservationInfo(uriPath).setPhReq((exchange.advanced().getProtectedRequest()));
			
			// Respond to establish the observe relation (response will be suppressed)
			exchange.respond(ResponseCode.CONTENT, Integer.toString(this.content));
			LOGGER.debug("Phantom request established for {} with content: {}", uriPath, this.content);

			// Section 4.1 Step 6: Store INIT_NOTIF as last_notif for informative responses
			Response initNotif = new Response(ResponseCode.CONTENT);
			initNotif.setPayload(Integer.toString(this.content));
			initNotif.getOptions().setContentFormat(MediaTypeRegistry.TEXT_PLAIN);
			observationInfo.setLastNotif(initNotif);

			sendInformativeResponsesToClients(uriPath);
		} else {
			// Section 4.5: If cancelling, send 5.03 with no payload and no Observe option
			if (cancelling) {
				LOGGER.info("Sending cancellation 5.03 for group observation on {}", uriPath);
				exchange.respond(new Response(ResponseCode.SERVICE_UNAVAILABLE));
				return;
			}

			// Subsequent notification for established phantom exchange
			LOGGER.debug("Sending multicast notification for {} with content: {}", uriPath, this.content);

			Response notification = new Response(ResponseCode.CONTENT);
			notification.setPayload(Integer.toString(this.content));
			notification.getOptions().setContentFormat(MediaTypeRegistry.TEXT_PLAIN);

			ObservationInfo observationInfo = groupObservationsInfo.getGroupObservationInfo(uriPath);
			if (observationInfo != null) {
				observationInfo.setLastNotif(notification);
			}

			exchange.respond(notification);
		}
	}
	@Override
	public void handleRequest(final Exchange exchange) {
		GroupObservationsInfo groupObservationsInfo = GroupObservationsInfo.getInstance();
		if (groupObservationsInfo.isOngoingGroupObservation(this.getURI())) {
			exchange.getRequest().getOptions().setOscore(Bytes.EMPTY);
		}
		super.handleRequest(exchange);
	}

	private boolean shouldGroupObservationStart() {
		return this.getObserverCount() >= 0;
	}

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
					LOGGER.debug("Setup in progress for {}, added client as pending", resourceUri);
					return;
				}
			} else {
				groupObservationsInfo.setGroupObservationSetupInProgress(resourceUri, true);
			}
		}

		if (exchange.advanced().isPhantomRequest()) {
			return;
		}

		// Allocate multicast token T and create phantom request
		Token multicastToken = groupObservationsInfo.allocateMulticastToken(resourceUri);
		LOGGER.debug("Allocated multicast token {} for {}", multicastToken, resourceUri);

		final Request phantomRequest = groupObservationsInfo.createPhantomRequest(this, multicastToken, exchange, true);

		// Deliver phantom request through the CoAP stack so ObserveLayer
		// can detect it and establish the observe relation
		LOGGER.debug("Injecting phantom request for {} with token {}", resourceUri, multicastToken);
		CoapEndpoint endpoint = (CoapEndpoint) exchange.advanced().getEndpoint();
		endpoint.sendRequest(phantomRequest);
	}
	class UpdateTask extends TimerTask {
		private int notificationCount = 0;

		@Override
		public void run() {
			int prev = content;
			content++;
			LOGGER.debug("{} -> {}", prev, content);
			changed();

			if (GroupObservationsInfo.getInstance().isOngoingGroupObservation(getURI())) {
				notificationCount++;
				if (notificationCount >= CANCEL_AFTER_NOTIFICATIONS) {
					LOGGER.info("Reached {} notifications, cancelling group observation", CANCEL_AFTER_NOTIFICATIONS);
					cancelGroupObservation();
					notificationCount = 0;
				}
			}
		}
	}
}

