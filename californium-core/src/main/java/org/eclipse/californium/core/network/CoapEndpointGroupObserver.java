  /**
   * Builder for CoapEndpointGroupObserver.
   */
  public static class Builder extends CoapEndpoint.Builder {
    @Override
    public CoapEndpointGroupObserver build() {
      // Use protected getters from CoapEndpoint.Builder to access parameters
      return new CoapEndpointGroupObserver(
        getConnector(),
        getConfig(),
        getTokenGenerator(),
        getObservationStore(),
        getExchangeStore(),
        getEndpointContextMatcher(),
        getSerializer(),
        getParser(),
        getTag(),
        getCoapStackFactory(),
        getCustomStackArgument()
      );
    }
  }
/*******************************************************************************
 * Copyright (c) 2015 Wireless Networks Group, UPC Barcelona and i2CAT.
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
 *    August Betzler    – CoCoA implementation
 *    Matthias Kovatsch - Embedding of CoCoA in Californium
 ******************************************************************************/

package org.eclipse.californium.core.network;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiConsumer;

import org.eclipse.californium.core.coap.CoAP;
import org.eclipse.californium.core.coap.CoAP.Type;
import org.eclipse.californium.core.coap.CoAPMessageFormatException;
import org.eclipse.californium.core.coap.EmptyMessage;
import org.eclipse.californium.core.coap.Message;
import org.eclipse.californium.core.coap.Message.OffloadMode;
import org.eclipse.californium.core.coap.MessageFormatException;
import org.eclipse.californium.core.coap.Request;
import org.eclipse.californium.core.coap.Response;
import org.eclipse.californium.core.coap.Token;
import org.eclipse.californium.core.coap.option.NoResponseOption;
import org.eclipse.californium.core.coap.option.OptionRegistry;
import org.eclipse.californium.core.coap.option.StandardOptionRegistry;
import org.eclipse.californium.core.config.CoapConfig;
import org.eclipse.californium.core.network.EndpointManager.ClientMessageDeliverer;
import org.eclipse.californium.core.network.Exchange.Origin;
import org.eclipse.californium.core.network.deduplication.NoDeduplicator;
import org.eclipse.californium.core.network.interceptors.MalformedMessageInterceptor;
import org.eclipse.californium.core.network.interceptors.MessageInterceptor;
import org.eclipse.californium.core.network.interceptors.NoResponseInterceptor;
import org.eclipse.californium.core.network.serialization.DataParser;
import org.eclipse.californium.core.network.serialization.DataSerializer;
import org.eclipse.californium.core.network.serialization.TcpDataParser;
import org.eclipse.californium.core.network.serialization.TcpDataSerializer;
import org.eclipse.californium.core.network.serialization.UdpDataParser;
import org.eclipse.californium.core.network.serialization.UdpDataSerializer;
import org.eclipse.californium.core.network.stack.BlockwiseLayer;
import org.eclipse.californium.core.network.stack.CoapStack;
import org.eclipse.californium.core.network.stack.CoapTcpStack;
import org.eclipse.californium.core.network.stack.CoapUdpStack;
import org.eclipse.californium.core.network.stack.ExchangeCleanupLayer;
import org.eclipse.californium.core.network.stack.ObserveLayer;
import org.eclipse.californium.core.network.stack.ReliabilityLayer;
import org.eclipse.californium.core.observe.InMemoryObservationStore;
import org.eclipse.californium.core.observe.ObservationStore;
import org.eclipse.californium.core.server.MessageDeliverer;
import org.eclipse.californium.elements.Connector;
import org.eclipse.californium.elements.EndpointContext;
import org.eclipse.californium.elements.EndpointContextMatcher;
import org.eclipse.californium.elements.EndpointIdentityResolver;
import org.eclipse.californium.elements.MessageCallback;
import org.eclipse.californium.elements.RawData;
import org.eclipse.californium.elements.RawDataChannel;
import org.eclipse.californium.elements.UDPConnector;
import org.eclipse.californium.elements.UdpMulticastConnector;
import org.eclipse.californium.elements.auth.ApplicationAuthorizer;
import org.eclipse.californium.elements.config.Configuration;
import org.eclipse.californium.elements.util.ClockUtil;
import org.eclipse.californium.elements.util.DaemonThreadFactory;
import org.eclipse.californium.elements.util.ExecutorsUtil;
import org.eclipse.californium.elements.util.ProtocolScheduledExecutorService;
import org.eclipse.californium.elements.util.StringUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


/**
 * An abstract class representing the current transmissions and parameters for a
 * specific remote endpoint.
 * 
 * @since 3.0 (moved and redesigned)
 */
public abstract class CoapEndpointGroupObserver extends CoapEndpoint{

  /** A store containing data about message exchanges. */
  private MessageExchangeStore exchangeStore;

  /** A Store containing data about observation. */
  private ObservationStore observationStore;
  
  public CoapEndpointGroupObserver(Connector connector, Configuration config, TokenGenerator tokenGenerator,
      ObservationStore store, MessageExchangeStore exchangeStore, EndpointContextMatcher endpointContextMatcher,
      DataSerializer serializer, DataParser parser, String loggingTag, CoapStackFactory coapStackFactory,
      Object customStackArgument) {
    super(connector, config, tokenGenerator, store, exchangeStore, endpointContextMatcher, serializer, parser, loggingTag,
        coapStackFactory, customStackArgument);   
  }
  /**
   * Get the current MessageExchangeStore.
   * @return the MessageExchangeStore
   */
  public MessageExchangeStore getExchangeStore() {
    return this.exchangeStore;
  }

  /**
   * Set the MessageExchangeStore.
   * @param exchangeStore the new MessageExchangeStore
   */
  public void setExchangeStore(MessageExchangeStore exchangeStore) {
    this.exchangeStore = exchangeStore;
  }

  /**
   * Get the current ObservationStore.
   * @return the ObservationStore
   */

  public ObservationStore getObservationStore() {
    return this.observationStore;
  }

  /**
   * Set the ObservationStore.
   * @param observationStore the new ObservationStore
   */
  public void setObservationStore(ObservationStore observationStore) {
    this.observationStore = observationStore;
  }
  public static class Builder extends CoapEndpoint.Builder {
      @Override
      public CoapEndpointGroupObserver build() {
        CoapEndpoint coapEndpoint = super.build();
          // Use fields from the builder (config, connector, etc.)
          return new CoapEndpointGroupObserver(
              coapEndpoint.getConnector(), coapEndpoint.getConfig(), coapEndpoint.tokenGenerator, observationStore, exchangeStore,
              endpointContextMatcher, serializer, parser, tag, coapStackFactory, customStackArgument
          );
      }
  }  
}
