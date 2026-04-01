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
import java.util.TimerTask;
import java.util.Timer;

import org.eclipse.californium.core.coap.CoAP.ResponseCode;
import org.eclipse.californium.core.coap.CoAP.Type;
import org.eclipse.californium.core.CoapExchange;
import org.eclipse.californium.core.CoapResource;
import org.eclipse.californium.core.coap.MediaTypeRegistry;
import org.eclipse.californium.core.coap.OptionSet;
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
import org.eclipse.californium.elements.util.Bytes;
import org.eclipse.californium.oscore.ErrorDescriptions;
import org.eclipse.californium.oscore.OSCoreResource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class OSCOREMulticastObservableResource extends OSCoreResource {
  private static final Logger LOGGER = LoggerFactory.getLogger(OSCOREMulticastObservableResource.class);
  private volatile int content = 1;
  private Timer timer  = new Timer();
  private boolean firstRequestReceived = false;
  private volatile boolean cancelling = false;
  private static final int CANCEL_AFTER_NOTIFICATIONS = 5;

  private final boolean isGroupObservable;
  private final MessageDeliverer serverMessageDeliverer;
  public OSCOREMulticastObservableResource(String uri, boolean groupObservable, MessageDeliverer serverMessageDeliverer) {
    // set resource identifier
    super(uri, true);
    this.serverMessageDeliverer = serverMessageDeliverer;
    // set display name
    getAttributes().setTitle("Multicast Observable Resource");
    setObservable(true);
    setObserveType(null);
    this.isGroupObservable = groupObservable;
    LOGGER.info("MulticastObservableResource created - URI: {}, Path: {}, Observable: {}", uri, this.getPath(), isObservable());
    timer.schedule(new UpdateTask(), 0, 10000);

  }

  
  @Override
  public void handleGET(CoapExchange exchange) {
	firstRequestReceived= true;
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
        groupObservationsInfo.addPendingClient(this.getURI(), exchange);
        setUpGroupObservation(exchange);
      }
    }else{
      // Normal GET request handling
      exchange.respond(ResponseCode.CONTENT, Integer.toString(this.content));
    }
  }
  
  /**
   * Send informative response (5.03 Service Unavailable) with tp_info to a client.
   */
  private void sendInformativeResponse(CoapExchange clientExchange, ObservationInfo obsInfo) {
	  LOGGER.debug("sending first ack");
	  clientExchange.accept();                                                                                                              
	 
	  Response response = new Response(ResponseCode.SERVICE_UNAVAILABLE);
      
	  response.setType(Type.CON); // RFC Section 4.2: informative response MUST be Confirmable
      response.getOptions().setContentFormat(MediaTypeRegistry.APPLICATION_INFORMATIVE_RESPONSE_CBOR);

      byte[] payload = obsInfo.toCbor();
      response.setPayload(payload);
      
      // Set destination from original request source
      response.setDestinationContext(clientExchange.advanced().getRequest().getSourceContext());
      response.setToken(clientExchange.advanced().getRequest().getToken());
      
      LOGGER.info("Sending informative response (5.03) to pending client {} with token {}. ObservationInfo: {}",
      clientExchange.advanced().getRequest().getSourceContext().getPeerAddress(),
      clientExchange.advanced().getRequest().getToken(), obsInfo);

      clientExchange.advanced().getRequest().getOptions().removeObserve();

      clientExchange.respond(response);
  }
  
  /**
   * Send informative responses to all pending clients waiting for group observation setup.
   */
  private void sendInformativeResponsesToClients(String uriPath) {
    GroupObservationsInfo groupInfo = GroupObservationsInfo.getInstance();
    List<CoapExchange> pendingClients = groupInfo.removePendingClients(uriPath);
    
    LOGGER.info("Sending informative responses to {} pending clients for resource {}", 
        pendingClients.size(), uriPath);
    
    for (CoapExchange clientExchange : pendingClients) {
    	sendInformativeResponse(clientExchange, groupInfo.getGroupObservationInfo(uriPath));
    	
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
    
  public boolean isGroupObservable(){
    return isGroupObservable;
  }

  /**
   * Cancel the group observation per RFC Section 4.5.
   *
   * Sends a multicast 5.03 (Service Unavailable) with Token T,
   * no payload, and no Observe option to the multicast group,
   * then cleans up all group observation state.
   */
  public void cancelGroupObservation() {
    String uriPath = this.getURI();
    GroupObservationsInfo groupInfo = GroupObservationsInfo.getInstance();

    if (!groupInfo.isOngoingGroupObservation(uriPath)) {
      LOGGER.info("No ongoing group observation to cancel for {}", uriPath);
      return;
    }

    LOGGER.info("Cancelling group observation for {}", uriPath);
    cancelling = true;
    changed();
    groupInfo.removeGroupObservation(uriPath);
    cancelling = false;
    LOGGER.info("Group observation cancelled for {}", uriPath);
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

      // Clear the setup-in-progress flagsss
      groupObservationsInfo.setGroupObservationSetupInProgress(uriPath, false);

      //Add phantom request as transport-independent bytes (Section 4.2.2: code + options + payload)
      groupObservationsInfo.getGroupObservationInfo(uriPath).setPhReq(
          ObservationInfo.extractTransportIndependent(exchange.advanced().getProtectedRequest()));

      // Still respond, this will establish the observe relation internally
      // but the response will be suppressed by StackBottomAdapter
      exchange.respond(ResponseCode.CONTENT, Integer.toString(this.content));
      LOGGER.info("handle phantom request for {} with content: {}", uriPath, this.content);

      // Section 4.1 Step 6: Build and store INIT_NOTIF so it can be included
      // as last_notif in informative responses sent to clients.
      Response initNotif = new Response(ResponseCode.CONTENT);
      initNotif.setPayload(Integer.toString(this.content));
      initNotif.getOptions().setContentFormat(MediaTypeRegistry.TEXT_PLAIN);
      observationInfo.setLastNotif(initNotif);

      // Send informative response to all pending clients
      sendInformativeResponsesToClients(uriPath);
    }else
      {
        // Section 4.5: If cancelling, send 5.03 with no payload and no Observe option
        if (cancelling) {
          LOGGER.info("Sending cancellation 5.03 for group observation on {}", uriPath);
          Response cancellation = new Response(ResponseCode.SERVICE_UNAVAILABLE);
          exchange.respond(cancellation);
          return;
        }

        // This is a notification for an established phantom exchange
        // Just send the normal response - it will go to multicast address
        LOGGER.info("Sending multicast notification for {} with content: {}", uriPath, this.content);

        Response notification = new Response(ResponseCode.CONTENT);
        notification.setPayload(Integer.toString(this.content));
        notification.getOptions().setContentFormat(MediaTypeRegistry.TEXT_PLAIN);

        ObservationInfo observationInfo = groupObservationsInfo.getGroupObservationInfo(uriPath);
        if (observationInfo != null) {
        observationInfo.setLastNotif(notification);
        }

        exchange.respond(notification);

        return;
      }
  }
  	@Override
	public void handleRequest(final Exchange exchange) {
  	    GroupObservationsInfo groupObservationsInfo = GroupObservationsInfo.getInstance();
  	  if(groupObservationsInfo.isOngoingGroupObservation(this.getURI())) {  		  
  		  exchange.getRequest().getOptions().setOscore(Bytes.EMPTY);
  	  }

		super.handleRequest(exchange);
	}

  private boolean shouldGroupObservationStart(){
    return this.getObserverCount() >= 0;
  }
 
  private void setUpGroupObservation(CoapExchange exchange){
    String resourceUri = this.getURI();
    GroupObservationsInfo groupObservationsInfo = GroupObservationsInfo.getInstance();

      LOGGER.debug("Group observation for {} starting", resourceUri);

      synchronized (groupObservationsInfo) {
        // If setup already in progress
        if (groupObservationsInfo.isGroupObservationSetupInProgress(resourceUri)) {
          // Phantom requests should pass through to establish observe relation
          if (exchange.advanced().isPhantomRequest()) {
            LOGGER.debug("Phantom request for {} - allowing delivery during setup", resourceUri);
            // Don't return - let the phantom be delivered to the resource
          } else {
            // Regular client during setup - add as pending client for informative response
            groupObservationsInfo.addPendingClient(resourceUri, exchange);
            LOGGER.debug("Group observation setup in progress for {}, added client as pending", resourceUri);
              LOGGER.debug("Returning early: setup in progress, added client as pending. Injection will not occur.");
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
          LOGGER.debug("Returning early: detected phantom request after setup. Injection will not occur.");
        return;
      } else {
        // Step 5: Allocate multicast token T
        Token multicastToken = groupObservationsInfo.allocateMulticastToken(resourceUri);
        LOGGER.debug("Allocated multicast token {} for group observation on {}", multicastToken, resourceUri);

        // Step 6-7: Create and deliver phantom request
        // Note: First client is NOT added to pending clients - it will continue
        // to handleGET after phantom completes and receive informative response there
        final Request phantomRequest = groupObservationsInfo.createPhantomRequest(this, multicastToken, exchange, true);
        
        // The phantom's observe relation will be established during response handling
        // via ObserveRelation.onResponse()
        // Deliver phantom request through the CoAP stack so that
        // ObserveLayer can detect it and set the phantom request flag
        LOGGER.debug("Injecting phantom request into stack for {} with token {}", resourceUri, multicastToken);
        CoapEndpoint endpoint = (CoapEndpoint) exchange.advanced().getEndpoint();                                    
                  
        endpoint.sendRequest(phantomRequest);
        
        LOGGER.debug("Return from injection phantom request into stack for {} with token {}", resourceUri, multicastToken);

        // After phantom is delivered, the group observation is established.
        // Now continue to let the first client's request reach handleGET,
      }
  }
  class UpdateTask extends TimerTask {
        private int notificationCount = 0;

        @Override
        public void run() {
        	int prev = content;
            content++;
            LOGGER.info("{} -> {}", prev, content);
            changed(); // notify all observers

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

