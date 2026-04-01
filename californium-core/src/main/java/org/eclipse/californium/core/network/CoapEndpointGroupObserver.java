/*******************************************************************************
 * CoapEndpoint extension exposing exchange and observation stores
 * for group observation support.
 ******************************************************************************/
package org.eclipse.californium.core.network;

import org.eclipse.californium.core.network.serialization.DataParser;
import org.eclipse.californium.core.network.serialization.DataSerializer;
import org.eclipse.californium.core.observe.ObservationStore;
import org.eclipse.californium.elements.Connector;
import org.eclipse.californium.elements.EndpointContextMatcher;
import org.eclipse.californium.elements.config.Configuration;

/**
 * Abstract CoapEndpoint subclass that exposes the exchange and observation
 * stores for use in multicast group observation scenarios.
 */
public abstract class CoapEndpointGroupObserver extends CoapEndpoint {

	private MessageExchangeStore exchangeStore;
	private ObservationStore observationStore;

	public CoapEndpointGroupObserver(Connector connector, Configuration config, TokenGenerator tokenGenerator,
			ObservationStore store, MessageExchangeStore exchangeStore, EndpointContextMatcher endpointContextMatcher,
			DataSerializer serializer, DataParser parser, String loggingTag, CoapStackFactory coapStackFactory,
			Object customStackArgument) {
		super(connector, config, tokenGenerator, store, exchangeStore, endpointContextMatcher, serializer, parser,
				loggingTag, coapStackFactory, customStackArgument);
	}

	public MessageExchangeStore getExchangeStore() {
		return this.exchangeStore;
	}

	public void setExchangeStore(MessageExchangeStore exchangeStore) {
		this.exchangeStore = exchangeStore;
	}

	public ObservationStore getObservationStore() {
		return this.observationStore;
	}
}
