/*******************************************************************************

 ******************************************************************************/
package org.eclipse.californium.core.observe;

import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.eclipse.californium.core.CoapExchange;
import org.eclipse.californium.core.coap.CoAP;
import org.eclipse.californium.core.coap.CoAP.Type;
import org.eclipse.californium.core.coap.Request;
import org.eclipse.californium.core.coap.Token;
import org.eclipse.californium.core.network.Exchange;
import org.eclipse.californium.core.network.Exchange.Origin;
import org.eclipse.californium.core.network.RandomTokenGenerator;
import org.eclipse.californium.core.network.TokenGenerator;
import org.eclipse.californium.core.network.TokenGenerator.Scope;
import org.eclipse.californium.core.server.resources.Resource;
import org.eclipse.californium.elements.AddressEndpointContext;
import org.eclipse.californium.elements.config.Configuration;

/**
 * Assuming that multicast notifications will all be sent to the same address for each group observation, 
 *  Singleton Data Structure to hold group observation state of resources at server
 */
public class GroupObservationsInfo {

	/**
	 * A map with key the URI path of the resource supporting group observation, and with value a boolean flag. 
	 * The flag has value true only while the server is setting up a group observation on the resource identified by that URI path.
	 */
	private Map<String, Boolean> ongoingGroupObservationSetup;

	/* 
	 * A map with key the URI path of the resource supporting group observation, and with value a Token value. 
	 * The latter is the value of a Token of a phantom request that the server has just sent to itself and 
	 * is waiting to receive and process for starting the group observation.
	 * A new element is added to the map once extracted from freeMulticastNotificationTokens 
	 * and upon preparing a phantom request with that token value. 
	 * Once started the group observation, the entry map is removed.
	 */
	private Map<String, Token> pendingMulticastNotificationTokens;
	
	/**
	 * a map with key the URI path of the resource supporting group observation, 
	 * and with value an object including all the information used to describe the group observation 
	 * and to compose the error informative response
	 */
	private Map<String, ObservationInfo> ongoingGroupObservations;

	/**
	 * Pending clients waiting for informative response (5.03).
	 * Key is resource URI, value is list of client exchanges.
	 */
	private Map<String, List<CoapExchange>> pendingClients;
  
  private final int GRP_PORT = 61616;

	/**
	 * Multicast address where notifications are sent.
	 */
	private volatile InetSocketAddress multicastAddress =  new InetSocketAddress(CoAP.MULTICAST_IPV4, GRP_PORT);// With custom multicast port
  
  public InetSocketAddress getMulticastAddress(){
    return multicastAddress;
  }
  /**
   * 
   * @param uri to be checked for ongoing groupObservation
   * @return if there is a ongoing group observation of uri
   */
  public boolean isOngoingGroupObservation(String uri){
    return this.ongoingGroupObservations.containsKey(uri);
  }

  
	private static GroupObservationsInfo instance;

	private final TokenGenerator tokenGenerator;

	private GroupObservationsInfo(){
		this.ongoingGroupObservationSetup = new ConcurrentHashMap<>();
		this.pendingMulticastNotificationTokens = new ConcurrentHashMap<>();
		this.ongoingGroupObservations = new ConcurrentHashMap<>();
		this.pendingClients = new ConcurrentHashMap<>();
		this.tokenGenerator = new RandomTokenGenerator(Configuration.createStandardWithoutFile());
    }

    public static synchronized GroupObservationsInfo getInstance() {
        if (instance == null) {
            instance = new GroupObservationsInfo();
        }
        
        return instance;
    }

    /**
     * Reset the singleton instance. Use this for testing to clear all state.
     */
    public static synchronized void init() {
        instance = null;
    }
    
    public boolean isTokenPendingProcess(Token token) {
    	return pendingMulticastNotificationTokens.containsValue(token);
    }

	/**
	 * Mark whether a group observation setup is ongoing for the URI path.
	 * 
	 * @param uriPath resource URI path
	 * @param inProgress {@code true} if setup ongoing
	 */
	public void setGroupObservationSetupInProgress(String uriPath, boolean inProgress) {
    if (uriPath != null) {
        ongoingGroupObservationSetup.put(uriPath, inProgress);
    }
  }

	/**
	 * Check whether a group observation setup is ongoing for the URI path.
	 * 
	 * @param uri resource URI path
	 * @return {@code true} if setup ongoing
	 */
	public boolean isGroupObservationSetupInProgress(String uri) {
    return Boolean.TRUE.equals(ongoingGroupObservationSetup.get(uri));
}

	/**
	 * Allocate a token for multicast notifications for a resource URI and mark it as pending.
	 * 
	 * @param uriPath resource URI path
	 * @return allocated token
	 */
	public synchronized Token allocateMulticastToken(String uriPath) {
		if (uriPath == null) {
			return null;
		}
		Token existing = pendingMulticastNotificationTokens.get(uriPath);
		if (existing != null) {
			return existing;
		}
		Token token = getFreeMulticastNotificationToken();
		pendingMulticastNotificationTokens.put(uriPath, token);
		return token;
	}

	/**
	 * Get pending token for a resource URI.
	 * 
	 * @param uriPath resource URI path
	 * @return pending token or {@code null}
	 */
	public Token getPendingToken(String uriPath) {
		return pendingMulticastNotificationTokens.get(uriPath);
	}

	/**
	 * Start a group observation for a URI.
	 * 
	 * @param uriPath resource URI path
	 * @param info observation info
	 */
	public void startGroupObservation(String uriPath, ObservationInfo info) {
		if (uriPath == null || info == null) {
			return;
		}
		pendingMulticastNotificationTokens.remove(uriPath);
		ongoingGroupObservations.put(uriPath, info);
	}

	/**
	 * Get observation info for a URI.
	 * 
	 * @param uriPath resource URI path
	 * @return observation info or {@code null}
	 */
	public ObservationInfo getGroupObservationInfo(String uriPath) {
		return ongoingGroupObservations.get(uriPath);
	}

	/**
	 * Add a pending client exchange that is waiting for an informative response.
	 * 
	 * @param uriPath resource URI path
	 * @param exchange the client'f exchange
	 */
	public void addPendingClient(String uriPath, CoapExchange exchange) {
		if (uriPath == null || exchange == null) {
			return;
		}
		pendingClients.computeIfAbsent(uriPath, k -> new ArrayList<>()).add(exchange);
	}

	/**
	 * Get and remove all pending client exchanges for a resource.
	 * 
	 * @param uriPath resource URI path
	 * @return list of pending exchanges, or empty list
	 */
	public List<CoapExchange> removePendingClients(String uriPath) {
		List<CoapExchange> clients = pendingClients.remove(uriPath);
		return clients != null ? clients : new ArrayList<>();
	}

	/**
	 * Get pending client CoapExchanges for a resource (without removing).
	 * 
	 * @param uriPath resource URI path
	 * @return list of pending CoapExchanges, or empty list
	 */
	public List<CoapExchange> getPendingClients(String uriPath) {
		List<CoapExchange> clients = pendingClients.get(uriPath);
		return clients != null ? new ArrayList<>(clients) : new ArrayList<>();
	}

	/**
	 * Remove group observation for a URI.
	 * 
	 * @param uriPath resource URI path
	 * @return removed observation info
	 */
public ObservationInfo removeGroupObservation(String uriPath) {
    if (uriPath == null) {
        return null;
    }
    pendingMulticastNotificationTokens.remove(uriPath);
    return ongoingGroupObservations.remove(uriPath);
  }

  /**
  * Available token values are practically enforced as the complement of the
  * union of pending and ongoing tokens.
  * @return 
  */

	private Token getFreeMulticastNotificationToken() {
		Token token;
		do {
			token = tokenGenerator.createToken(Scope.LONG_TERM);
		} while (isTokenInUse(token));
		return token;
	}

	private boolean isTokenInUse(Token token) {
		if (token == null) {
			return false;
		}
		if (pendingMulticastNotificationTokens.containsValue(token)) {
			return true;
		}
		for (ObservationInfo info : ongoingGroupObservations.values()) {
			if (info != null && token.equals(info.getToken())) {
				return true;
			}
		}
		return false;
	}

  	/**
	 * Creates a phantom request and CoapExchange for multicast group observation.
	 * 
	 * The phantom request is a self-generated observe request that establishes
	 * the group observation. Its source context is set to the multicast group
	 * address so that responses (notifications) will be sent to the multicast group.
	 * 
	 * @param resource the resource to observe
	 * @param multicastToken the token T allocated for multicast notifications
	 * @param triggeringCoapExchange the original client CoapExchange that triggered this setup
	 * @return the phantom CoapExchange ready to be delivered
	 */
	public Request createPhantomRequest(Resource resource, Token multicastToken, CoapExchange triggeringCoapExchange) {
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
		
		InetSocketAddress localAddress = triggeringCoapExchange.advanced().getEndpoint().getAddress();
		
		// Set source context to the server's own address.
		// This allows ObserveLayer.isPhantomRequest() to recognize this as a
		// phantom request (source == server's own address). The ObserveLayer
		// will then change the source to the multicast address.
		phantomRequest.setDestinationContext(new AddressEndpointContext(localAddress));
		phantomRequest.setSourceContext(new AddressEndpointContext(localAddress));
		phantomRequest.setMultiResponse(true);

		// Step 7: Create the CoapExchange for server-side processing
		// Use Origin.REMOTE because the server should see this as an incoming request
		//Exchange phantomExchange = new Exchange(phantomRequest, localAddress, Origin.REMOTE, triggeringExchange.getEndpoint().getExecutor());

		// Do NOT manually set phantomExchange.setPhantomRequest(true) here.
		// The ObserveLayer will detect this as a phantom request when it
		// traverses the stack and set the flag accordingly.
		
		// Set endpoint from triggering exchange if available
		//if (triggeringExchange.getEndpoint() != null) {
			//phantomExchange.setEndpoint(triggeringExchange.getEndpoint());
		//}

		return phantomRequest;
	}
}
