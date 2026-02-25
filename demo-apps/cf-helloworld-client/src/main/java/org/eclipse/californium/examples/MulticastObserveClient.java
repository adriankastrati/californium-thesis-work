package org.eclipse.californium.examples;


import java.io.File;
import java.io.IOException;
import java.net.BindException;
import java.net.Inet4Address;
import java.net.InetSocketAddress;
import java.net.SocketAddress;

import org.eclipse.californium.core.CoapClient;
import org.eclipse.californium.core.CoapResponse;
import org.eclipse.californium.core.Utils;
import org.eclipse.californium.core.coap.MediaTypeRegistry;
import org.eclipse.californium.core.CoapHandler;
import org.eclipse.californium.core.CoapObserveRelation;
import org.eclipse.californium.core.config.CoapConfig;
import org.eclipse.californium.core.network.CoapEndpoint;
import org.eclipse.californium.core.network.CoapEndpointGroupObserver;
import org.eclipse.californium.core.observe.GroupObservationsInfo;
import org.eclipse.californium.core.observe.ObservationInfo;
import org.eclipse.californium.elements.UDPConnector;
import org.eclipse.californium.elements.UdpMulticastConnector;
import org.eclipse.californium.elements.config.Configuration;
import org.eclipse.californium.elements.config.UdpConfig;
import org.eclipse.californium.elements.util.NetworkInterfacesUtil;
import org.eclipse.californium.core.network.CoapEndpoint.Builder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class MulticastObserveClient{
			
  private static final Logger LOGGER = LoggerFactory.getLogger(MulticastObserveClient.class);
	private static final File CONFIG_FILE = new File("CaliforniumMulticast3.properties");
	/**
	 * Header for configuration.
	 */
	  private static final String CONFIG_HEADER = "Californium CoAP Properties file for Multicast Client";

		private static class ObserveHandler implements CoapHandler {
			
			private int notificationCount = 0;
		    private final int targetCount;
		    
		    public ObserveHandler(int targetCount) {
		        this.targetCount = targetCount;
		    }
		    
		    public synchronized void waitForNotifications() {
		        while (notificationCount < targetCount) {
		        	try {
						this.wait();
					} catch (InterruptedException e) {
						e.printStackTrace();
					}
		        }
		    }
		    
		    @Override
		    public synchronized void onLoad(CoapResponse response) {
		        System.out.println("Response arrives at onload()");
            System.out.println(Utils.prettyPrint(response));
            // if (response.getOptions().isContentFormat(MediaTypeRegistry.APPLICATION_INFORMATIVE_RESPONSE_CBOR)){

            //   ObservationInfo observationInfo = ObservationInfo.fromCbor(response.getPayload());
            //   System.out.println(observationInfo.toString());
              
            //   InetSocketAddress socketAddress = observationInfo.getTpInfo().getTpiClient().toSocketAddress();
             
            //   UdpMulticastConnector.Builder endpointBuilder = new UdpMulticastConnector.Builder()
            //     .setLocalAddress(socketAddress)
            //     .addMulticastGroup(socketAddress.getAddress());
          
            //   Inet4Address ipv4 = NetworkInterfacesUtil.getMulticastInterfaceIpv4();
            //   Configuration config = Configuration.getStandard();

            //   UDPConnector udpConnector = new UDPConnector(new InetSocketAddress(ipv4, 61616), config);

            //   createReceiver(endpointBuilder, udpConnector);
            //     /*
            //       builder = new UdpMulticastConnector.Builder().setLocalAddress(multicastIP, multicastPort)
            //       .addMulticastGroup(multicastIP, networkInterface);
            //       createReceiver(builder, udpConnector);
            //     */

            //   //endpointBuilder = new UdpMulticastConnector.Builder().addMulticastGroup(socketAddress.getAddress())

            //   CoapClient client = new CoapClient();
            //   // client.setEndpoint(endpointBuilder.build());

            // }
		        // notificationCount++;
		        // notifyAll();
		    }

			@Override
			public void onError() {
				System.out.println("error when recieving");
			}
		
		}
		

		static {
			CoapConfig.register();
			UdpConfig.register();
		}

    

		/*
		 * Application entry point.
		 */	
		public static void main(String requestedURI, int timeout) {
			try {
				CoapClient ObserveClient = new CoapClient(requestedURI);
			
				ObserveHandler handler = new ObserveHandler(4);
				CoapObserveRelation relation = ObserveClient.observeAndWait(handler);
								
				handler.waitForNotifications();
				relation.reactiveCancel();
				
			} catch (Exception e) {

				// TODO Auto-generated catch block
				System.out.println(e.toString());
			}
			
			
		}
    private static void createReceiver(UdpMulticastConnector.Builder builder, UDPConnector connector) {
		UdpMulticastConnector multicastConnector = builder.setMulticastReceiver(true).build();
		try {
			multicastConnector.start();
		} catch (BindException ex) {
			// binding to multicast seems to fail on windows
			if (builder.getLocalAddress().getAddress().isMulticastAddress()) {
				int port = builder.getLocalAddress().getPort();
				builder.setLocalPort(port);
				multicastConnector = builder.build();
				try {
					multicastConnector.start();
				} catch (IOException e) {
					e.printStackTrace();
					multicastConnector = null;
				}
			} else {
				ex.printStackTrace();
				multicastConnector = null;
			}
		} catch (IOException e) {
			e.printStackTrace();
			multicastConnector = null;
		}
		if (multicastConnector != null && connector != null) {
			connector.addMulticastReceiver(multicastConnector);
		}
	}
}
