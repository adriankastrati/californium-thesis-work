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
package org.eclipse.californium.core;

import java.net.InetSocketAddress;
import java.util.List;
import org.eclipse.californium.core.coap.CoAP.ResponseCode;
import org.eclipse.californium.core.coap.CoAP.Type;
import org.eclipse.californium.core.coap.MediaTypeRegistry;
import org.eclipse.californium.core.coap.Request;
import org.eclipse.californium.core.coap.Response;
import org.eclipse.californium.core.coap.Token;
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

public class MulticastObservableResource extends CoapResource {
  private static final Logger LOGGER = LoggerFactory.getLogger(MulticastObservableResource.class);
  private volatile String content = "initial";
  private final boolean isGroupObservable;
  private final MessageDeliverer serverMessageDeliverer;
  public MulticastObservableResource(String uri, boolean groupObservable, MessageDeliverer serverMessageDeliverer) {
    // set resource identifier
    super(uri);
    this.serverMessageDeliverer = serverMessageDeliverer;
    // set display name
    getAttributes().setTitle("Multicast Observable Resource");
    setObservable(true);
    setObserveType(null);
    this.isGroupObservable = groupObservable;
    LOGGER.info("MulticastObservableResource created - URI: {}, Path: {}, Observable: {}", uri, this.getPath(), isObservable());
  }

  
  @Override
  public void handleGET(CoapExchange exchange) {
    // respond to the request
    GroupObservationsInfo groupObservationsInfo = GroupObservationsInfo.getInstance();
    String uriPath = this.getURI();
    
    if (exchange.getRequestOptions().hasObserve() && exchange.getRequestOptions().getObserve() == 0){
      if (exchange.advanced().isPhantomRequest()) {
          handlePhantomRequest(exchange);
      }
      // Check if this is a regular observe request while group observation is already active
      else if (groupObservationsInfo.isOngoingGroupObservation(uriPath)) {
        // Group observation already active, send informative response immediately
        ObservationInfo obsInfo = groupObservationsInfo.getGroupObservationInfo(uriPath);
        sendInformativeResponse(exchange, obsInfo);
        
      }else if (shouldGroupObservationStart()){
        groupObservationsInfo.addPendingClient(this.getURI(), exchange.advanced());
        setUpGroupObservation(exchange);
      }
    }else{
      // Normal GET request handling
      exchange.respond(ResponseCode.CONTENT, this.content);
    }
  }
  
  /**
   * Send informative response (5.03 Service Unavailable) with tp_info to a client.
   */
  private void sendInformativeResponse(CoapExchange exchange, ObservationInfo obsInfo) {
    Response response = new Response(ResponseCode.SERVICE_UNAVAILABLE);
    
    // Set Content-Format to application/informative-response+cbor
    response.getOptions().setContentFormat(MediaTypeRegistry.APPLICATION_INFORMATIVE_RESPONSE_CBOR);
    response.setConfirmable(true);
    // Serialize ObservationInfo to CBOR payload
    byte[] payload = obsInfo.toCbor();
    response.setPayload(payload);
    
    LOGGER.debug("Sending informative response (5.03) to client {} with token {}. ObservationInfo: {}",
        exchange.getSourceSocketAddress(), exchange.advanced().getRequest().getToken(), obsInfo);
    LOGGER.debug("payload: {}",response.toString());
    
    // Send the informative response
    exchange.respond(response);
  }
  
  /**
   * Send informative responses to all pending clients waiting for group observation setup.
   */
  private void sendInformativeResponsesToClients(String uriPath, ObservationInfo obsInfo) {
    GroupObservationsInfo groupInfo = GroupObservationsInfo.getInstance();
    List<Exchange> pendingClients = groupInfo.removePendingClients(uriPath);
    
    LOGGER.info("Sending informative responses to {} pending clients for resource {}", 
        pendingClients.size(), uriPath);
    
    for (Exchange clientExchange : pendingClients) {
      Response response = new Response(ResponseCode.SERVICE_UNAVAILABLE);
      response.getOptions().setContentFormat(MediaTypeRegistry.APPLICATION_INFORMATIVE_RESPONSE_CBOR);
      
      byte[] payload = groupInfo.getGroupObservationInfo(uriPath).toCbor();
      response.setPayload(payload);
      
      // Set destination from original request source
      response.setDestinationContext(clientExchange.getRequest().getSourceContext());
      response.setToken(clientExchange.getRequest().getToken());
      
      LOGGER.info("Sending informative response (5.03) to pending client {} with token {}. ObservationInfo: {}",
          clientExchange.getRequest().getSourceContext().getPeerAddress(), 
          clientExchange.getRequest().getToken(), obsInfo);
      
      clientExchange.sendResponse(response);
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
  public boolean isGroupObservable(){
    return isGroupObservable;
  }

  private void handlePhantomRequest(CoapExchange exchange){
    String uriPath =  this.getURI();
    GroupObservationsInfo groupObservationsInfo = GroupObservationsInfo.getInstance();
    Token exchangeToken = exchange.advanced().getRequest().getToken();


    Token pendingToken = GroupObservationsInfo.getInstance().getPendingToken(uriPath);
    
    if (pendingToken != null && pendingToken.equals(exchangeToken)) {
      // Suppress the response - it will establish the observation but not be sent on wire
      exchange.advanced().setSuppressResponse(true);
      
      // Create ObservationInfo for the group observation
      InetSocketAddress localAddress = exchange.advanced().getEndpoint().getAddress();
      InetSocketAddress multicastAddress = groupObservationsInfo.getMulticastAddress();
      ObservationInfo observationInfo = new ObservationInfo(localAddress, multicastAddress, exchangeToken);
      
      // Start the group observation, this removes token from pendingMulticastNotificationTokens
      groupObservationsInfo.startGroupObservation(uriPath, observationInfo);

      // Clear the setup-in-progress flag
      groupObservationsInfo.setGroupObservationSetupInProgress(uriPath, false);
      
      // Send informative response to all pending clients
      sendInformativeResponsesToClients(uriPath, observationInfo);

      // Still respond, this will establish the observe relation internally
      // but the response will be suppressed by StackBottomAdapter
      exchange.respond(ResponseCode.CONTENT, this.content);
    }else
      {
        // This is a notification for an established phantom exchange
        // Just send the normal response - it will go to multicast address
        LOGGER.info("Sending multicast notification for {} with content: {}", uriPath, this.content);
        
        exchange.respond(ResponseCode.CONTENT, this.content);
        return;
      }
  }

  private boolean shouldGroupObservationStart(){
    return this.getObserverCount() >= 0;
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
		InetSocketAddress multicastAddress = groupInfo.getMulticastAddress();
		phantomRequest.setSourceContext(new AddressEndpointContext(multicastAddress));
		LOGGER.debug("Phantom request source set to multicast address: {}", multicastAddress);

		// Step 7: Create the Exchange for server-side processing
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

 
  private void setUpGroupObservation(CoapExchange exchange){
    String resourceUri = this.getURI();
    GroupObservationsInfo groupObservationsInfo = GroupObservationsInfo.getInstance();

					LOGGER.debug("Group observation for {} starting", resourceUri);

					// trigger phantom request setup
					// Check if setup is already in progress 
					synchronized (groupObservationsInfo) {
						// If setup already in progress
						if (groupObservationsInfo.isGroupObservationSetupInProgress(resourceUri)) {
							// Phantom requests should pass through to establish observe relation
							if (exchange.advanced().isPhantomRequest()) {
								LOGGER.debug("Phantom request for {} - allowing delivery during setup", resourceUri);
								// Don't return - let the phantom be delivered to the resource
							} else {
								// Regular client during setup - add as pending client for informative response
								groupObservationsInfo.addPendingClient(resourceUri, exchange.advanced());
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
					if (exchange.advanced().isPhantomRequest()) {
						return;
					} else {
						// Step 5: Allocate multicast token T
						Token multicastToken = groupObservationsInfo.allocateMulticastToken(resourceUri);
						LOGGER.debug("Allocated multicast token {} for group observation on {}", multicastToken, resourceUri);

						// Step 6-7: Create and deliver phantom request
						// Note: First client is NOT added to pending clients - it will continue
						// to handleGET after phantom completes and receive informative response there
            final Exchange phantomExchange = groupObservationsInfo.createPhantomExchange(this, multicastToken, exchange.advanced());					
								
					
						// The phantom's observe relation will be established during response handling
						// via ObserveRelation.onResponse()
						LOGGER.debug("Delivering phantom request for {} with token {}", resourceUri, multicastToken);
            serverMessageDeliverer.deliverRequest(phantomExchange);

						// After phantom is delivered, the group observation is established.
						// Now continue to let the first client's request reach handleGET,
				}
  }
}