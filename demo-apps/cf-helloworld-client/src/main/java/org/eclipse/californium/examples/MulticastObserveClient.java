package org.eclipse.californium.examples;

import java.io.IOException;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import org.eclipse.californium.core.CoapClient;
import org.eclipse.californium.core.CoapHandler;
import org.eclipse.californium.core.CoapObserveRelation;
import org.eclipse.californium.core.CoapResponse;
import org.eclipse.californium.core.Utils;
import org.eclipse.californium.core.coap.CoAP.Type;
import org.eclipse.californium.core.coap.MediaTypeRegistry;
import org.eclipse.californium.core.coap.Request;
import org.eclipse.californium.core.coap.Token;
import org.eclipse.californium.core.config.CoapConfig;
import org.eclipse.californium.core.network.CoapEndpoint;
import org.eclipse.californium.core.observe.ObservationInfo;
import org.eclipse.californium.elements.AddressEndpointContext;
import org.eclipse.californium.elements.UDPConnector;
import org.eclipse.californium.elements.UdpMulticastConnector;
import org.eclipse.californium.elements.config.Configuration;
import org.eclipse.californium.elements.config.UdpConfig;
import org.eclipse.californium.elements.util.NetworkInterfacesUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class MulticastObserveClient {

  static {
    CoapConfig.register();
    UdpConfig.register();
  }
  private static final Logger LOGGER = LoggerFactory.getLogger(MulticastObserveClient.class);

  private static UDPConnector udpConnector;
  private static UdpMulticastConnector multicastReceiver;
  private static CoapEndpoint endpoint;
  private static CoapClient multicastClient;
  private static boolean receiverReady = false;

  private static class MulticastObserveHandler implements CoapHandler {

    private int notificationCount = 0;
    private final int targetCount;

    public MulticastObserveHandler(int targetCount) {
      this.targetCount = targetCount;
    }

    public synchronized void waitForNotifications() {
      while (notificationCount < targetCount) {
        try {
          this.wait();
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
          return;
        }
      }
    }

    @Override
    public void onLoad(CoapResponse response) {
      LOGGER.info("onLoad(): \n {}", Utils.prettyPrint(response));

      // First response might be an "informative response" containing ObservationInfo (group+token).
      if (response.getOptions().isContentFormat(MediaTypeRegistry.APPLICATION_INFORMATIVE_RESPONSE_CBOR)) {
        LOGGER.info("Got APPLICATION_INFORMATIVE_RESPONSE_CBOR (ObservationInfo)");
        ObservationInfo info = ObservationInfo.fromCbor(response.getPayload());

        InetSocketAddress groupSock = info.getTpInfo().getTpiClient().toSocketAddress();
        InetAddress groupAddr = groupSock.getAddress();
        int groupPort = groupSock.getPort();
        Token multicastToken = info.getToken();

        LOGGER.info("Multicast group: {} : {}", groupAddr, groupPort);
        LOGGER.info("Token: {}", multicastToken.getAsString());
        LOGGER.info("Requested URI: {}", info.getTpInfo().getTpiServer().toString());

        if (!receiverReady) {
          try {
            // Build receiver stack (join group + bind port) - MUST come first to create multicastClient
            LOGGER.info("Setting up multicast receiver...");
            setupMulticastReceiverOnce(groupAddr, groupPort, Configuration.createStandardWithoutFile(), multicastToken);
            LOGGER.info("Multicast receiver activated.");
            
            // Now create phantom request and register observe relation
            Request phantomRequest = createPhantomRequest(multicastToken, info.getTpInfo().getTpiServer().toString(), groupAddr, groupPort);
            LOGGER.info("Created PhantomRequest with token: {}", multicastToken);
            
            CoapObserveRelation phantomRelation = multicastClient.observe(phantomRequest, this);
            LOGGER.info("Registered phantom observe relation.");

            receiverReady = true;
          } catch (IOException e) {
            LOGGER.error("Failed to activate multicast receiver", e);
          }
        } else {
          LOGGER.info("Multicast receiver already active (ignoring extra informative response).");
        }

        return;
      }

      // Otherwise it's a normal notification payload
      synchronized (this) {
        notificationCount++;
        notifyAll();
      }
    }

    @Override
    public void onError() {
      LOGGER.error("Error receiving multicast notification");
    }
  }

  /**
   * Creates a phantom request to register an observe relation for receiving multicast notifications.
   */
  private static Request createPhantomRequest(Token multicastToken, String requestedURI, InetAddress groupAddr, int groupPort) {
    Request phantomRequest = Request.newGet();
    phantomRequest.setToken(multicastToken);
    phantomRequest.setObserve();
    phantomRequest.setDestinationContext(new AddressEndpointContext(new InetSocketAddress(groupAddr, groupPort)));
    phantomRequest.setURI(requestedURI);
    phantomRequest.setType(Type.NON);
    phantomRequest.setShouldSend(false);
    LOGGER.debug("Created phantom request: {}", phantomRequest);
    return phantomRequest;
  }

  public static void setupMulticastReceiverOnce(InetAddress groupAddr, int port, Configuration config, Token multicastToken) throws IOException {
    NetworkInterface ni = NetworkInterfacesUtil.getMulticastInterface();
    if (ni == null) {
      throw new IOException("No multicast network interface found");
    }

    // Get IPv4 address on that interface (prevents binding to ::)
    Inet4Address ipv4 = NetworkInterfacesUtil.getMulticastInterfaceIpv4();
    if (ipv4 == null) {
      throw new IOException("No IPv4 address found on multicast interface " + ni.getDisplayName());
    }

    InetSocketAddress bind = new InetSocketAddress(ipv4, 0);
    udpConnector = new UDPConnector(bind, config);
    udpConnector.setReuseAddress(true);
    LOGGER.info("Binding UDPConnector to {} (ephemeral port) on {}", ipv4, ni.getDisplayName());
    
    UdpMulticastConnector.Builder mcBuilder = new UdpMulticastConnector.Builder()
        .setConfiguration(config)
        .setMulticastReceiver(true)
        .setLocalAddress(groupAddr, port)
        .addMulticastGroup(groupAddr, ni);
    
    multicastReceiver = mcBuilder.build();

    try {
      multicastReceiver.start();
      LOGGER.info("Multicast receiver started on {} port {}", groupAddr, port);
    } catch (java.net.BindException ex) {
      LOGGER.warn("Bind to multicast address failed, retrying with port only: {}", ex.getMessage());
      mcBuilder = new UdpMulticastConnector.Builder()
          .setConfiguration(config)
          .setMulticastReceiver(true)
          .setLocalPort(port)
          .addMulticastGroup(groupAddr, ni);
      multicastReceiver = mcBuilder.build();
      multicastReceiver.setLoopbackMode(true);
      multicastReceiver.start();
    }

    udpConnector.addMulticastReceiver(multicastReceiver);

    endpoint = new CoapEndpoint.Builder()
        .setConfiguration(config)
        .setConnector(udpConnector)
        .build();

    endpoint.start();

    multicastClient = new CoapClient();
    multicastClient.setEndpoint(endpoint);

    LOGGER.info("Joined group {} on interface {} port {}", groupAddr, ni.getDisplayName(), port);
  }

  public static void main(String requestedURI, int timeout) {
    try {
      CoapClient observeClient = new CoapClient(requestedURI);
      MulticastObserveHandler handler = new MulticastObserveHandler(4);
      CoapObserveRelation relation = observeClient.observe(handler);
      handler.waitForNotifications();
      relation.reactiveCancel();
      observeClient.shutdown();
      if (multicastClient != null) multicastClient.shutdown();
    } catch (Exception e) {
      LOGGER.error("Error in multicast observe client", e);
    }
  }
}