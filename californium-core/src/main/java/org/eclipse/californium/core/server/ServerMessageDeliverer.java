/*******************************************************************************
 * Copyright (c) 2015, 2016 Institute for Pervasive Computing, ETH Zurich and others.
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
 *    Kai Hudalla (Bosch Software Innovations GmbH) - use Logger's message formatting instead of
 *                                                    explicit String concatenation
 *    Achim Kraus (Bosch Software Innovations GmbH) - replace byte array token by Token
 ******************************************************************************/
package org.eclipse.californium.core.server;

import static org.mockito.ArgumentMatchers.endsWith;

import java.net.InetSocketAddress;
import java.util.List;
import java.util.concurrent.Executor;

import org.eclipse.californium.core.coap.CoAP;
import org.eclipse.californium.core.coap.CoAP.ResponseCode;
import org.eclipse.californium.core.coap.CoAP.Type;
import org.eclipse.californium.core.coap.Request;
import org.eclipse.californium.core.coap.Response;
import org.eclipse.californium.core.coap.Token;
import org.eclipse.californium.core.coap.option.StringOption;
import org.eclipse.californium.core.network.Exchange;
import org.eclipse.californium.core.network.Exchange.Origin;
import org.eclipse.californium.core.observe.GroupObservationsInfo;
import org.eclipse.californium.core.observe.GroupObservationsInfo;
import org.eclipse.californium.core.observe.ObserveHealth;
import org.eclipse.californium.core.observe.ObserveManager;
import org.eclipse.californium.core.server.resources.ObservableResource;
import org.eclipse.californium.core.server.resources.Resource;
import org.eclipse.californium.elements.AddressEndpointContext;
import org.eclipse.californium.elements.config.Configuration;
import org.eclipse.californium.elements.util.StringUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The ServerMessageDeliverer delivers requests to corresponding resources and
 * responses to corresponding requests.
 */
public class ServerMessageDeliverer implements MessageDeliverer {

	private static final Logger LOGGER = LoggerFactory.getLogger(ServerMessageDeliverer.class);

	/* The root of all resources */
	private final Resource root;

	/* The manager of the observe mechanism for this server */
	private final ObserveManager observeManager;

	/**
	 * Constructs a default message deliverer that delivers requests to the
	 * resources rooted at the specified root.
	 * 
	 * @param root the root resource
	 * @param config the configuration
	 * @since 3.6
	 */
	public ServerMessageDeliverer(Resource root, Configuration config) {
		this.root = root;
		this.observeManager = new ObserveManager(config);
	}

	/**
	 * Set observe health status.
	 * 
	 * @param observeHealth health status for observe.
	 * @since 3.6
	 */
	public void setObserveHealth(ObserveHealth observeHealth) {
		observeManager.setObserveHealth(observeHealth);
	}

	/**
	 * Delivers an inbound CoAP request to an appropriate resource.
	 * <p>
	 * This method first invokes {@link #preDeliverRequest(Exchange)}. The
	 * request is considered <em>processed</em> if the
	 * <em>preDeliverRequest</em> method returned {@code true}.
	 * <p>
	 * Otherwise, this method
	 * <ol>
	 * <li>tries to {@linkplain #findResource(List) find a matching
	 * resource},</li>
	 * <li>handle a GET request's observe option and</li>
	 * <li>deliver the request to the resource for processing.</li>
	 * </ol>
	 * 
	 * @param exchange The exchange containing the inbound request.
	 * @throws NullPointerException if exchange is {@code null}.
	 */
	@Override
	public final void deliverRequest(final Exchange exchange) {
		if (exchange == null) {
			throw new NullPointerException("exchange must not be null");
		}
		boolean processed = preDeliverRequest(exchange);
		if (!processed) {
			try {
				final Resource resource = findResource(exchange);
				if (resource != null) {
					checkForObserveOption(exchange, resource);

					// Get the executor and let it process the request
					Executor executor = resource.getExecutor();
					if (executor != null) {
						executor.execute(new Runnable() {

							public void run() {
								resource.handleRequest(exchange);
							}
						});
					} else {
						resource.handleRequest(exchange);
					}
				} else {
					if (LOGGER.isInfoEnabled()) {
						Request request = exchange.getRequest();
						LOGGER.info("did not find resource /{} requested by {}",
								request.getOptions().getUriPathString(),
								StringUtil.toLog(request.getSourceContext().getPeerAddress()));
					}
					exchange.sendResponse(new Response(ResponseCode.NOT_FOUND, true));
				}
			} catch (DelivererException ex) {
				Response response = new Response(ex.getErrorResponseCode(), ex.isInternal());
				response.setPayload(ex.getMessage());
				exchange.sendResponse(response);
			}
		}
	}

	/**
	 * Invoked by the <em>deliverRequest</em> before the request gets processed.
	 * <p>
	 * Subclasses may override this method in order to replace the default
	 * request handling logic or to modify or add headers etc before the request
	 * gets processed.
	 * <p>
	 * This default implementation returns {@code false}.
	 * 
	 * @param exchange The exchange for the incoming request.
	 * @return {@code true} if the request has already been processed by this
	 *         method and thus should not be delivered to a matching resource
	 *         anymore.
	 */
	protected boolean preDeliverRequest(final Exchange exchange) {
		return false;
	}

	/**
	 * Checks whether an observe relationship has to be established or canceled.
	 * <p>
	 * This is done here to have a server-global observeManager that holds the
	 * set of remote endpoints for all resources. This global knowledge is
	 * required for efficient orphan handling.
	 * 
	 * @param exchange the exchange of the current request
	 * @param resource the target resource
	 */
	protected final void checkForObserveOption(final Exchange exchange, final Resource resource) {

		Request request = exchange.getRequest();

		if (CoAP.isObservable(request.getCode()) && request.getOptions().hasObserve() && resource.isObservable()
				&& resource instanceof ObservableResource) {

			if (request.isObserve()) {
				GroupObservationsInfo groupObservationsInfo = GroupObservationsInfo.getInstance();
				String resourceUri = resource.getURI();

				if (groupObservationsInfo.isOngoingGroupObservation(resourceUri)) {
					// Group observation already established - send informative response to client
					LOGGER.debug("Group observation already exists for {}, sending informative response", resourceUri);
					// TODO_A: send 5.03 informative response with tp_info, ph_req, last_notif

				} else if (((ObservableResource) resource).getObserverCount() >= 0) {
					LOGGER.debug("Group observation for {} starting", resourceUri);

					// trigger phantom request setup
					// Check if setup is already in progress 
					synchronized (groupObservationsInfo) {
						// If setup already in progress
						if (groupObservationsInfo.isGroupObservationSetupInProgress(resourceUri)) {
							// Phantom requests should pass through to establish observe relation
							if (exchange.isPhantomRequest()) {
								LOGGER.debug("Phantom request for {} - allowing delivery during setup", resourceUri);
								// Don't return - let the phantom be delivered to the resource
							} else {
								// Regular client during setup - add as pending client for informative response
								groupObservationsInfo.addPendingClient(resourceUri, exchange);
								LOGGER.debug("Group observation setup in progress for {}, added client as pending", resourceUri);
								return;
							}
						} else {
							// We are the first thread - mark setup as in progress
							groupObservationsInfo.setGroupObservationSetupInProgress(resourceUri, true);
						}
					}

					// If this is a phantom request, it should just be delivered to the resource
					// (don't create another phantom or add to pending)
					if (exchange.isPhantomRequest()) {
						// Phantom request - add observe relation and continue to resource
						LOGGER.debug("Phantom request for {} - adding observe relation and continuing to resource", resourceUri);
						observeManager.addObserveRelation(exchange, (ObservableResource) resource);
						request.setProtectFromOffload();
            exchange.setSuppressResponse(true);
            
					} else {
						// Step 5: Allocate multicast token T
						Token multicastToken = groupObservationsInfo.allocateMulticastToken(resourceUri);
						LOGGER.debug("Allocated multicast token {} for group observation on {}", multicastToken, resourceUri);

						// Step 6-7: Create and deliver phantom request
						// Note: First client is NOT added to pending clients - it will continue
						// to handleGET after phantom completes and receive informative response there
						final Exchange phantomExchange = createPhantomExchange(resource, multicastToken, exchange);					
						LOGGER.debug("Delivering phantom request for {} with token {}", resourceUri, multicastToken);
						
						// Deliver phantom inline (synchronously) so the group observation is established
						// before the first client's request continues to handleGET.
						// The phantom's observe relation will be established during response handling
						// via ObserveRelation.onResponse(), not here.
						deliverRequest(phantomExchange);
						
						// After phantom is delivered, the group observation is established.
						// Now continue to let the first client's request reach handleGET,
						// where it will see the group observation exists and send informative response.
					}

				} else {
					// Regular unicast observe (no group observation)
					InetSocketAddress source = request.getSourceContext().getPeerAddress();
					LOGGER.debug("initiating an observe relation between {} and resource {}, {}", StringUtil.toLog(source),
							resource.getURI(), exchange);
					observeManager.addObserveRelation(exchange, (ObservableResource) resource);
					request.setProtectFromOffload();
				}
        
			} else if (request.isObserveCancel()) {
				// Observe defines 1 for canceling
				InetSocketAddress source = request.getSourceContext().getPeerAddress();
				LOGGER.debug("cancel an observe relation between {} and resource {}, {}", StringUtil.toLog(source),
						resource.getURI(), exchange);
				observeManager.cancelObserveRelation(exchange);
			}
		}
	}

	/**
	 * Return root resource.
	 * 
	 * Intended to be used by custom {@link #findResource(List)}.
	 * 
	 * @return root resources
	 * @see #root
	 */
	protected Resource getRootResource() {
		return root;
	}

	/**
	 * Searches in the resource tree for the specified path. A parent resource
	 * may accept requests to sub-resources, e.g., to allow addresses with
	 * wildcards like <code>coap://example.com:5683/devices/*</code>
	 * 
	 * @param exchange The exchange containing the inbound request including the
	 *            path of resource names
	 * @return the resource or {@code null}, if not found
	 * @throws DelivererException if an other error is detected.
	 * @since 3.0 (added DelivererException)
	 */
	protected Resource findResource(Exchange exchange) throws DelivererException {
		return findResource(exchange.getRequest().getOptions().getUriPath());
	}

	/**
	 * Searches in the resource tree for the specified path. A parent resource
	 * may accept requests to sub-resources, e.g., to allow addresses with
	 * wildcards like <code>coap://example.com:5683/devices/*</code>
	 * 
	 * @param path the path as list of resource names
	 * @return the resource or {@code null}, if not found
	 * @throws DelivererException if an other error is detected.
	 * @since 3.0 (added DelivererException)
	 */
	protected Resource findResource(final List<StringOption> path) throws DelivererException {
		Resource current = getRootResource();
		for (StringOption name : path) {
			current = current.getChild(name.getStringValue());
			if (current == null) {
				break;
			}
		}
		return current;
	}

	/**
	 * Delivers an inbound CoAP response message to its corresponding request.
	 * <p>
	 * This method first invokes
	 * {@link #preDeliverResponse(Exchange, Response)}. The response is
	 * considered <em>processed</em> if the <em>preDeliverResponse</em> method
	 * returned {@code true}.
	 * <p>
	 * * Otherwise, this method delivers the response to the corresponding
	 * request.
	 * 
	 * @param exchange The exchange containing the originating CoAP request.
	 * @param response The inbound CoAP response message.
	 * @throws NullPointerException if exchange or response are {@code null}.
	 * @throws IllegalArgumentException if the exchange does not contain a
	 *             request.
	 */
	@Override
	public final void deliverResponse(final Exchange exchange, final Response response) {
		if (response == null) {
			throw new NullPointerException("Response must not be null");
		} else if (exchange == null) {
			throw new NullPointerException("Exchange must not be null");
		} else if (exchange.getRequest() == null) {
			throw new IllegalArgumentException("Exchange does not contain request");
		} else {
			boolean processed = preDeliverResponse(exchange, response);
			if (!processed) {
				exchange.getRequest().setResponse(response);
			}
		}
	}

	/**
	 * Invoked by the <em>deliverResponse</em> method before the response is
	 * delivered to the corresponding request.
	 * <p>
	 * Subclasses may override this method in order to replace the default
	 * response handling logic or to modify or add headers etc before the
	 * response is delivered to the request.
	 * <p>
	 * The response is delivered to the request if and only if the exchange's
	 * request does not contain a <em>response</em> when this method returns.
	 * <p>
	 * This default implementation returns {@code false}.
	 * 
	 * @param exchange The exchange containing the request that the incoming
	 *            response belongs to.
	 * @param response The incoming response.
	 * @return {@code true} if the response has been processed by this method
	 *         and thus should not be delivered to the corresponding request
	 *         anymore.
	 */
	protected boolean preDeliverResponse(final Exchange exchange, final Response response) {
		return false;
	}

	/**
	 * Creates a phantom request and exchange for multicast group observation.
	 * <p>
	 * The phantom request is a self-generated observe request that establishes
	 * the group observation. Its source context is set to the multicast group
	 * address so that responses (notifications) will be sent to the multicast group.
	 * 
	 * @param resource the resource to observe
	 * @param multicastToken the token T allocated for multicast notifications
	 * @param triggeringExchange the original client exchange that triggered this setup
	 * @return the phantom exchange ready to be delivered
	 */
	protected Exchange createPhantomExchange(Resource resource, Token multicastToken, Exchange triggeringExchange) {
		GroupObservationsInfo groupInfo = GroupObservationsInfo.getInstance();
		
		// Step 6: Build the phantom GET request
		Request phantomRequest = Request.newGet();
		
		// Set the multicast token T
		phantomRequest.setToken(multicastToken);
		
		// Set Observe=0 (register for observation)
		phantomRequest.setObserve();
		
		// Set URI path to the resource
		phantomRequest.getOptions().setUriPath(resource.getURI());
		
		// Set message type to NON (multicast requires non-confirmable)
		phantomRequest.setType(Type.NON);
		
		InetSocketAddress localAddress = triggeringExchange.getEndpoint().getAddress();
		
		// Set source context to MULTICAST ADDRESS 
		// When notifications are sent, the response destination is set from request source,
		// so setting multicast here means notifications go to the multicast group
		InetSocketAddress multicastAddress = groupInfo.getMulticastAddress();
		phantomRequest.setSourceContext(new AddressEndpointContext(multicastAddress));
		LOGGER.debug("Phantom request source set to multicast address: {}", multicastAddress);

		// Step 7: Create the Exchange for server-side processing
		// Use Origin.REMOTE because the server should treat this as an incoming request
		Exchange phantomExchange = new Exchange(phantomRequest, localAddress, Origin.REMOTE, triggeringExchange.getEndpoint().getExecutor());
		
		// Mark exchange as phantom request
		phantomExchange.setPhantomRequest(true);
		
		// Set endpoint from triggering exchange if available
		if (triggeringExchange.getEndpoint() != null) {
			phantomExchange.setEndpoint(triggeringExchange.getEndpoint());
		}
		
		LOGGER.debug("Created phantom exchange for resource {} with multicast token {}", 
				resource.getURI(), multicastToken);
		
		return phantomExchange;
	}
}
