package org.eclipse.californium.examples;


import org.eclipse.californium.core.CoapClient;
import org.eclipse.californium.core.CoapResponse;
import org.eclipse.californium.core.Utils;
import org.eclipse.californium.core.CoapHandler;
import org.eclipse.californium.core.CoapObserveRelation;
import org.eclipse.californium.core.config.CoapConfig;
import org.eclipse.californium.elements.config.UdpConfig;

public class ObserveClient{
		
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
}
