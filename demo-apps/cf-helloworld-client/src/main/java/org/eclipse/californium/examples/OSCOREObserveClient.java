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
import java.security.Provider;
import java.security.Security;

import org.eclipse.californium.cose.AlgorithmID;
import org.eclipse.californium.elements.util.Bytes;
import org.eclipse.californium.elements.util.StringUtil;
import org.eclipse.californium.oscore.HashMapCtxDB;
import org.eclipse.californium.oscore.OSCoreCoapStackFactory;
import org.eclipse.californium.oscore.OSException;
import org.eclipse.californium.oscore.group.GroupCtx;
import org.eclipse.californium.oscore.group.MultiKey;

import net.i2p.crypto.eddsa.EdDSASecurityProvider;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class OSCOREObserveClient {
	/* --- OSCORE Security Context information (sender) --- */
	private final static HashMapCtxDB db = new HashMapCtxDB();
	private final static AlgorithmID alg = AlgorithmID.AES_CCM_16_64_128;
	private final static AlgorithmID kdf = AlgorithmID.HMAC_SHA_256;

	// Group OSCORE specific values for the countersignature (EdDSA)
	private final static AlgorithmID algCountersign = AlgorithmID.EDDSA;

	// Encryption algorithm for when using Group mode
	private final static AlgorithmID algGroupEnc = AlgorithmID.AES_CCM_16_64_128;

	// Algorithm for key agreement
	private final static AlgorithmID algKeyAgreement = AlgorithmID.ECDH_SS_HKDF_256;

	// test vector OSCORE draft Appendix C.1.1
	private final static byte[] master_secret = { 0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07, 0x08, 0x09, 0x0A, 0x0B,
			0x0C, 0x0D, 0x0E, 0x0F, 0x10 };
	private final static byte[] master_salt = { (byte) 0x9e, (byte) 0x7c, (byte) 0xa9, (byte) 0x22, (byte) 0x23,
			(byte) 0x78, (byte) 0x63, (byte) 0x40 };

	private static final int REPLAY_WINDOW = 32;

	private final static byte[] gm_public_key_bytes = StringUtil.hex2ByteArray(
			"A501781A636F6170733A2F2F6D79736974652E6578616D706C652E636F6D026C67726F75706D616E6167657203781A636F6170733A2F2F646F6D61696E2E6578616D706C652E6F7267041AAB9B154F08A101A4010103272006215820CDE3EFD3BC3F99C9C9EE210415C6CBA55061B5046E963B8A58C9143A61166472");
	
	// server 1
	private final static byte[] server_ID = new byte[] { 0x52 }; 
	private static byte[] server_public_key_bytes = StringUtil.hex2ByteArray(
		    "A501781A636F6170733A2F2F7365727665722E6578616D706C652E636F6D026673656E64657203781A636F6170733A2F2F636C69656E742E6578616D706C652E6F7267041A70004B4F08A101A401010327200621582077EC358C1D344E41EE0E87B8383D23A2099ACD39BDF989CE45B52E887463389B");
	private static MultiKey server_public_key = new MultiKey(server_public_key_bytes);

	
	// Sender 1
	private final static byte[] sender_1_ID = new byte[] { 0x25 };

	private final static byte[] sender_1_public_key_bytes = StringUtil.hex2ByteArray(
			"A501781B636F6170733A2F2F746573746572312E6578616D706C652E636F6D02666D796E616D6503781A636F6170733A2F2F68656C6C6F312E6578616D706C652E6F7267041A70004B4F08A101A4010103272006215820069E912B83963ACC5941B63546867DEC106E5B9051F2EE14F3BC5CC961ACD43A");
	private static MultiKey sender_1_private_key;
	private static byte[] sender_1_private_key_bytes = new byte[] { (byte) 0x64, (byte) 0x71, (byte) 0x4D, (byte) 0x41,
			(byte) 0xA2, (byte) 0x40, (byte) 0xB6, (byte) 0x1D, (byte) 0x8D, (byte) 0x82, (byte) 0x35, (byte) 0x02,
			(byte) 0x71, (byte) 0x7A, (byte) 0xB0, (byte) 0x88, (byte) 0xC9, (byte) 0xF4, (byte) 0xAF, (byte) 0x6F,
			(byte) 0xC9, (byte) 0x84, (byte) 0x45, (byte) 0x53, (byte) 0xE4, (byte) 0xAD, (byte) 0x4C, (byte) 0x42,
			(byte) 0xCC, (byte) 0x73, (byte) 0x52, (byte) 0x39 
    };
	
	// Sender 2
  private final static byte[] sender_2_ID = new byte[] { 0x77 };
  private final static byte[] sender_2_public_key_bytes= StringUtil.hex2ByteArray(
      "A501781A636F6170733A2F2F7365727665722E6578616D706C652E636F6D026673656E64657203781A636F6170733A2F2F636C69656E742E6578616D706C652E6F7267041A70004B4F08A101A4010103272006215820105B8C6A8C88019BF0C354592934130BAA8007399CC2AC3BE845884613D5BA2E");
  private static byte[] sender_2_private_key_bytes = new byte[] { 0x7B, (byte) 0xF6, 0x2F, 0x76, 0x7E, (byte) 0xD1, (byte) 0xCF, 0x4C,
      0x60, (byte) 0x91, 0x1F, (byte) 0xC4, (byte) 0x9F, (byte) 0xDF, (byte) 0xCC, (byte) 0xB9,
      (byte) 0xBD, 0x47, (byte) 0xCC, 0x7E, (byte) 0x9F, (byte) 0xAF, 0x41, (byte) 0xCB, 0x66, 0x36,
      (byte) 0x9D, 0x5C, (byte) 0x85, 0x08, (byte) 0xB2, 0x39 
    };



	private final static byte[] group_identifier = new byte[] { 0x44, 0x61, 0x6c }; // GID

	/* --- OSCORE Security Context information --- */
  static {
    CoapConfig.register();
    UdpConfig.register();
  }
  private static final Logger LOGGER = LoggerFactory.getLogger(OSCOREMulticastObserveClient.class);

  private static UDPConnector udpConnector;
  private static UdpMulticastConnector multicastReceiver;
  private static CoapEndpoint endpoint;
  private static CoapClient multicastClient;
  private static boolean receiverReady = false;

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


    private static void setupOscore(String requestURI, int sender) throws OSException {
	// Install cryptographic providers
			Provider EdDSA = new EdDSASecurityProvider();
			Security.insertProviderAt(EdDSA, 1);
			// InstallCryptoProviders.generateCounterSignKey();

      	    // Select client identity based on sender parameter
	    byte[] sender_id;
	    MultiKey sender_private_key;

	    switch (sender) {
	        case 1: // sender_1 identity (0x52)
	            sender_id = sender_1_ID;
	            sender_private_key = new MultiKey(sender_1_public_key_bytes, sender_1_private_key_bytes);
	            break;
	        case 2: // sender_2 identity (0x77)
	            sender_id = sender_2_ID;
	            sender_private_key = new MultiKey(sender_2_public_key_bytes, sender_2_private_key_bytes);
	            break;
	        default:
	            throw new IllegalArgumentException("Unknown sender ID: " + sender);
	    }

			// If OSCORE is being used set the context information

				byte[] gmPublicKey = gm_public_key_bytes;
				GroupCtx commonCtx = new GroupCtx(master_secret, master_salt, alg, kdf, group_identifier, algCountersign,
						algGroupEnc, algKeyAgreement, gmPublicKey);

				commonCtx.addSenderCtxCcs(sender_id, sender_private_key);
				commonCtx.addRecipientCtxCcs(server_ID, REPLAY_WINDOW, server_public_key);

				db.addContext(requestURI, commonCtx);

				OSCoreCoapStackFactory.useAsDefault(db);				

  }
  /**
   * Creates a phantom request to register an observe relation for receiving multicast notifications.
   */


  public static void main(String requestURI, int timeout, int sender) {
	 try {
		 Configuration config = Configuration.getStandard();
      Configuration.setStandard(config);
		 
      LOGGER.debug("Requesting {}", requestURI);
      try {
			  setupOscore(requestURI, sender);
      } catch (OSException e) {
        e.printStackTrace();
        return;
      }     

      CoapEndpoint endpoint = new CoapEndpoint.Builder().setConfiguration(config).build();
      CoapClient client = new CoapClient();
      client.setEndpoint(endpoint);
      client.setURI(requestURI);
      
      Request multicastRequest = Request.newGet();
      multicastRequest.setObserve();
      multicastRequest.getOptions().setOscore(Bytes.EMPTY);
      
      ObserveHandler handler = new ObserveHandler(4);
      CoapObserveRelation relation = client.observe(multicastRequest, handler);
      
      handler.waitForNotifications();
      relation.reactiveCancel();
      client.shutdown();
        if (multicastClient != null) multicastClient.shutdown();
  } catch (Exception e) {
    LOGGER.error("Error in multicast observe client", e);
  }
  }
}