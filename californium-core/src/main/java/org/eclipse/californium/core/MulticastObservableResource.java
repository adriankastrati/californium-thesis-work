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
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executor;
import java.util.concurrent.Semaphore;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Predicate;

import org.eclipse.californium.core.coap.CoAP;
import org.eclipse.californium.core.coap.CoAP.ResponseCode;
import org.eclipse.californium.core.coap.CoAP.Type;
import org.eclipse.californium.core.coap.MediaTypeRegistry;
import org.eclipse.californium.core.coap.OptionSet;
import org.eclipse.californium.core.coap.Response;
import org.eclipse.californium.core.coap.Token;
import org.eclipse.californium.core.network.Exchange;
import org.eclipse.californium.core.network.stack.BlockwiseLayer;
import org.eclipse.californium.core.observe.GroupObservationsInfo;
import org.eclipse.californium.core.observe.ObservationInfo;
import org.eclipse.californium.core.observe.ObserveNotificationOrderer;
import org.eclipse.californium.core.observe.ObserveRelation;
import org.eclipse.californium.core.server.resources.ObservableResource;
import org.eclipse.californium.core.server.resources.Resource;
import org.eclipse.californium.core.server.resources.ResourceAttributes;
import org.eclipse.californium.core.server.resources.ResourceObserver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class MulticastObservableResource extends CoapResource {
  private static final Logger LOGGER = LoggerFactory.getLogger(MulticastObservableResource.class);
  private volatile String content = "initial";
  
  
  public MulticastObservableResource(String uri) {
    // set resource identifier
    super(uri);
    // set display name
    getAttributes().setTitle("Multicast Observable Resource");
    setObservable(true);
    setObserveType(null);
    LOGGER.info("MulticastObservableResource created - URI: {}, Path: {}, Observable: {}", uri, this.getPath(), isObservable());
  }

  
  @Override
  public void handleGET(CoapExchange exchange) {
    // respond to the request
    GroupObservationsInfo groupObservationsInfo = GroupObservationsInfo.getInstance();
    String uriPath = this.getURI();

    Token exchangeToken = exchange.advanced().getRequest().getToken();
    
    if (exchange.advanced().isPhantomRequest()) {
      // This is the self-sent phantom request to start group observation
      // OR a notification being sent to the phantom exchange
      Token pendingToken = GroupObservationsInfo.getInstance().getPendingToken(uriPath);
      
      if (pendingToken != null && pendingToken.equals(exchangeToken)) {
        // Initial phantom request during setup - suppress and store
        // Suppress the response - it will establish the observation but not be sent on wire
        exchange.advanced().setSuppressResponse(true);
        
        // Create ObservationInfo for the group observation
        InetSocketAddress localAddress = exchange.advanced().getEndpoint().getAddress();
        InetSocketAddress multicastAddress = groupObservationsInfo.getMulticastAddress();
        ObservationInfo observationInfo = new ObservationInfo(localAddress, multicastAddress, exchangeToken);
        
        // Start the group observation (this removes token from pendingMulticastNotificationTokens)
        groupObservationsInfo.startGroupObservation(uriPath, observationInfo);
        // Clear the setup-in-progress flag
        groupObservationsInfo.setGroupObservationSetupInProgress(uriPath, false);
        
        // Send informative response (5.03 Service Unavailable) to all pending clients
        sendInformativeResponsesToClients(uriPath, observationInfo);

        // Still respond - this will establish the observe relation internally
        // but the response will be suppressed by StackBottomAdapter
        exchange.respond(ResponseCode.CONTENT, this.content);
        return;
      } else {
        // This is a notification for an established phantom exchange
        // Just send the normal response - it will go to multicast address
        LOGGER.info("Sending multicast notification for {} with content: {}", uriPath, this.content);
        
        exchange.respond(ResponseCode.CONTENT, this.content);
        return;
      }
    }
    
    // Check if this is a regular observe request while group observation is already active
    if (exchange.getRequestOptions().hasObserve() 
        && exchange.getRequestOptions().getObserve() == 0
        && groupObservationsInfo.isOngoingGroupObservation(uriPath)) {
      // Group observation already active - send informative response immediately
      ObservationInfo obsInfo = groupObservationsInfo.getGroupObservationInfo(uriPath);
      if (obsInfo != null) {
        sendInformativeResponse(exchange, obsInfo);
        return;
      }
    }
    
    // Normal GET request handling
    exchange.respond(ResponseCode.CONTENT, this.content);
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
    
    LOGGER.info("Sending informative response (5.03) to client {} with token {}. ObservationInfo: {}",
        exchange.getSourceSocketAddress(), exchange.advanced().getRequest().getToken(), obsInfo);
    LOGGER.info("payload: {}",response.toString());
    
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
      response.getOptions().setContentFormat(MediaTypeRegistry.APPLICATION_CBOR);
      
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
}