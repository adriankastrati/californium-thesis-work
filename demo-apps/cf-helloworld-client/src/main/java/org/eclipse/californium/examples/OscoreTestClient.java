package org.eclipse.californium.examples;


	import java.net.InetSocketAddress;
import java.util.Random;
	import java.util.concurrent.CountDownLatch;
	import java.util.concurrent.TimeUnit;

	//import org.eclipse.californium.TestTools;
	import org.eclipse.californium.core.CoapClient;
import org.eclipse.californium.core.CoapHandler;
import org.eclipse.californium.core.CoapObserveRelation;
	import org.eclipse.californium.core.CoapResponse;
import org.eclipse.californium.core.Utils;
import org.eclipse.californium.core.coap.CoAP.Code;
	import org.eclipse.californium.core.coap.MediaTypeRegistry;
	import org.eclipse.californium.core.coap.Request;
	import org.eclipse.californium.core.coap.Response;
import org.eclipse.californium.core.config.CoapConfig;
import org.eclipse.californium.core.network.CoapEndpoint;
import org.eclipse.californium.core.network.Endpoint;
	import org.eclipse.californium.cose.AlgorithmID;
import org.eclipse.californium.elements.config.Configuration;
import org.eclipse.californium.elements.config.UdpConfig;
import org.eclipse.californium.elements.util.Bytes;
import org.eclipse.californium.oscore.HashMapCtxDB;
import org.eclipse.californium.oscore.OSCoreCoapStackFactory;
import org.eclipse.californium.oscore.OSCoreCtx;
import org.eclipse.californium.oscore.OSException;

	public class OscoreTestClient {
		static {
		    CoapConfig.register();
		    UdpConfig.register();
		}
	    // OSCORE context information — must match server
	    private final static HashMapCtxDB dbClient = new HashMapCtxDB();
	    private final static AlgorithmID alg = AlgorithmID.AES_CCM_16_64_128;
	    private final static AlgorithmID kdf = AlgorithmID.HKDF_HMAC_SHA_256;
	    private final static byte[] master_secret = { 0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07, 0x08, 0x09, 0x0A, 0x0B,
	            0x0C, 0x0D, 0x0E, 0x0F, 0x10 };
	    private final static byte[] master_salt = { (byte) 0x9e, (byte) 0x7c, (byte) 0xa9, (byte) 0x22, (byte) 0x23,
	            (byte) 0x78, (byte) 0x63, (byte) 0x40 };
	    private final static int MAX_UNFRAGMENTED_SIZE = 4096;

	    private final Endpoint serverEndpoint;
	    private CoapClient client;

	    public OscoreTestClient(Endpoint serverEndpoint) {
	        this.serverEndpoint = serverEndpoint;
	    }

	    /**
	     * Sets up the OSCORE context for the client.
	     * Client sender ID = empty, recipient ID = 0x01 (matches server's sender ID)
	     */
	    public void setClientContext() {
	        byte[] sid = new byte[0];
	        byte[] rid = new byte[] { 0x01 };
	        
	        InetSocketAddress addr = serverEndpoint.getAddress();
	        String uri = "coap://" + addr.getHostString() + ":" + addr.getPort();
	        
	        System.out.println("Registering OSCORE context for: " + uri); // debug

	        try {
	            OSCoreCtx ctx = new OSCoreCtx(master_secret, true, alg, sid, rid, kdf, 32, master_salt, null,
	                    MAX_UNFRAGMENTED_SIZE);
	            dbClient.addContext(uri, ctx);
	        } catch (OSException e) {
	            System.err.println("Failed to set client OSCORE Context information!");
	        }
	    }
	    /**
	     * Creates an OSCORE-protected CoAP request to the server
	     */
	    private Request createRequest(Code code, String resourceUri) {
	        InetSocketAddress addr = serverEndpoint.getAddress();
	        String host = addr.getHostString();
	        int port = addr.getPort();
	        
	        // resourceUri should be like "/oscore/observe2"
	        String serverUri = "coap://" + host + ":" + port + resourceUri;
	        System.out.println("Connecting to: " + serverUri);
	        
	        Request r = new Request(code);
	        r.setConfirmable(true);
	        r.setURI(serverUri);
	        r.getOptions().setOscore(Bytes.EMPTY);
	        return r;
	    }
	    

	    /**
	     * Registers an Observe relation on /oscore/observe2, waits for 2
	     * notifications, then deregisters and verifies the final response.
	     */
	    public void observe() throws InterruptedException {
	        String resourceUri = "/oscore/observe2";
	        client = new CoapClient();
	        
	        client.setEndpoint(this.serverEndpoint);
	        client.setURI(resourceUri);
	        // Latch to wait for 2 notifications
	        CountDownLatch latch = new CountDownLatch(2);
	        final CoapResponse[] lastResponse = new CoapResponse[1];


	        // Register observe
	        Request observeRequest = createRequest(Code.GET, resourceUri);
	        observeRequest.setObserve();

	        ObserveHandler handler = new ObserveHandler(4);
			CoapObserveRelation relation = client.observe(observeRequest, handler);

			handler.waitForNotifications();
			
	        // Deregister observe
	        Request deregisterRequest = createRequest(Code.GET, resourceUri);
	        deregisterRequest.getOptions().setObserve(1); // Observe=1 means cancel
	        deregisterRequest.send();

	        Response finalResponse = deregisterRequest.waitForResponse(1000);
	        if (finalResponse != null) {
	            System.out.println("Final response: " + finalResponse.getPayloadString());
	            System.out.println("Has Observe option: " + finalResponse.getOptions().hasObserve());
	        }

	        System.out.println("Current relation value: " + relation.getCurrent().getResponseText());
	        client.shutdown();
	    }

	    public static void main(String[] args) throws InterruptedException {
	    	Configuration config = Configuration.getStandard();
	    	OscoreServerTest server = new OscoreServerTest();
	        server.start();
	        Thread.sleep(500);

	        OscoreTestClient client = new OscoreTestClient(server.getServerEndpoint());

	        client.setClientContext();
	        client.observe();
	        server.stop();
	    }
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
								// TODO Auto-generated catch block
								e.printStackTrace();
							}
				        }
				    }
				    
				    @Override
				    public synchronized void onLoad(CoapResponse response) {
				        System.out.println(Utils.prettyPrint(response));
				        notificationCount++;
				        notifyAll();
		
				    }
		
					@Override
					public void onError() {
						System.out.println("error when recieving");
					}
				
				}
}